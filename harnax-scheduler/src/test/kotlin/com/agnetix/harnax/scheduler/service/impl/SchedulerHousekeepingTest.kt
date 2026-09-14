package com.agnetix.harnax.scheduler.service.impl

import com.agnetix.harnax.entity.AgentTaskLog
import com.agnetix.harnax.mapper.AgentTaskLogMapper
import com.agnetix.harnax.mapper.AgentTaskMapper
import com.agnetix.harnax.scheduler.client.CommandDelivery
import com.agnetix.harnax.scheduler.client.RouterClient
import com.agnetix.harnax.scheduler.health.QuartzJobInventory
import com.agnetix.harnax.scheduler.health.SchedulerStatus
import com.agnetix.harnax.scheduler.job.SchedulerHousekeepingJob
import com.agnetix.harnax.scheduler.job.TaskQuartzRegistrar
import com.agnetix.harnax.scheduler.metrics.SchedulerMetrics
import com.agnetix.harnax.scheduler.service.AgentTaskExecutionGuard
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.quartz.JobDetail
import org.quartz.JobExecutionContext
import org.quartz.JobKey
import org.quartz.Scheduler
import org.quartz.SchedulerContext
import org.quartz.SimpleTrigger
import org.quartz.Trigger
import org.springframework.scheduling.quartz.SchedulerFactoryBean
import java.time.Duration
import java.time.LocalDateTime

/**
 * Two halves of the same promise: the sweep has to be *registered* (the guard table had no caller that
 * ever removed a row), and what it calls has to reach the database with the retention and the timeout
 * baseline it is supposed to use.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchedulerHousekeepingTest {

    @Mock
    private lateinit var schedulerFactory: SchedulerFactoryBean

    @Mock
    private lateinit var quartz: Scheduler

    @Mock
    private lateinit var agentTaskMapper: AgentTaskMapper

    @Mock
    private lateinit var agentTaskLogMapper: AgentTaskLogMapper

    @Mock
    private lateinit var routerClient: RouterClient

    @Mock
    private lateinit var executionGuard: AgentTaskExecutionGuard

    private lateinit var jobInventory: QuartzJobInventory

    private lateinit var service: SchedulerServiceImpl

    @BeforeEach
    fun setUp() {
        whenever(schedulerFactory.scheduler).thenReturn(quartz)
        whenever(quartz.checkExists(any<JobKey>())).thenReturn(false)
        whenever(agentTaskMapper.selectRunningTasks()).thenReturn(emptyList())
        jobInventory = QuartzJobInventory(schedulerFactory)
        service = SchedulerServiceImpl(
            schedulerFactory,
            agentTaskMapper,
            agentTaskLogMapper,
            routerClient,
            executionGuard,
            SchedulerStatus(schedulerEnabled = true),
            SchedulerMetrics(SimpleMeterRegistry(), jobInventory),
            jobInventory = jobInventory,
            registrar = TaskQuartzRegistrar(schedulerFactory),
            executionTimeoutSeconds = TIMEOUT,
            schedulerEnabled = true,
        )
    }

    @AfterEach
    fun tearDown() {
        service.shutdownLoadExecutor()
    }

    @Test
    fun `boot registers the housekeeping sweep as a repeating job`() {
        service.onApplicationReady()

        val jobCaptor = ArgumentCaptor.forClass(JobDetail::class.java)
        val triggerCaptor = ArgumentCaptor.forClass(Trigger::class.java)
        verify(quartz).scheduleJob(jobCaptor.capture(), triggerCaptor.capture())

        assertEquals(SchedulerHousekeepingJob::class.java, jobCaptor.value.jobClass)
        assertEquals(
            JobKey(SchedulerHousekeepingJob.JOB_NAME, SchedulerHousekeepingJob.GROUP),
            jobCaptor.value.key,
        )
        val trigger = triggerCaptor.value as SimpleTrigger
        assertEquals(SimpleTrigger.REPEAT_INDEFINITELY, trigger.repeatCount, "a one-shot sweep sweeps once")
        assertEquals(5 * 60 * 1000L, trigger.repeatInterval, "the sweep is meant to run every five minutes")
    }

    /** A second boot on a store that kept the job must not put a second sweep on the same table. */
    @Test
    fun `an already registered sweep is not registered again`() {
        whenever(quartz.checkExists(any<JobKey>())).thenReturn(true)

        service.onApplicationReady()

        verify(quartz, never()).scheduleJob(any<JobDetail>(), any<Trigger>())
    }

    /**
     * `scheduler.enabled=false` means "this node takes no scheduling work", not "nothing on this node may
     * ever write to the database again". Sweeping touches no user task and lives in its own Quartz
     * group, so an inert node must still run it — see the next test for what happens when it does not.
     * The task load, which *is* scheduling work, must stay off.
     */
    @Test
    fun `a disabled instance still registers the sweep`() {
        val disabled = disabledInstance()

        disabled.onApplicationReady()

        val jobCaptor = ArgumentCaptor.forClass(JobDetail::class.java)
        verify(quartz).scheduleJob(jobCaptor.capture(), any<Trigger>())
        assertEquals(SchedulerHousekeepingJob::class.java, jobCaptor.value.jobClass)
        verify(agentTaskMapper, never()).selectRunningTasks()
    }

    /**
     * Registration alone would be a fiction: the job takes its collaborators out of the Quartz scheduler
     * context, and `init()` used to fill that context behind the very same `scheduler.enabled` gate. This
     * is the sequence that used to be unrecoverable — `/tasks/logs/{id}/stop` is deliberately open on an
     * inert node, so the row can be written there, and `expireStale` (which matches status 3 *and* 4) was
     * reachable from nowhere else on that node. The row sat at 4 for the rest of its life.
     */
    @Test
    fun `a disabled node's own sweep reclaims the row its stop left behind`() {
        whenever(quartz.context).thenReturn(SchedulerContext())
        val disabled = disabledInstance()
        disabled.init()

        whenever(agentTaskLogMapper.selectById(21L)).thenReturn(stoppingLog(21L))
        whenever(agentTaskLogMapper.markStopping(eq(21L), anyOrNull())).thenReturn(1)
        // The router never answered, so nothing closes the row out: 4 is where it stays on this node.
        whenever(routerClient.sendCommand(any(), any())).thenReturn(CommandDelivery.Unanswered("connection refused"))
        assertTrue(disabled.stopTask(21L))

        val fired = mock<JobExecutionContext>()
        whenever(fired.scheduler).thenReturn(quartz)
        SchedulerHousekeepingJob().execute(fired)

        verify(agentTaskLogMapper).expireStale(eq(TIMEOUT))
    }

    /** The sweep's zombie baseline is the configured execution timeout, same key the fire path uses. */
    @Test
    fun `the stale sweep passes the configured timeout down to the mapper`() {
        service.expireStaleExecutions()

        verify(agentTaskLogMapper).expireStale(eq(TIMEOUT))
    }

    @Test
    fun `the log sweep cuts the retention window off create_time`() {
        val before = LocalDateTime.now()

        service.cleanupOldExecutionLogs(90)

        val captor = argumentCaptor<LocalDateTime>()
        verify(agentTaskLogMapper).deleteOldLogs(captor.capture())
        val age = Duration.between(captor.firstValue, LocalDateTime.now()).seconds
        assertTrue(
            age in (90L * 24 * 3600 - 60)..(90L * 24 * 3600 + 60),
            "cutoff $age seconds in the past is not 90 days, and $before was the clock then",
        )
    }

    /** Housekeeping must not be the thing that takes the instance down when one statement fails. */
    @Test
    fun `a failing log sweep swallows its own error`() {
        whenever(agentTaskLogMapper.deleteOldLogs(anyOrNull())).thenThrow(RuntimeException("lock wait timeout"))

        service.cleanupOldExecutionLogs(90)

        verify(agentTaskLogMapper).deleteOldLogs(anyOrNull())
    }

    /** Same collaborators as the enabled instance in `setUp`, only told not to schedule. */
    private fun disabledInstance() = SchedulerServiceImpl(
        schedulerFactory,
        agentTaskMapper,
        agentTaskLogMapper,
        routerClient,
        executionGuard,
        SchedulerStatus(schedulerEnabled = false),
        SchedulerMetrics(SimpleMeterRegistry(), jobInventory),
        jobInventory = jobInventory,
        registrar = TaskQuartzRegistrar(schedulerFactory),
        executionTimeoutSeconds = TIMEOUT,
        schedulerEnabled = false,
    )

    /** A row a stop put at 4: written by this node, owned by no execution it can still report on. */
    private fun stoppingLog(id: Long) = AgentTaskLog().apply {
        this.id = id
        taskId = 1L
        status = 4
        sessionId = "task-1-stopped"
        startTime = LocalDateTime.now().minusSeconds(60)
        createTime = LocalDateTime.now().minusSeconds(60)
    }

    companion object {
        private const val TIMEOUT = 300
    }
}

package com.agnetix.harnax.scheduler.service.impl

import com.agnetix.harnax.entity.AgentTask
import com.agnetix.harnax.mapper.AgentTaskLogMapper
import com.agnetix.harnax.mapper.AgentTaskMapper
import com.agnetix.harnax.scheduler.client.RouterClient
import com.agnetix.harnax.scheduler.health.QuartzJobInventory
import com.agnetix.harnax.scheduler.health.SchedulerStatus
import com.agnetix.harnax.scheduler.job.AgentTaskJob
import com.agnetix.harnax.scheduler.job.AgentTaskNonConcurrentJob
import com.agnetix.harnax.scheduler.job.TaskQuartzRegistrar
import com.agnetix.harnax.scheduler.metrics.SchedulerMetrics
import com.agnetix.harnax.scheduler.service.AgentTaskExecutionGuard
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.quartz.JobDetail
import org.quartz.JobKey
import org.quartz.Scheduler
import org.quartz.Trigger
import org.springframework.scheduling.quartz.SchedulerFactoryBean

/**
 * `concurrent` reaches Quartz through exactly one channel: the job class registered for the task. Pick
 * the wrong class and the flag is decoration — a misfire instruction alone cannot stop two runs of one
 * task overlapping. Both registration sites are covered because a task that forbids overlap while
 * scheduled can still be started twice by two clicks of "run now".
 *
 * The cron path is a delegation to [TaskQuartzRegistrar] now, so the registrar here is the real one over
 * the mocked Quartz [Scheduler]: these assertions have to land on the class that actually reaches the
 * store, not on a stub that was told what to answer.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchedulerScheduleTaskTest {

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

    private lateinit var service: SchedulerServiceImpl

    @BeforeEach
    fun setUp() {
        whenever(schedulerFactory.scheduler).thenReturn(quartz)
        whenever(quartz.checkExists(any<JobKey>())).thenReturn(false)
        service = SchedulerServiceImpl(
            schedulerFactory,
            agentTaskMapper,
            agentTaskLogMapper,
            routerClient,
            executionGuard,
            SchedulerStatus(schedulerEnabled = true),
            SchedulerMetrics(SimpleMeterRegistry(), QuartzJobInventory(schedulerFactory)),
            jobInventory = QuartzJobInventory(schedulerFactory),
            registrar = TaskQuartzRegistrar(schedulerFactory),
            executionTimeoutSeconds = 300,
            schedulerEnabled = true,
        )
    }

    @Test
    fun `a non-concurrent cron registers the disallowed-concurrency job class`() {
        service.scheduleTask(task(concurrent = 0))

        assertEquals(AgentTaskNonConcurrentJob::class.java, registeredJobClass())
    }

    @Test
    fun `a concurrent cron registers the plain job class`() {
        service.scheduleTask(task(concurrent = 1))

        assertEquals(AgentTaskJob::class.java, registeredJobClass())
    }

    @Test
    fun `a non-concurrent run-once registers the disallowed-concurrency job class`() {
        whenever(agentTaskMapper.selectAnyById(TASK_ID)).thenReturn(task(concurrent = 0))

        service.runTaskOnce(TASK_ID)

        assertEquals(AgentTaskNonConcurrentJob::class.java, scheduledOnceJobClass())
    }

    @Test
    fun `a concurrent run-once registers the plain job class`() {
        whenever(agentTaskMapper.selectAnyById(TASK_ID)).thenReturn(task(concurrent = 1))

        service.runTaskOnce(TASK_ID)

        assertEquals(AgentTaskJob::class.java, scheduledOnceJobClass())
    }

    /**
     * The one-shot job goes into the same table under the same rule: with `useProperties: true` an
     * `AgentTask` in its data map is a hard store error, and a snapshot of the prompt anywhere else.
     */
    @Test
    fun `a run-once hands the store only the task id`() {
        whenever(agentTaskMapper.selectAnyById(TASK_ID)).thenReturn(task(concurrent = 1))

        service.runTaskOnce(TASK_ID)

        val data = scheduledOnceJobDetail().jobDataMap
        assertEquals(TASK_ID.toString(), data.getString(TaskQuartzRegistrar.KEY_TASK_ID))
        assertEquals(1, data.size, "the id is the only thing a JDBC store with useProperties may carry")
        assertNull(data["agentTask"], "the entity must not be reachable from the one-shot path either")
    }

    /** `scheduleJob(jobDetail, triggers, replace)` is the atomic replace the cron path uses. */
    private fun registeredJobClass(): Class<*> {
        val captor = ArgumentCaptor.forClass(JobDetail::class.java)
        verify(quartz).scheduleJob(captor.capture(), any<MutableSet<Trigger>>(), eq(true))
        return captor.value.jobClass
    }

    private fun scheduledOnceJobClass(): Class<*> = scheduledOnceJobDetail().jobClass

    private fun scheduledOnceJobDetail(): JobDetail {
        val captor = ArgumentCaptor.forClass(JobDetail::class.java)
        verify(quartz).scheduleJob(captor.capture(), any<Trigger>())
        return captor.value
    }

    private fun task(concurrent: Int) = AgentTask().apply {
        id = TASK_ID
        name = "Ticker"
        agentId = 1L
        prompt = "p"
        cronExpression = "0 0/5 * * * ?"
        this.concurrent = concurrent
    }

    companion object {
        private const val TASK_ID = 5L
    }
}

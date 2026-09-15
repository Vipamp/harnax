package com.agnetix.harnax.scheduler.service.impl

import com.agnetix.harnax.scheduler.client.RouterClient
import com.agnetix.harnax.scheduler.entity.AgentTask
import com.agnetix.harnax.scheduler.entity.AgentTaskLog
import com.agnetix.harnax.scheduler.health.QuartzJobInventory
import com.agnetix.harnax.scheduler.health.SchedulerHealthIndicator
import com.agnetix.harnax.scheduler.health.SchedulerStatus
import com.agnetix.harnax.scheduler.job.TaskQuartzRegistrar
import com.agnetix.harnax.scheduler.mapper.AgentTaskLogMapper
import com.agnetix.harnax.scheduler.mapper.AgentTaskMapper
import com.agnetix.harnax.scheduler.metrics.SchedulerMetrics
import com.agnetix.harnax.scheduler.service.AgentTaskExecutionGuard
import com.agnetix.harnax.scheduler.service.ReconcileReport
import com.agnetix.harnax.scheduler.service.TaskScheduleReconciler
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.quartz.JobDetail
import org.quartz.JobKey
import org.quartz.Scheduler
import org.quartz.Trigger
import org.springframework.boot.health.contributor.Status
import org.springframework.scheduling.quartz.SchedulerFactoryBean
import java.time.LocalDateTime

/**
 * A conflict has to leave the service as a return value: the controller turns false into business
 * code 40901, and an exception instead would degrade to a generic 500 for the caller. These cases run
 * against the real service — the controller tests mock it away and cannot see the difference.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchedulerServiceImplTest {

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

    private lateinit var status: SchedulerStatus

    private lateinit var jobInventory: QuartzJobInventory

    private lateinit var service: SchedulerServiceImpl

    @BeforeEach
    fun setUp() {
        whenever(schedulerFactory.scheduler).thenReturn(quartz)
        status = SchedulerStatus(schedulerEnabled = true)
        // The job count lives in QuartzJobInventory now; a real one over the mocked factory keeps the
        // service delegation and the health read on the production path.
        jobInventory = QuartzJobInventory(schedulerFactory)
        // The registrar is the service's one writer into the store, and the cases below assert what
        // reaches Quartz (one replace call, a bad cron refused before anything is written), so it is the
        // real one over the same mocked factory. Delegation itself is the next test's job.
        service = serviceWith(TaskQuartzRegistrar(schedulerFactory))
    }

    private fun serviceWith(
        registrar: TaskQuartzRegistrar,
        reconciler: TaskScheduleReconciler = mock<TaskScheduleReconciler>(),
    ) = SchedulerServiceImpl(
        schedulerFactory,
        agentTaskMapper,
        agentTaskLogMapper,
        routerClient,
        executionGuard,
        status,
        SchedulerMetrics(SimpleMeterRegistry(), jobInventory),
        jobInventory = jobInventory,
        registrar = registrar,
        reconciler = reconciler,
        executionTimeoutSeconds = 300,
        reconcileIntervalSeconds = 60,
        schedulerEnabled = true,
    )

    /**
     * The store write is the registrar's alone: a second copy of the job-key/cron/misfire rules in this
     * service is what the reconciler would then drift from.
     */
    @Test
    fun `scheduleTask and unscheduleTask hand the store write to the registrar`() {
        val registrar = mock<TaskQuartzRegistrar>()
        val task = cronTask(TASK_ID, "0 0 9 * * ?")

        serviceWith(registrar).apply {
            scheduleTask(task)
            unscheduleTask(task)
        }

        verify(registrar).register(task)
        verify(registrar).unregister(TASK_ID)
        verifyNoInteractions(quartz)
    }

    /**
     * Re-aimed at the one-shot delivery, which `/trigger` and `/run-once` now share. The property that made
     * this a separate case is the one the merged path has to keep: a refusal writes nothing anywhere, and
     * the cluster lock is not this call's business.
     */
    @Test
    fun `a manual run on a task with a live execution is rejected as false, with no lock and no store write`() {
        givenTaskWithActiveRunningLog()

        val rejected = assertDoesNotThrow { service.runTaskOnce(TASK_ID) }

        assertFalse(rejected)
        // No lock at delivery time at all: the job takes it when it fires, on whichever node claims the
        // trigger. Nothing was scheduled either, so the refused click leaves no orphan behind.
        verify(executionGuard, never()).tryAcquireLock(any(), any())
        verify(quartz, never()).scheduleJob(any<JobDetail>(), any<Trigger>())
    }

    @Test
    fun `a run-once on a task with a live execution is rejected as false, not as an exception`() {
        givenTaskWithActiveRunningLog()

        val rejected = assertDoesNotThrow { service.runTaskOnce(TASK_ID) }

        assertFalse(rejected)
        verify(quartz, never()).scheduleJob(any(), any())
    }

    /**
     * `concurrent = 1` is the task saying "another run may overlap this one" (entity/DDL: 0 = no
     * overlap, 1 = allow). Refusing a manual run anyway turns that flag into a lie and answers the
     * user with 40901 for a conflict their own task declared acceptable.
     *
     * The lock check is the inversion this task is about: the path that used to serve `/trigger` acquired
     * the cluster lock here, on the node that happened to be asked, and then ran the work on a thread. A
     * one-shot cannot do that — the fire may land on a different node, and a lock taken for a run nobody
     * started is a leaked row housekeeping only reaps after twice the timeout.
     */
    @Test
    fun `a manual run on a concurrency-tolerant task is delivered while an execution is live, lockless`() {
        givenTaskWithActiveRunningLog(concurrent = 1)

        assertTrue(service.runTaskOnce(TASK_ID), "concurrent=1 must not be blocked by a live execution")

        verify(quartz).scheduleJob(any<JobDetail>(), any<Trigger>())
        verify(executionGuard, never()).tryAcquireLock(any(), any())
    }

    @Test
    fun `a run-once on a concurrency-tolerant task is scheduled while an execution is live`() {
        givenTaskWithActiveRunningLog(concurrent = 1)

        assertTrue(service.runTaskOnce(TASK_ID))

        verify(quartz).scheduleJob(any(), any())
    }

    /**
     * The running-log insert used to be the one step outside any error handling: its return value was
     * dropped, and because it sits before the try/finally a failure there never reached the finally that
     * releases the cluster lock. The result was the worst combination — no log row, a lock stuck at
     * status=0 (which every later trigger reads as "already running"), and a caller who had already
     * been answered "Task triggered".
     */
    @Test
    fun `an execution whose running log was not created fails and releases the lock row`() {
        whenever(agentTaskLogMapper.insert(any())).thenReturn(0)

        assertThrows(RuntimeException::class.java) { service.executeTaskOnce(task(), LocalDateTime.now()) }

        // Nothing was scheduled and no row exists to close out: no success may be written anywhere.
        verify(routerClient, never()).chat(any(), any())
        verify(agentTaskLogMapper, never()).finishExecution(any())
        verify(executionGuard).updateExecutionStatus(eq(TASK_ID), any(), eq(false), any(), any())
    }

    @Test
    fun `a running log that came back without a generated id is a failure too`() {
        // The statement reported one affected row but the key did not come back: from here that row is
        // unreachable, so it cannot be closed out later and must not be treated as running.
        whenever(agentTaskLogMapper.insert(any())).thenReturn(1)

        assertThrows(RuntimeException::class.java) { service.executeTaskOnce(task(), LocalDateTime.now()) }

        verify(routerClient, never()).chat(any(), any())
        verify(agentTaskLogMapper, never()).finishExecution(any())
        verify(executionGuard).updateExecutionStatus(eq(TASK_ID), any(), eq(false), any(), any())
    }

    @Test
    fun `a running-log insert that throws still releases the lock row`() {
        whenever(agentTaskLogMapper.insert(any())).thenThrow(RuntimeException("Data too long for column 'prompt'"))

        assertThrows(RuntimeException::class.java) { service.executeTaskOnce(task(), LocalDateTime.now()) }

        verify(agentTaskLogMapper, never()).finishExecution(any())
        verify(executionGuard).updateExecutionStatus(eq(TASK_ID), any(), eq(false), any(), any())
    }

    /**
     * 修 6: the sequence was `checkExists -> deleteJob -> scheduleJob`, and the spec's own premise is
     * weaker than the hole — an invalid cron already throws during the build, above. What actually bites
     * is the gap between the delete and the write: a failed or lost last call (paused scheduler,
     * job-store error, a reload racing a start) leaves the task with no job at all while agent_task still
     * says task_status=1, so nothing fires and nothing reports it.
     */
    @Test
    fun `re-scheduling a task that already has a live job replaces it without a delete window`() {
        service.scheduleTask(cronTask(TASK_ID, "0 0 9 * * ?"))

        verify(quartz, never()).deleteJob(any<JobKey>())
        verify(quartz).scheduleJob(any<JobDetail>(), any<MutableSet<Trigger>>(), eq(true))
    }

    /** The cron is validated while the trigger is built, so a bad one must not cost the live schedule. */
    @Test
    fun `an invalid cron fails the start before the live schedule is touched`() {
        whenever(agentTaskMapper.selectAnyById(TASK_ID)).thenReturn(cronTask(TASK_ID, "0 0 0 * * *"))

        assertThrows(RuntimeException::class.java) { service.startTask(TASK_ID) }

        verify(quartz, never()).deleteJob(any<JobKey>())
        verify(quartz, never()).scheduleJob(any<JobDetail>(), any<MutableSet<Trigger>>(), anyBoolean())
        // The row must not be flipped to running for a definition that never made it into the scheduler.
        verify(agentTaskMapper, never()).updateStatus(eq(TASK_ID), anyInt())
    }

    /**
     * The service owns none of the converge any more — it hands the round to
     * [com.agnetix.harnax.scheduler.service.TaskScheduleReconciler] and answers with its report. What the
     * report *means* is pinned there and in `SchedulerStartupReconcileTest`; what is pinned here is that a
     * round leaving drift reaches the caller as drift, since `/reload`'s answer and the health verdict both
     * read it off this return value.
     */
    @Test
    fun `reconcileTasks answers with the reconciler's report so a drifting round is not a success`() {
        val report = ReconcileReport(added = 0, removed = 0, updated = 1, unchanged = 3, failedIds = listOf(2L))
        val reconciler = mock<TaskScheduleReconciler>()
        whenever(reconciler.reconcile()).thenReturn(report)
        whenever(quartz.isStarted).thenReturn(true)
        status.recordReconcile(jobCount = 4, pendingError = "1 of 5 active tasks could not be registered: ids=[2]")

        val answered = serviceWith(TaskQuartzRegistrar(schedulerFactory), reconciler).reconcileTasks()

        verify(reconciler).reconcile()
        assertEquals(report, answered)
        assertFalse(answered.converged, "a partial round must not be reported as a completed reload")
        assertEquals(
            Status.DOWN,
            SchedulerHealthIndicator(status, schedulerFactory, jobInventory).health().status,
            "health must not read UP just because some tasks did get registered",
        )
    }

    /**
     * The running-log guard expires stale rows before reading, so that read is stubbed too — otherwise
     * a test would pass on a mocked-out mapper rather than on the guard's own decision.
     *
     * Nothing else is stubbed, and that is the point of the delivery path: it reads the task, reads the log
     * table at most once, hands one job and one trigger to the store and returns. No lock row is written, no
     * log row is inserted and the router is never called — a run that gets this far executes when Quartz
     * fires it, not here.
     */
    private fun givenTaskWithActiveRunningLog(concurrent: Int = 0) {
        whenever(agentTaskMapper.selectAnyById(TASK_ID)).thenReturn(task(concurrent = concurrent))
        whenever(agentTaskLogMapper.expireStale(anyInt())).thenReturn(0)
        whenever(agentTaskLogMapper.selectRunningByTaskId(TASK_ID)).thenReturn(listOf(runningLog()))
    }

    private fun task(concurrent: Int = 0) = AgentTask().apply {
        id = TASK_ID
        name = "Daily News"
        prompt = "summarize today"
        creator = "admin"
        this.concurrent = concurrent
        timeoutSeconds = 300
    }

    private fun cronTask(id: Long, cron: String) = task().apply {
        this.id = id
        name = "Task $id"
        cronExpression = cron
    }

    private fun runningLog() = AgentTaskLog().apply {
        id = 21L
        taskId = TASK_ID
        taskName = "Daily News"
        status = 3
        sessionId = "sess-21"
        startTime = LocalDateTime.now().minusSeconds(5)
    }

    companion object {
        private const val TASK_ID = 1L
    }
}

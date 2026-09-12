package com.agnetix.harnax.scheduler.service.impl

import com.agnetix.harnax.agent.protocol.ChatResponse
import com.agnetix.harnax.entity.AgentTask
import com.agnetix.harnax.entity.AgentTaskLog
import com.agnetix.harnax.mapper.AgentTaskLogMapper
import com.agnetix.harnax.mapper.AgentTaskMapper
import com.agnetix.harnax.scheduler.client.RouterClient
import com.agnetix.harnax.scheduler.health.SchedulerHealthIndicator
import com.agnetix.harnax.scheduler.health.SchedulerStatus
import com.agnetix.harnax.scheduler.metrics.SchedulerMetrics
import com.agnetix.harnax.scheduler.service.AgentTaskExecutionGuard
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
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

    private lateinit var service: SchedulerServiceImpl

    @BeforeEach
    fun setUp() {
        whenever(schedulerFactory.scheduler).thenReturn(quartz)
        status = SchedulerStatus(schedulerEnabled = true)
        val metrics = SchedulerMetrics(SimpleMeterRegistry(), status)
        service = SchedulerServiceImpl(
            schedulerFactory,
            agentTaskMapper,
            agentTaskLogMapper,
            routerClient,
            executionGuard,
            status,
            metrics,
            schedulerEnabled = true,
        )
    }

    @Test
    fun `a manual trigger on a task with a live execution is rejected as false, not as an exception`() {
        givenTaskWithActiveRunningLog()

        val rejected = assertDoesNotThrow { service.triggerManually(TASK_ID) }

        assertFalse(rejected)
        // Rejected before the cluster lock, so no other instance can be told to run it either.
        verify(executionGuard, never()).tryAcquireLock(any(), any())
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
     * overlap, 1 = allow). Refusing a manual trigger anyway turns that flag into a lie and answers the
     * user with 40901 for a conflict their own task declared acceptable.
     */
    @Test
    fun `a manual trigger on a concurrency-tolerant task is accepted while an execution is live`() {
        givenTaskWithActiveRunningLog(concurrent = 1)

        assertTrue(service.triggerManually(TASK_ID), "concurrent=1 must not be blocked by a live execution")

        // Past the log guard and into the cluster lock, which is what actually dedupes instances.
        verify(executionGuard).tryAcquireLock(any(), any())
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
        whenever(quartz.checkExists(any<JobKey>())).thenReturn(true)

        service.scheduleTask(cronTask(TASK_ID, "0 0 9 * * ?"))

        verify(quartz, never()).deleteJob(any<JobKey>())
        verify(quartz).scheduleJob(any<JobDetail>(), any<MutableSet<Trigger>>(), eq(true))
    }

    /** The cron is validated while the trigger is built, so a bad one must not cost the live schedule. */
    @Test
    fun `an invalid cron fails the start before the live schedule is touched`() {
        whenever(agentTaskMapper.selectAnyById(TASK_ID)).thenReturn(cronTask(TASK_ID, "0 0 0 * * *"))
        whenever(quartz.checkExists(any<JobKey>())).thenReturn(true)

        assertThrows(RuntimeException::class.java) { service.startTask(TASK_ID) }

        verify(quartz, never()).deleteJob(any<JobKey>())
        verify(quartz, never()).scheduleJob(any<JobDetail>(), any<MutableSet<Trigger>>(), anyBoolean())
        // The row must not be flipped to running for a definition that never made it into the scheduler.
        verify(agentTaskMapper, never()).updateStatus(eq(TASK_ID), anyInt())
    }

    /**
     * Registering 1 of 2 active tasks is drift, not a success: the health signal and the /reload
     * answer both have to keep saying so, otherwise the instance quietly stops scheduling one task
     * while every probe reads UP.
     */
    @Test
    fun `a load that registers only part of the active tasks keeps the load error and reports false`() {
        val badCronTaskId = 2L
        whenever(agentTaskMapper.selectRunningTasks())
            .thenReturn(listOf(cronTask(1L, "0 0 9 * * ?"), cronTask(badCronTaskId, "definitely not a cron")))
        whenever(quartz.isStarted).thenReturn(true)

        val reloaded = service.loadTasksToScheduler()

        assertFalse(reloaded, "a partial registration must not be reported as a completed reload")
        val error = status.lastLoadError
        assertNotNull(error, "a partial registration must leave lastLoadError set")
        assertTrue(
            error!!.contains("ids=[$badCronTaskId]"),
            "the error has to name the task that could not be registered, got: $error",
        )
        assertEquals(
            Status.DOWN,
            SchedulerHealthIndicator(status, schedulerFactory).health().status,
            "health must not read UP just because some tasks did get registered",
        )
    }

    /**
     * The running-log guard expires stale rows before reading, so that read is stubbed too — otherwise
     * a test would pass on a mocked-out mapper rather than on the guard's own decision.
     */
    private fun givenTaskWithActiveRunningLog(concurrent: Int = 0) {
        whenever(agentTaskMapper.selectAnyById(TASK_ID)).thenReturn(task(concurrent = concurrent))
        whenever(agentTaskLogMapper.expireStale(anyInt())).thenReturn(0)
        whenever(agentTaskLogMapper.selectRunningByTaskId(TASK_ID)).thenReturn(listOf(runningLog()))
        // Not the source of the rejection on purpose: if the log guard ever stops short-circuiting,
        // the manual-trigger case still has to fail instead of slipping through the lock path.
        whenever(executionGuard.tryAcquireLock(any(), any())).thenReturn(true)
        givenExecutionPathIsHarmless()
    }

    /**
     * A trigger that gets past the guards hands the run to a background thread. Stub its whole path so
     * the thread finishes on its own: an assertion that races a still-running mock is how suites turn
     * flaky.
     */
    private fun givenExecutionPathIsHarmless() {
        whenever(agentTaskLogMapper.insert(any())).thenAnswer {
            it.getArgument<AgentTaskLog>(0).id = 77L
            1
        }
        whenever(agentTaskLogMapper.finishExecution(any())).thenReturn(1)
        whenever(routerClient.chat(any(), any())).thenReturn(ChatResponse(sessionId = "s", content = "ok"))
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

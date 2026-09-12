package com.agnetix.harnax.scheduler.job

import com.agnetix.harnax.entity.AgentTask
import com.agnetix.harnax.scheduler.service.AgentTaskExecutionGuard
import com.agnetix.harnax.scheduler.service.SchedulerService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.quartz.DisallowConcurrentExecution
import org.quartz.InterruptableJob
import org.quartz.JobDataMap
import org.quartz.JobDetail
import org.quartz.JobExecutionContext
import org.quartz.Scheduler
import org.quartz.SchedulerContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A Quartz job that hands its work to another thread is a job that finished: Quartz writes no
 * `QRTZ_FIRED_TRIGGERS` row, so fail-over has nothing to take over, graceful shutdown has nothing to
 * wait for, and `@DisallowConcurrentExecution` has no live execution to keep away a second fire.
 * Everything below therefore keys on the same property: when `execute()` returns, the execution is over.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentTaskJobExecutionTest {

    @Mock
    private lateinit var context: JobExecutionContext

    @Mock
    private lateinit var scheduler: Scheduler

    @Mock
    private lateinit var jobDetail: org.quartz.JobDetail

    @Mock
    private lateinit var schedulerService: SchedulerService

    @Mock
    private lateinit var executionGuard: AgentTaskExecutionGuard

    private val fireTime = Date.from(
        LocalDateTime.of(2026, 9, 11, 2, 30, 0).atZone(ZoneId.systemDefault()).toInstant(),
    )

    /**
     * @param concurrent what the task says about overlap; @param activeExecution what the store answers
     * when the fire asks whether a run of this task is already live.
     */
    private fun setUpJob(
        concurrent: Int = 0,
        activeExecution: Boolean = false,
        lockAcquired: Boolean = true,
    ) {
        val task = AgentTask().apply {
            id = 3L
            name = "Nightly"
            agentId = 9L
            prompt = "go"
            this.concurrent = concurrent
        }
        whenever(context.jobDetail).thenReturn(jobDetail)
        whenever(jobDetail.jobDataMap).thenReturn(JobDataMap().apply { put("agentTask", task) })
        whenever(context.scheduler).thenReturn(scheduler)
        whenever(scheduler.context).thenReturn(
            SchedulerContext().apply {
                put("schedulerService", schedulerService)
                put("executionGuard", executionGuard)
            },
        )
        whenever(context.scheduledFireTime).thenReturn(fireTime)
        whenever(executionGuard.tryAcquireLock(any(), any())).thenReturn(lockAcquired)
        whenever(schedulerService.hasActiveRunningExecution(any())).thenReturn(activeExecution)
    }

    @Test
    fun `execute returns only once the task has run to completion`() {
        setUpJob(concurrent = 1)
        val finished = AtomicBoolean(false)
        whenever(schedulerService.executeTaskOnce(any(), any())).thenAnswer {
            Thread.sleep(SLOW_EXECUTION_MS)
            finished.set(true)
            Unit
        }

        AgentTaskJob().execute(context)

        assertTrue(
            finished.get(),
            "execute() returned while the task was still running — Quartz would file this fire as finished",
        )
        verify(schedulerService).executeTaskOnce(any(), any())
    }

    /**
     * The subclass has to keep the guarantee too: inheriting a synchronous body is worthless if the
     * registered class ends up overriding it with a hand-off.
     */
    @Test
    fun `the non-concurrent job also runs the task on the calling thread`() {
        setUpJob(concurrent = 0, activeExecution = false)
        val callerThread = Thread.currentThread().name
        var ranOn: String? = null
        whenever(schedulerService.executeTaskOnce(any(), any())).thenAnswer {
            ranOn = Thread.currentThread().name
            Unit
        }

        AgentTaskNonConcurrentJob().execute(context)

        assertEquals(callerThread, ranOn, "the execution escaped the Quartz worker thread")
    }

    /**
     * `@DisallowConcurrentExecution` is read off the registered class with plain reflection and is not
     * `@Inherited`, so the same annotation on [AbstractAgentTaskJob] would be silently ignored.
     */
    @Test
    fun `only the non-concurrent class carries the annotation and it sits on the class itself`() {
        assertTrue(
            AgentTaskNonConcurrentJob::class.java.isAnnotationPresent(DisallowConcurrentExecution::class.java),
            "concurrent=0 tasks must register a class Quartz recognises as non-concurrent",
        )
        assertFalse(
            AgentTaskJob::class.java.isAnnotationPresent(DisallowConcurrentExecution::class.java),
            "a concurrent task must still be allowed to overlap itself",
        )
        assertFalse(
            AbstractAgentTaskJob::class.java.isAnnotationPresent(DisallowConcurrentExecution::class.java),
            "on the base class the annotation would apply to neither subclass and mislead the reader",
        )
    }

    /**
     * Stopping a run is the router's job (INTERRUPT via SchedulerService.stopTask). Implementing
     * `InterruptableJob` with an empty `interrupt()` advertised a capability that does not exist here.
     */
    @Test
    fun `neither job class pretends to be interruptable by Quartz`() {
        assertFalse(
            InterruptableJob::class.java.isAssignableFrom(AgentTaskJob::class.java),
            "AgentTaskJob must not be an InterruptableJob",
        )
        assertFalse(
            InterruptableJob::class.java.isAssignableFrom(AgentTaskNonConcurrentJob::class.java),
            "AgentTaskNonConcurrentJob must not be an InterruptableJob",
        )
    }

    /**
     * The annotation is per JobDetail, and one task owns two of them: the cron `AgentTask_{id}` and the
     * click-time `AgentTask_{id}_ONCE_*`. Only a read keyed by *task* can see across that boundary.
     */
    @Test
    fun `a non-concurrent task with a live execution skips this fire`() {
        setUpJob(concurrent = 0, activeExecution = true)

        AgentTaskNonConcurrentJob().execute(context)

        verify(schedulerService).hasActiveRunningExecution(TASK_ID)
        // Before the cluster lock, on purpose: a lock taken for a run that never happens is a leak
        // that housekeeping then has two timeouts' worth of patience for.
        verify(executionGuard, never()).tryAcquireLock(any(), any())
        verify(schedulerService, never()).executeTaskOnce(any(), any())
    }

    /** `concurrent = 1` is the task permitting overlap, so nothing may withhold a fire over a live run. */
    @Test
    fun `a concurrent task never asks whether another run is live`() {
        setUpJob(concurrent = 1, activeExecution = true)

        AgentTaskJob().execute(context)

        verify(schedulerService, never()).hasActiveRunningExecution(any())
        verify(schedulerService).executeTaskOnce(any(), any())
    }

    @Test
    fun `a fire another instance already claimed does not run here`() {
        setUpJob(concurrent = 1, lockAcquired = false)

        AgentTaskJob().execute(context)

        verify(schedulerService, never()).executeTaskOnce(any(), any())
    }

    /**
     * Every node must derive the same trigger identity from one fire, or the guard's unique key stops
     * being a key: wall clock differs per instance, the scheduled fire time does not.
     */
    @Test
    fun `the execution is keyed by the scheduled fire time rather than by the local clock`() {
        setUpJob(concurrent = 1)
        val expected = LocalDateTime.ofInstant(fireTime.toInstant(), ZoneId.systemDefault())

        AgentTaskJob().execute(context)

        val taskCaptor = argumentCaptor<AgentTask>()
        val timeCaptor = argumentCaptor<LocalDateTime>()
        verify(schedulerService).executeTaskOnce(taskCaptor.capture(), timeCaptor.capture())
        assertEquals(TASK_ID, taskCaptor.firstValue.id)
        assertEquals(expected, timeCaptor.firstValue)
        verify(executionGuard).tryAcquireLock(eq(TASK_ID), eq(expected))
    }

    companion object {
        private const val TASK_ID = 3L
        private const val SLOW_EXECUTION_MS = 150L
    }
}

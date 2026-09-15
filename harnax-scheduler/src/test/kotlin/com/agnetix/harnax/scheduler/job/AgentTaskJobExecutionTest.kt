package com.agnetix.harnax.scheduler.job

import com.agnetix.harnax.entity.AgentTask
import com.agnetix.harnax.mapper.AgentTaskMapper
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
import org.quartz.JobKey
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

    @Mock
    private lateinit var agentTaskMapper: AgentTaskMapper

    private val fireTime = Date.from(
        LocalDateTime.of(2026, 9, 11, 2, 30, 0).atZone(ZoneId.systemDefault()).toInstant(),
    )

    private val cronJobKey = JobKey("AgentTask_$TASK_ID", TaskQuartzRegistrar.GROUP_AGENT_TASK)

    /** The name and group `SchedulerServiceImpl.runTaskOnce` writes: the fire path reads the *group*. */
    private val onceJobKey = JobKey("AgentTask_${TASK_ID}_ONCE_a1b2c3d4", TaskQuartzRegistrar.GROUP_ONCE)

    /**
     * @param concurrent what the row says about overlap; @param activeExecution what the store answers
     * when the fire asks whether a run of this task is already live.
     *
     * The stored job carries the task id and nothing else, so everything the fire runs on comes back out of
     * [agentTaskMapper] — which is why the row's own status is a parameter here rather than a given.
     */
    private fun setUpJob(
        concurrent: Int = 0,
        activeExecution: Boolean = false,
        lockAcquired: Boolean = true,
        schedulingEnabled: Boolean = true,
        taskStatus: Int = 1,
        active: Int = 1,
        taskExists: Boolean = true,
        jobKey: JobKey = cronJobKey,
        jobDataMap: JobDataMap = JobDataMap().apply { put(TaskQuartzRegistrar.KEY_TASK_ID, TASK_ID.toString()) },
    ) {
        whenever(context.jobDetail).thenReturn(jobDetail)
        whenever(jobDetail.jobDataMap).thenReturn(jobDataMap)
        whenever(jobDetail.key).thenReturn(jobKey)
        whenever(context.scheduler).thenReturn(scheduler)
        whenever(scheduler.context).thenReturn(
            SchedulerContext().apply {
                put("schedulerService", schedulerService)
                put("executionGuard", executionGuard)
                put("agentTaskMapper", agentTaskMapper)
            },
        )
        whenever(context.scheduledFireTime).thenReturn(fireTime)
        whenever(executionGuard.tryAcquireLock(any(), any())).thenReturn(lockAcquired)
        whenever(schedulerService.hasActiveRunningExecution(any())).thenReturn(activeExecution)
        whenever(schedulerService.schedulingEnabled).thenReturn(schedulingEnabled)
        whenever(agentTaskMapper.selectAnyById(TASK_ID))
            .thenReturn(if (taskExists) task(concurrent = concurrent, taskStatus = taskStatus, active = active) else null)
    }

    private fun task(
        concurrent: Int,
        taskStatus: Int = 1,
        active: Int = 1,
    ) = AgentTask().apply {
        id = TASK_ID
        name = "Nightly"
        agentId = 9L
        prompt = "go"
        this.concurrent = concurrent
        this.taskStatus = taskStatus
        this.active = active
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
     * A delete that never reached the store leaves a job that fires for no row. The job is the stale half
     * of that pair, so the fire is what converges them — and nothing may run in between.
     */
    @Test
    fun `a task deleted since registration takes its job out of the store`() {
        setUpJob(concurrent = 1, taskExists = false)

        AgentTaskJob().execute(context)

        verify(scheduler).deleteJob(cronJobKey)
        verify(schedulerService, never()).executeTaskOnce(any(), any())
        verify(executionGuard, never()).tryAcquireLock(any(), any())
    }

    /**
     * The store is shared, so this node can be the one Quartz hands a fire to even though it registered
     * nothing and runs with `scheduler.enabled=false`. Leaving it alone is the whole point: the other node
     * claims the next fire.
     *
     * And it leaves *early*: no `agent_task` read either, because the read is what notices a row that has
     * gone away — and deleting that orphaned job is a scheduling write, which is exactly what this instance
     * exists not to perform (`SchedulerController.requireEnabled` refuses the same thing on the API).
     */
    @Test
    fun `a fire handed to a node with scheduling disabled does nothing`() {
        setUpJob(concurrent = 1, schedulingEnabled = false)

        AgentTaskJob().execute(context)

        verify(agentTaskMapper, never()).selectAnyById(any())
        verify(schedulerService, never()).executeTaskOnce(any(), any())
        verify(executionGuard, never()).tryAcquireLock(any(), any())
        // Not the job's fault either: a disabled node must not delete a schedule the enabled one owns.
        verify(scheduler, never()).deleteJob(any())
    }

    /**
     * The same refusal in the click's shape: the enabled guard sits in `AbstractAgentTaskJob.run` before any
     * group discrimination, so a one-shot trigger a shared store hands to a disabled node is left for the
     * scheduling one exactly as a cron fire would be.
     */
    @Test
    fun `a one-shot handed to a node with scheduling disabled does nothing either`() {
        setUpJob(concurrent = 1, schedulingEnabled = false, jobKey = onceJobKey)

        AgentTaskJob().execute(context)

        verify(agentTaskMapper, never()).selectAnyById(any())
        verify(schedulerService, never()).executeTaskOnce(any(), any())
        verify(executionGuard, never()).tryAcquireLock(any(), any())
        verify(scheduler, never()).deleteJob(any())
    }

    /** A pause (or a soft delete) that outlived its registration must not run on the next cron tick. */
    @Test
    fun `a task that has since been paused does not run`() {
        setUpJob(concurrent = 1, taskStatus = 0)

        AgentTaskJob().execute(context)

        verify(schedulerService, never()).executeTaskOnce(any(), any())
        verify(executionGuard, never()).tryAcquireLock(any(), any())
        verify(scheduler, never()).deleteJob(any())
    }

    /**
     * The other half of that boundary: `taskStatus` guards a *cron*, because a stored cron job is a lagging
     * copy of `agent_task`. A one-shot is exempt for the opposite reason — its registration *is* the user's
     * intent, made seconds ago, so a pause that lands between the click and the fire must not swallow the run
     * the user just asked for. The group is what tells the two apart.
     */
    @Test
    fun `a paused task still runs when it was asked to run once`() {
        setUpJob(concurrent = 1, taskStatus = 0, jobKey = onceJobKey)

        AgentTaskJob().execute(context)

        verify(schedulerService).executeTaskOnce(any(), any())
    }

    /** A delete is not a pause: not even a click buys a soft-deleted task a run. */
    @Test
    fun `a one-shot does not run a task that has been deleted`() {
        setUpJob(concurrent = 1, active = 0, jobKey = onceJobKey)

        AgentTaskJob().execute(context)

        verify(schedulerService, never()).executeTaskOnce(any(), any())
    }

    /** A job nobody registered through the registrar is a broken job, not a task to guess about. */
    @Test
    fun `a fire whose job carries no task id is refused without touching the store`() {
        setUpJob(concurrent = 1, jobDataMap = JobDataMap())

        AgentTaskJob().execute(context)

        verify(agentTaskMapper, never()).selectAnyById(any())
        verify(schedulerService, never()).executeTaskOnce(any(), any())
        verify(scheduler, never()).deleteJob(any())
    }

    /**
     * The reason the entity left the store: a job registered before an edit must not keep firing the
     * pre-edit prompt. Only the row can answer for what a fire runs.
     */
    @Test
    fun `the fire runs the row as it is now rather than anything the job carried`() {
        setUpJob(concurrent = 1)
        whenever(agentTaskMapper.selectAnyById(TASK_ID)).thenReturn(
            task(concurrent = 1).apply { prompt = "the edited prompt" },
        )

        AgentTaskJob().execute(context)

        val captor = argumentCaptor<AgentTask>()
        verify(schedulerService).executeTaskOnce(captor.capture(), any())
        assertEquals("the edited prompt", captor.firstValue.prompt)
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

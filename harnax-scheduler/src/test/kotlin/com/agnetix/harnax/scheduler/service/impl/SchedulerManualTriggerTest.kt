package com.agnetix.harnax.scheduler.service.impl

import com.agnetix.harnax.scheduler.client.RouterClient
import com.agnetix.harnax.scheduler.controller.SchedulerController
import com.agnetix.harnax.scheduler.entity.AgentTask
import com.agnetix.harnax.scheduler.entity.AgentTaskLog
import com.agnetix.harnax.scheduler.health.QuartzJobInventory
import com.agnetix.harnax.scheduler.health.SchedulerStatus
import com.agnetix.harnax.scheduler.job.TaskQuartzRegistrar
import com.agnetix.harnax.scheduler.mapper.AgentTaskLogMapper
import com.agnetix.harnax.scheduler.mapper.AgentTaskMapper
import com.agnetix.harnax.scheduler.metrics.SchedulerMetrics
import com.agnetix.harnax.scheduler.service.AgentTaskExecutionGuard
import com.agnetix.harnax.scheduler.service.TaskScheduleReconciler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.quartz.JobDetail
import org.quartz.Scheduler
import org.quartz.Trigger
import org.springframework.scheduling.quartz.SchedulerFactoryBean

/**
 * A manual run has to be a Quartz job, not a thread.
 *
 * The reason is not tidiness: `waitForJobsToCompleteOnShutdown` waits for Quartz worker threads, so a run
 * started on a bare thread is invisible to it and to the container's 400s grace — restarting the scheduler
 * mid-manual-run cut that execution into an `agent_task_log` row at 3 and a lock row at 0 for the sweeper
 * to reap up to 2× timeout later. Same shape as the cron path, same protection.
 */
class SchedulerManualTriggerTest {

    private val quartz: Scheduler = mock(Scheduler::class.java)
    private val factory: SchedulerFactoryBean = mock(SchedulerFactoryBean::class.java).apply {
        doReturn(quartz).`when`(this).scheduler
    }
    private val taskMapper: AgentTaskMapper = mock(AgentTaskMapper::class.java)
    private val logMapper: AgentTaskLogMapper = mock(AgentTaskLogMapper::class.java)
    private val routerClient: RouterClient = mock(RouterClient::class.java)
    private val guard: AgentTaskExecutionGuard = mock(AgentTaskExecutionGuard::class.java)
    private val status = SchedulerStatus(schedulerEnabled = true)
    private val metrics: SchedulerMetrics = mock(SchedulerMetrics::class.java)
    private val inventory: QuartzJobInventory = mock(QuartzJobInventory::class.java)
    private val registrar = TaskQuartzRegistrar(factory)

    private val service = SchedulerServiceImpl(
        schedulerFactory = factory,
        agentTaskMapper = taskMapper,
        agentTaskLogMapper = logMapper,
        routerClient = routerClient,
        executionGuard = guard,
        status = status,
        metrics = metrics,
        jobInventory = inventory,
        registrar = registrar,
        reconciler = mock(TaskScheduleReconciler::class.java),
        executionTimeoutSeconds = 300,
        schedulerEnabled = true,
        reconcileIntervalSeconds = 60,
    )

    @Test
    fun `a manual run is scheduled as a one-shot job carrying only its task id`() {
        doReturn(task(7L)).`when`(taskMapper).selectAnyById(7L)
        doReturn(emptyList<Any>()).`when`(logMapper).selectRunningByTaskId(7L)

        assertTrue(service.runTaskOnce(7L))

        val jobCaptor = argumentCaptor<JobDetail>()
        val triggerCaptor = argumentCaptor<Trigger>()
        verify(quartz).scheduleJob(jobCaptor.capture(), triggerCaptor.capture())
        val detail = jobCaptor.firstValue
        assertEquals(TaskQuartzRegistrar.GROUP_ONCE, detail.key.group)
        // durable would leave a permanent AgentTaskGroup_ONCE row: Quartz deletes a job only with its completed trigger.
        assertFalse(detail.isDurable)
        assertEquals("7", detail.jobDataMap.getString(TaskQuartzRegistrar.KEY_TASK_ID))
        assertEquals(
            1,
            detail.jobDataMap.size,
            "the data map must hold nothing but the id: useProperties forbids any other type",
        )
        assertEquals(
            "AgentTask_7",
            detail.key.name.substringBeforeLast("_ONCE"),
            "the one-shot keeps the task's identity in its job name so a fire can find it",
        )
        // The trigger names its job on the way in rather than leaning on Quartz's back-fill, which is what
        // keeps `storeJobAndTrigger` writing one consistent pair into the shared store.
        assertEquals(detail.key, triggerCaptor.firstValue.jobKey, "the one-shot trigger belongs to its own job")
        assertEquals(TaskQuartzRegistrar.GROUP_ONCE, triggerCaptor.firstValue.key.group)
    }

    @Test
    fun `a manual run on a task that forbids overlap while one is live is refused`() {
        doReturn(task(7L, concurrent = 0)).`when`(taskMapper).selectAnyById(7L)
        doReturn(listOf(anyLogRow())).`when`(logMapper).selectRunningByTaskId(7L)

        assertEquals(false, service.runTaskOnce(7L))
        verify(quartz, never()).scheduleJob(any(), any())
    }

    /**
     * The merge itself, at the seam admin and the CLI actually use.
     *
     * `/trigger` used to answer from a different implementation than `/run-once`: it took the cluster lock
     * and started a thread, so the 200 preceded any store write and the run it launched was unwatched. Both
     * endpoints land on the one-shot now, and the lock is the job's to take when it fires (`AbstractAgentTaskJob`),
     * on whichever node claims the trigger — taking it at delivery time here would hold a lock for a run
     * another node may end up firing.
     */
    @Test
    fun `the trigger endpoint delivers a persisted one-shot and touches neither the lock nor the router`() {
        doReturn(task(7L, concurrent = 1)).`when`(taskMapper).selectAnyById(7L)

        val result = SchedulerController(service, status).trigger(7L)

        assertEquals(200, result.code)
        val detailCaptor = argumentCaptor<JobDetail>()
        val triggerCaptor = argumentCaptor<Trigger>()
        verify(quartz).scheduleJob(detailCaptor.capture(), triggerCaptor.capture())
        assertEquals(TaskQuartzRegistrar.GROUP_ONCE, triggerCaptor.firstValue.key.group)
        verifyNoInteractions(guard)
        verifyNoInteractions(routerClient)
    }

    private fun task(id: Long, concurrent: Int = 0) = AgentTask().apply {
        this.id = id
        this.cronExpression = "0 0 4 * * ?"
        this.concurrent = concurrent
        this.prompt = "p"
        this.name = "task-$id"
    }

    private fun anyLogRow() = AgentTaskLog().apply { this.taskId = 7L }
}

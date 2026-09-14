package com.agnetix.harnax.scheduler.service

import com.agnetix.harnax.entity.AgentTask
import com.agnetix.harnax.mapper.AgentTaskMapper
import com.agnetix.harnax.scheduler.health.QuartzJobInventory
import com.agnetix.harnax.scheduler.health.RegisteredJob
import com.agnetix.harnax.scheduler.health.SchedulerStatus
import com.agnetix.harnax.scheduler.job.AgentTaskNonConcurrentJob
import com.agnetix.harnax.scheduler.job.TaskQuartzRegistrar
import com.agnetix.harnax.scheduler.metrics.SchedulerMetrics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/**
 * The whole point of a shared store is that a restart must not unregister what another node is running.
 * These cases are that promise as a table: register what is missing, drop what is gone, reschedule what
 * changed, and — the one an "easier" implementation gets wrong — leave a matching job completely alone,
 * because touching it resets its prev/next fire history.
 */
class TaskScheduleReconcilerTest {

    private val mapper: AgentTaskMapper = mock()
    private val registrar: TaskQuartzRegistrar = mock()
    private val inventory: QuartzJobInventory = mock()
    private val status = SchedulerStatus(schedulerEnabled = true)
    private val metrics: SchedulerMetrics = mock()
    private val reconciler = TaskScheduleReconciler(mapper, registrar, inventory, status, metrics)

    private val nonConcurrent = AgentTaskNonConcurrentJob::class.java.name

    private fun task(id: Long, cron: String = CRON, concurrent: Int = 0) = AgentTask().apply {
        this.id = id
        this.cronExpression = cron
        this.concurrent = concurrent
    }

    @Test
    fun `a matching job is left completely alone`() {
        stubTasks(task(1L))
        doReturn(mapOf(1L to RegisteredJob(CRON, nonConcurrent))).`when`(inventory).agentTaskJobs()

        val report = reconciler.reconcile()

        assertEquals(1, report.unchanged)
        assertEquals(0, report.added + report.removed + report.updated)
        verifyNoInteractionsOnRegistrar()
        assertTrue(report.converged)
        // a round that changed nothing must not publish drift samples
        verify(metrics, never()).recordReconcileDrift(any(), anyInt())
    }

    @Test
    fun `missing jobs are added extra ones removed and changed crons rescheduled`() {
        stubTasks(task(1L, "0 0 1 * * ?"), task(2L))
        doReturn(
            mapOf(
                1L to RegisteredJob("0 0 2 * * ?", nonConcurrent),
                9L to RegisteredJob("0 0 1 * * ?", nonConcurrent),
            ),
        ).`when`(inventory).agentTaskJobs()

        val report = reconciler.reconcile()

        assertEquals(1, report.added)
        assertEquals(1, report.removed)
        assertEquals(1, report.updated)
        verify(registrar).register(argThat { id == 2L })
        verify(registrar).register(argThat { id == 1L })
        verify(registrar).unregister(9L)
        verify(metrics).recordReconcileDrift("add", 1)
        verify(metrics).recordReconcileDrift("remove", 1)
        verify(metrics).recordReconcileDrift("update", 1)
        assertTrue(report.converged)
    }

    @Test
    fun `a task whose job class no longer matches its concurrent flag counts as a change`() {
        stubTasks(task(3L, concurrent = 1))
        doReturn(mapOf(3L to RegisteredJob(CRON, nonConcurrent))).`when`(inventory).agentTaskJobs()

        val report = reconciler.reconcile()

        assertEquals(1, report.updated)
        verify(registrar).register(argThat { id == 3L && concurrent == 1 })
    }

    @Test
    fun `one failing task does not stop the others and is reported as drift`() {
        stubTasks(task(1L), task(2L))
        doReturn(emptyMap<Long, RegisteredJob>()).`when`(inventory).agentTaskJobs()
        doThrow(RuntimeException("bad cron")).doNothing().`when`(registrar).register(any())

        val report = reconciler.reconcile()

        assertEquals(2, report.added)
        assertEquals(listOf(1L), report.failedIds)
        assertFalse(report.converged)
        assertEquals("1 of 2 active tasks could not be registered: ids=[1]", status.lastReconcileError)
    }

    /**
     * The property the old full-rebuild path had for free and the diff has to keep: a write that failed
     * left nothing in the store, so the *next* round's diff finds the task missing again and retries it.
     * One unusable cron therefore costs one round, not a permanent hole in the schedule — nothing has to
     * notice the failure for the 60-second sweep to take another pass at it.
     */
    @Test
    fun `a task that failed one round is retried by the next`() {
        stubTasks(task(1L), task(2L))
        doReturn(emptyMap<Long, RegisteredJob>()).`when`(inventory).agentTaskJobs()
        doThrow(RuntimeException("bad cron")).doNothing().`when`(registrar).register(any())

        assertFalse(reconciler.reconcile().converged)

        val second = reconciler.reconcile()
        assertTrue(second.converged, "the next round left ${second.failedIds} behind")
        assertEquals(2, second.added)
        assertEquals(0, second.updated)
    }

    private fun stubTasks(vararg tasks: AgentTask) {
        doReturn(tasks.toList()).`when`(mapper).selectRunningTasks()
    }

    private fun verifyNoInteractionsOnRegistrar() {
        verify(registrar, never()).register(any())
        verify(registrar, never()).unregister(any())
    }

    companion object {
        private const val CRON = "0 0 * * * ?"
    }
}

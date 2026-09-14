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
import org.mockito.kotlin.whenever

/**
 * The whole point of a shared store is that a restart must not unregister what another node is running.
 * These cases are that promise as a table: register what is missing, drop what is gone, reschedule what
 * changed, and — the one an "easier" implementation gets wrong — leave a matching job completely alone,
 * because touching it resets its prev/next fire history.
 *
 * The last two of those four need real care rather than a bigger diff: what the store hands back is
 * Quartz's *normalized* cron, not what the table holds, and the two reads that make the diff have to happen
 * in one particular order. Both are pinned below with data, because a mock that agrees with the code under
 * test proves nothing about either.
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

    /**
     * The store does not hand back what was typed into it: `CronExpression`'s constructor uppercases its
     * argument, the trigger answers with that string and a JDBC store persists and re-reads exactly it, so
     * `0 0 9 ? * mon-fri` comes back `0 0 9 ? * MON-FRI` (pinned on a real trigger in `QuartzJobInventoryTest`).
     * The webui's own presets ship the lettered form
     * (`harnax-webui/src/pages/agent-task/components/TaskForm.tsx`) and admin stores the field verbatim, so a
     * case-sensitive compare makes one such task a permanent `updated++` — a `scheduleJob(replace=true)` every
     * 60 seconds, which deletes the trigger row and recomputes NEXT_FIRE_TIME. That is the state this class
     * exists to make impossible, and it feeds the drift metric forever.
     */
    @Test
    fun `a cron whose stored form differs only in letter case is the same schedule`() {
        stubTasks(task(1L, cron = "0 0 9 ? * mon-fri"))
        doReturn(mapOf(1L to RegisteredJob("0 0 9 ? * MON-FRI", nonConcurrent))).`when`(inventory).agentTaskJobs()

        val report = reconciler.reconcile()

        assertEquals(1, report.unchanged)
        assertEquals(0, report.updated, "a case difference is Quartz's normalization, not a cron change")
        verifyNoInteractionsOnRegistrar()
        assertTrue(report.converged)
    }

    /**
     * The two reads have to happen in this order, and admin's one `/reload` forward after every committed CRUD
     * is what makes the interleaving routine rather than theoretical: a create+register committing *between*
     * them has to land in the table but not in the store snapshot, so the round re-registers it (a benign
     * replace). Read the table first and the same commit lands only in the snapshot — the job then looks like an
     * extra key, and this round deletes a schedule the cluster just asked for along with any cron boundary
     * inside the window.
     */
    @Test
    fun `a task created between the two reads is re-registered rather than deleted`() {
        val table = mutableMapOf<Long, AgentTask>()
        val store = mutableMapOf<Long, RegisteredJob>()
        var reads = 0

        // The CRUD commits after whichever read ran first: that is the only difference between the two
        // orders, so it is also the only way to pin one of them down.
        fun crudCommits() {
            if (++reads == 1) {
                table[9L] = task(9L, cron = "0 0 9 ? * MON-FRI")
                store[9L] = RegisteredJob("0 0 9 ? * MON-FRI", nonConcurrent)
            }
        }
        whenever(inventory.agentTaskJobs()).thenAnswer { store.toMap().also { crudCommits() } }
        whenever(mapper.selectRunningTasks()).thenAnswer { table.values.toList().also { crudCommits() } }

        val report = reconciler.reconcile()

        assertEquals(1, report.added, "a schedule the table wants must never read as an extra key")
        assertEquals(0, report.removed)
        assertEquals(0, report.unchanged)
        verify(registrar).register(argThat { id == 9L })
        verify(registrar, never()).unregister(any())
        assertTrue(report.converged)
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
     * A delete that would not land is not a registration failure, and the text used to say it was: an
     * operator reading "1 of 2 active tasks could not be registered: ids=[9]" goes looking for an active task
     * 9 that is in fact a job trying and failing to leave. The other half of why this sentence is now
     * direction-specific is the empty table, where the same code printed "1 of 0 active tasks".
     */
    @Test
    fun `a job the store will not give back is reported as a removal not as a failed registration`() {
        stubTasks()
        doReturn(mapOf(9L to RegisteredJob(CRON, nonConcurrent))).`when`(inventory).agentTaskJobs()
        doThrow(RuntimeException("row locked")).`when`(registrar).unregister(9L)

        val report = reconciler.reconcile()

        assertEquals(listOf(9L), report.failedIds)
        assertFalse(report.converged)
        assertEquals(
            "1 scheduled job the table no longer wants could not be unregistered: ids=[9]",
            status.lastReconcileError,
        )
    }

    /** Both directions in one round, because neither may be folded into the other's sentence. */
    @Test
    fun `a round that failed both ways names both`() {
        stubTasks(task(1L))
        doReturn(mapOf(9L to RegisteredJob(CRON, nonConcurrent))).`when`(inventory).agentTaskJobs()
        doThrow(RuntimeException("bad cron")).`when`(registrar).register(any())
        doThrow(RuntimeException("row locked")).`when`(registrar).unregister(9L)

        val report = reconciler.reconcile()

        assertEquals(
            "1 of 1 active tasks could not be registered: ids=[1]; " +
                "1 scheduled job the table no longer wants could not be unregistered: ids=[9]",
            status.lastReconcileError,
        )
        // What the round left scheduled is the apply side only: a job that would not be deleted is not a task
        // this node is running.
        assertEquals(0, status.lastReconcileJobCount)
        assertEquals(listOf(1L, 9L), report.failedIds)
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

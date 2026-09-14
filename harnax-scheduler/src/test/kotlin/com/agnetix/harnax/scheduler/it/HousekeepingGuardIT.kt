package com.agnetix.harnax.scheduler.it

import com.agnetix.harnax.scheduler.job.SchedulerHousekeepingJob
import com.agnetix.harnax.scheduler.service.AgentTaskExecutionGuard
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import java.time.LocalDateTime

/**
 * IT-5: the guard table had no removal path at all before the housekeeping sweep existed, and it grows with
 * every trigger. Two sweeps share that table and they must not do each other's work: the retention one deletes
 * by age alone, the leaked-lock one by age *and* by status, and only the second may free a lock.
 *
 * The four rows are the cross product of those two predicates, and the table is read after each call, so every
 * row says which sweep was allowed to take it:
 *
 * - 201 is terminal and four times past the retention: the retention call takes it, and the assertion right
 *   after is what attributes the deletion — the leaked sweep had not run yet;
 * - 202 is terminal and an hour old, i.e. inside the retention: it must survive both calls, and surviving the
 *   second one is the proof that the leaked sweep filters on status, since an hour is long past its deadline;
 * - 203 is a lock older than twice the execution timeout but nowhere near the retention: it has to survive the
 *   retention call (age alone must not release a lock) and go with the leaked one, which is the call that
 *   stops a (task_id, trigger_time) being blocked forever by a holder that never came back;
 * - 204 is a lock a live execution still holds: too young for either deadline, and it has to still be there at
 *   the end, which is what keeps the leaked sweep from freeing a lock out from under a running task.
 *
 * The leaked-lock age is derived from `scheduler.timeout-seconds` rather than hard-coded, because that key is
 * exactly what `cleanupLeakedLocks()` judges against (2x of it): a literal age would leave one of the two
 * "must still be there" rows on the wrong side of the deadline as soon as the IT profile moved the timeout.
 */
class HousekeepingGuardIT : BaseSchedulerIT() {

    @Autowired
    private lateinit var guard: AgentTaskExecutionGuard

    /** The same key `AgentTaskExecutionGuard` is injected with; its 2x is this test's stale-lock deadline. */
    @Value("\${scheduler.timeout-seconds:300}")
    private var executionTimeoutSeconds: Int = 0

    @AfterEach
    fun removeSeededRows() {
        jdbc.update("DELETE FROM agent_task_execution WHERE task_id IN (${SEEDED_TASK_IDS.joinToString(",")})")
    }

    @Test
    fun `retention and leaked-lock sweeps only touch what they may`() {
        val now = LocalDateTime.now()
        insert(201L, now.minusDays(SchedulerHousekeepingJob.GUARD_RETENTION_DAYS * 4L), status = 1)
        insert(202L, now.minusHours(1), status = 1)
        insert(203L, now.minusSeconds(executionTimeoutSeconds * 2L + LEAKED_LOCK_MARGIN_SECONDS), status = 0)
        insert(204L, now, status = 0)

        guard.cleanupOldExecutions(SchedulerHousekeepingJob.GUARD_RETENTION_DAYS)

        assertEquals(0, countOf(201L), "a terminal row past the retention is the retention sweep's to take")
        assertEquals(1, countOf(202L), "a terminal row inside the retention belongs to nobody")
        assertEquals(1, countOf(203L), "age alone must not release a lock: that is the other sweep's job")
        assertEquals(1, countOf(204L), "a fresh lock is a live execution")

        assertEquals(
            1,
            guard.cleanupLeakedLocks(),
            "exactly one row was a lock whose holder is gone, and the sweep has to say so",
        )
        assertEquals(0, countOf(203L), "a lock older than twice the timeout stops blocking its trigger here")
        assertEquals(1, countOf(204L), "a lock inside twice the timeout still has an owner to wait for")
        assertEquals(1, countOf(202L), "the leaked sweep filters on status; a finished run is not a lock")
    }

    /**
     * Row counts are keyed by `task_id`, which is the column the test writes: `id` is the table's
     * auto-increment surrogate, so nothing here chooses it and an assertion against it would be about the
     * insert order rather than about the sweep.
     */
    private fun countOf(taskId: Long): Int = requireNotNull(
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM agent_task_execution WHERE task_id = ?",
            Int::class.java,
            taskId,
        ),
    ) { "no count for task $taskId" }

    /**
     * `trigger_time` is the identity the lock is keyed by, and the two sweeps judge only `create_time` — so
     * this test's rows carry the same instant in both columns and the age under test is unambiguous.
     */
    private fun insert(
        taskId: Long,
        at: LocalDateTime,
        status: Int,
    ) {
        jdbc.update(
            "INSERT INTO agent_task_execution (task_id, trigger_time, instance_id, status, create_time) " +
                "VALUES (?, ?, 'it-node', ?, ?)",
            taskId,
            at,
            status,
            at,
        )
    }

    private companion object {
        /** Moves 203 clear of the 2x deadline without moving it near the 7-day retention. */
        const val LEAKED_LOCK_MARGIN_SECONDS = 60L

        val SEEDED_TASK_IDS = listOf(201L, 202L, 203L, 204L)
    }
}

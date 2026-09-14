package com.agnetix.harnax.scheduler.it

import com.agnetix.harnax.scheduler.job.TaskQuartzRegistrar
import com.agnetix.harnax.scheduler.service.TaskScheduleReconciler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.quartz.CronTrigger
import org.quartz.TriggerKey
import org.quartz.impl.matchers.GroupMatcher
import org.springframework.beans.factory.annotation.Autowired

/**
 * IT-2: hand the store a drift the CRUD path could not have produced, and prove the sweep converges it —
 * without clearing the schedule of the job that never changed. That second part is the one that matters: it
 * is what "reconcile" means here as opposed to "delete everything and re-register", and an implementation
 * that re-registers matching jobs passes every other assertion in this file.
 *
 * The three drifts are three different shapes, and each lands in exactly one bucket of the report — the
 * counts are only meaningful as a partition, so [TaskScheduleReconciler.reconcile] is asserted as a whole:
 * 101 has its cron rewritten inside `QRTZ_CRON_TRIGGERS` (no code path of the application writes the store
 * directly, and this is the case a lost CRUD notification leaves behind), 102's row disappears from the table
 * while its job stays, and 103 is left alone.
 *
 * 103's cron is also lowercase, the way the webui's own presets are stored. Quartz uppercases a cron
 * expression, the store hands that back, and `matches()` compares the two case-insensitively; a compare that
 * honoured case would call it a change and report `updated = 2`. That is the difference between a round that
 * writes one job and a round that rewrites the whole group on every sweep.
 */
class ReconcileConvergenceIT : BaseSchedulerIT() {

    @Autowired
    private lateinit var reconciler: TaskScheduleReconciler

    @Autowired
    private lateinit var registrar: TaskQuartzRegistrar

    @AfterEach
    fun removeSeededTasks() {
        // The context is cached and the store is shared across the IT classes of this JVM, so a task left
        // here would be a cluster schedule for the rest of the run — and the next round of anybody else's
        // test would find an extra key.
        SEADED_IDS.forEach { registrar.unregister(it) }
        jdbc.update("DELETE FROM agent_task WHERE id IN (${SEADED_IDS.joinToString(",")})")
    }

    @Test
    fun `drift converges and untouched jobs keep their schedule`() {
        insertTask(101L, CRON_101)
        insertTask(102L, CRON_102)
        insertTask(103L, CRON_103_IN_TABLE)

        // The first round is asserted through the store rather than through its report: the startup load runs
        // the same call on the service's own thread, and a round that got there first would leave this one
        // with `added = 0`. What may not vary is the end state it registers.
        reconciler.reconcile()
        assertEquals(
            SEADED_IDS.toSet(),
            scheduler
                .getJobKeys(GroupMatcher.jobGroupEquals(TaskQuartzRegistrar.GROUP_AGENT_TASK))
                .mapNotNull { TaskQuartzRegistrar.taskIdOf(it) }
                .toSet(),
            "the round has to register every running task of the table",
        )

        val driftedKey = registrar.triggerKeyOf(101L)
        val untouchedKey = registrar.triggerKeyOf(103L)
        val untouchedNextFire = requireNotNull((scheduler.getTrigger(untouchedKey) as CronTrigger).nextFireTime).time
        val untouchedStartTime = storedStartTimeOf(untouchedKey)
        val driftedStartTime = storedStartTimeOf(driftedKey)

        jdbc.update(
            "UPDATE QRTZ_CRON_TRIGGERS SET CRON_EXPRESSION = ? WHERE SCHED_NAME = ? AND TRIGGER_NAME = ? AND TRIGGER_GROUP = ?",
            DRIFTED_CRON_101,
            schedulerName,
            driftedKey.name,
            driftedKey.group,
        )
        jdbc.update("DELETE FROM agent_task WHERE id = 102")
        val beforeRound = registeredTaskIds()
        require(101L in beforeRound && 103L in beforeRound) { "writing the drift must not unregister anything: $beforeRound" }

        // A re-registration stamps START_TIME with the build moment rounded up to the next second, so two
        // rounds inside the same second would leave it unchanged even for a rebuild — the comparison below
        // has to straddle a second boundary to say anything at all.
        Thread.sleep(1_500)

        val report = reconciler.reconcile()

        assertEquals(emptyList<Long>(), report.failedIds, "nothing may have failed to apply")
        assertEquals(0, report.added, "no task the store had never seen")
        assertEquals(1, report.updated, "exactly the one job whose cron the store drifted on")
        assertEquals(1, report.removed, "exactly the one task the table no longer wants")
        assertEquals(1, report.unchanged, "exactly the one job that was already right, lowercase cron and all")

        assertNull(scheduler.getJobDetail(registrar.jobKeyOf(102L)), "a task deleted from the table must lose its job")
        assertEquals(
            CRON_101,
            (scheduler.getTrigger(driftedKey) as CronTrigger).cronExpression,
            "a cron edited only in the store must be pulled back to the table's value",
        )
        assertEquals(
            "0 0 9 ? * SUN",
            (scheduler.getTrigger(untouchedKey) as CronTrigger).cronExpression,
            "the premise of the case-insensitive compare: the store hands Quartz's normalized form back",
        )

        // The proof that this is a diff and not a re-register, twice over: the schedule the untouched job
        // already had is still the one in the trigger row, and the row itself was never rewritten. An
        // implementation that rebuilds every job passes the four count assertions and the cron assertions
        // above (its rewrite is *also* the table's value) and fails here.
        val untouchedAfter = scheduler.getTrigger(untouchedKey) as CronTrigger
        assertEquals(
            untouchedNextFire,
            requireNotNull(untouchedAfter.nextFireTime).time,
            "the untouched job kept the NEXT_FIRE_TIME it already had",
        )
        assertEquals(
            untouchedStartTime,
            storedStartTimeOf(untouchedKey),
            "the untouched job's trigger row was never rewritten",
        )
        // …and the control that keeps the two assertions above from passing by doing nothing at all: the job
        // that *was* rewritten moved its START_TIME, in the same store, in the same round.
        assertTrue(
            storedStartTimeOf(driftedKey) > driftedStartTime,
            "101 was re-registered, so the START_TIME comparison is not vacuous",
        )
    }

    private fun registeredTaskIds(): Set<Long> = scheduler
        .getJobKeys(GroupMatcher.jobGroupEquals(TaskQuartzRegistrar.GROUP_AGENT_TASK))
        .mapNotNull { TaskQuartzRegistrar.taskIdOf(it) }
        .toSet()

    /**
     * The trigger's own start stamp, read out of the store rather than off a Trigger object — which is the
     * point: a rebuild overwrites the row, and only the row says so.
     */
    private fun storedStartTimeOf(key: TriggerKey): Long = requireNotNull(
        jdbc.queryForObject(
            "SELECT START_TIME FROM QRTZ_TRIGGERS WHERE SCHED_NAME = ? AND TRIGGER_NAME = ? AND TRIGGER_GROUP = ?",
            Long::class.java,
            schedulerName,
            key.name,
            key.group,
        ),
    ) { "no trigger row for $key" }

    private fun insertTask(
        id: Long,
        cron: String,
    ) {
        jdbc.update(
            "INSERT INTO agent_task (id, tenant_id, name, agent_id, prompt, cron_expression, " +
                "task_status, concurrent, timeout_seconds, active, creator) " +
                "VALUES (?, 1, ?, 1, 'p', ?, 1, 0, 300, 1, 'admin')",
            id,
            "it-$id",
            cron,
        )
    }

    private companion object {
        /**
         * Daily and weekly at off hours, on purpose: nothing is mocked in these tests, so a seeded task that
         * fired would call the router and write execution rows. The reconciler's diff does not need a fire.
         */
        const val CRON_101 = "0 0 4 * * ?"

        const val CRON_102 = "0 30 4 * * ?"

        const val DRIFTED_CRON_101 = "0 0 5 * * ?"

        /**
         * Lowercase, because that is what the webui writes and what `agent_task` keeps: `CronExpression`
         * uppercases its argument, so the store hands back `0 0 9 ? * SUN` and only the ignoreCase compare in
         * `TaskScheduleReconciler.matches()` keeps this job out of the `updated` bucket.
         */
        const val CRON_103_IN_TABLE = "0 0 9 ? * sun"

        val SEADED_IDS = listOf(101L, 102L, 103L)
    }
}

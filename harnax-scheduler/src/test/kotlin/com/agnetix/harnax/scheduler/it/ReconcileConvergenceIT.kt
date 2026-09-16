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
 *
 * Under all of it sits one property the counts are worthless without: no seeded task may *fire* while this
 * test runs. Nothing here is mocked, so a fire would call the router and leave a lock row behind — the same
 * table whose leaked locks IT-5 counts as exactly one. It is bought twice: every cron pins a weekday so the
 * nearest reachable fire is days away, and `assertNoSeededTaskFired` checks the two tables that only a fire
 * writes, once after the setup and once after the measured round.
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

        // The row count of each write is asserted, because both `WHERE` clauses name a row this test just
        // created and neither can legitimately match zero rows or two: a drift that reached nothing leaves the
        // round with nothing to converge, and without this the symptom is `updated = 0` — a verdict on the
        // reconciler that belongs to the fixture.
        assertEquals(
            1,
            jdbc.update(
                "UPDATE QRTZ_CRON_TRIGGERS SET CRON_EXPRESSION = ? WHERE SCHED_NAME = ? AND TRIGGER_NAME = ? AND TRIGGER_GROUP = ?",
                DRIFTED_CRON_101,
                schedulerName,
                driftedKey.name,
                driftedKey.group,
            ),
            "the drift has to land on 101's cron row and on nobody else's",
        )
        assertEquals(
            1,
            jdbc.update("DELETE FROM agent_task WHERE id = 102"),
            "102 has to lose its own table row and only its own",
        )
        assertNoSeededTaskFired("the setup")
        val beforeRound = registeredTaskIds()
        require(beforeRound == SEADED_IDS.toSet()) {
            "writing the drift must not unregister anything: $beforeRound. A 102 missing here means it fired " +
                "after this test deleted its table row, and AbstractAgentTaskJob.taskToRun answers that by " +
                "deleting the orphaned job"
        }

        // A re-registration stamps START_TIME with the build moment truncated to the whole second:
        // `CronTriggerImpl.setStartTime` zeroes the milliseconds (quartz-2.5.2-sources `:162-181`, the
        // `cl.set(Calendar.MILLISECOND, 0)` at :179), and the store persists that Date as epoch millis. So two
        // rounds inside the same second would leave it unchanged even for a rebuild — the comparison below has
        // to straddle a second boundary to say anything at all.
        Thread.sleep(1_500)

        val report = reconciler.reconcile()

        // Before the counts, on purpose. Every count below is only about the round if nothing else executed a
        // task in the window, and this package mocks nothing — see the crons' KDoc.
        assertNoSeededTaskFired("the measured round")

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
            CRON_103_NORMALIZED,
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

    /**
     * The check for the one assumption [CRON_101] and its neighbours carry: no seeded task fires while this
     * test runs.
     *
     * Both tables are the fire's own paper trail — `AbstractAgentTaskJob.run()` takes the execution lock before
     * it reaches the router and `SchedulerServiceImpl` logs the run — so a zero in both is evidence the
     * schedules stayed unreachable, and a non-zero is a fire naming itself. Without it, the same coincidence
     * arrives as `updated = 0` or as IT-5's leaked-lock count off by one, and both read as a bug in the code
     * under test. Called after the setup and after the measured round, which together bracket every window a
     * fire could have landed in.
     */
    private fun assertNoSeededTaskFired(
        stage: String,
    ) {
        val ids = SEADED_IDS.joinToString(",")
        val locks = requireNotNull(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_task_execution WHERE task_id IN ($ids)",
                Int::class.java,
            ),
        ) { "no lock count for the seeded tasks" }
        val logs = requireNotNull(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_task_log WHERE task_id IN ($ids)",
                Int::class.java,
            ),
        ) { "no log count for the seeded tasks" }
        assertTrue(
            locks == 0 && logs == 0,
            "A seeded task fired before or during $stage: $locks execution lock(s) and $logs log row(s) for ids " +
                "[$ids]. This package mocks nothing, so that fire also called the router, and every count " +
                "asserted after this line measures it rather than the reconcile round",
        )
    }

    private companion object {
        /**
         * Every cron here pins a weekday *and* a minute of the hour, and the three live schedules sit on three
         * different days of the week. That is the guard against a fire, and a fire is the one thing this test
         * cannot survive: nothing is mocked here, so `AbstractAgentTaskJob.run()` would call the router at its
         * default URL and write `agent_task_execution` + `agent_task_log` rows — rows IT-5 then counts.
         * [assertNoSeededTaskFired] reports such a row as a fire instead of as a wrong count.
         *
         * A bare nightly time is not enough, and the `0 0 4 * * ?` this file used to seed is not one: 04:00 is
         * an ordinary CI window, and one fire inside the measurement window is all it takes. With the weekday
         * pinned, any one of these can only fire during one minute of one week. The drift value is not a live
         * schedule at all — writing `CRON_EXPRESSION` in place leaves `NEXT_FIRE_TIME` alone, which is exactly
         * the drift this test converges — so it only has to be a different expression, not another day.
         *
         * "Days away" is the strongest form Quartz accepts: `scheduleJob` refuses a trigger whose first fire
         * time cannot be computed (quartz-2.5.2-sources `QuartzScheduler.java:835-839`), so a schedule that
         * never fires is not an option here.
         */
        const val CRON_101 = "0 7 3 ? * WED"

        const val CRON_102 = "0 40 22 ? * FRI"

        const val DRIFTED_CRON_101 = "0 7 4 ? * WED"

        /**
         * Lowercase, because that is what the webui writes and what `agent_task` keeps: `CronExpression`
         * uppercases its argument, so the store hands back `0 20 5 ? * SAT` and only the ignoreCase compare in
         * `TaskScheduleReconciler.matches()` keeps this job out of the `updated` bucket.
         */
        const val CRON_103_IN_TABLE = "0 20 5 ? * sat"

        /** What the store gives back for [CRON_103_IN_TABLE]: the same expression, uppercased. */
        const val CRON_103_NORMALIZED = "0 20 5 ? * SAT"

        val SEADED_IDS = listOf(101L, 102L, 103L)
    }
}

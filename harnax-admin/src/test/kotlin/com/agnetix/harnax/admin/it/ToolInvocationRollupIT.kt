package com.agnetix.harnax.admin.it

import com.agnetix.harnax.admin.service.ToolInvocationRollupService
import com.agnetix.harnax.mapper.ToolInvocationLogMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The hourly rollup against a real MySQL: which hours get recomputed, and what the retention sweep may delete.
 *
 * Every case drives `rollUp()` directly rather than waiting for a cron. The whole contract is about which
 * rows survive one run, and a timer is not something to assert against; the scheduled entry point is covered
 * separately for the one thing only it owns, which is the kill switch.
 *
 * Both tables are emptied before every case: the MySQL container is shared by every IT in this JVM, and the
 * run counts here are asserted absolutely, so a row another class left behind would read as this class's
 * failure. Nothing else writes these two tables.
 */
class ToolInvocationRollupIT : BaseAdminIT() {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var rollup: ToolInvocationRollupService

    @Autowired
    private lateinit var logMapper: ToolInvocationLogMapper

    private fun call(
        at: LocalDateTime,
        tenantId: Long?,
        outcome: String,
        durationMs: Long,
        toolName: String = "send_email",
    ) {
        jdbc.update(
            """
                INSERT INTO tool_invocation_log
                (tenant_id, agent_id, session_id, user_id, kind, tool_name, outcome, duration_ms, start_time, end_time, ts)
                VALUES (?, ?, ?, ?, 'builtin', ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            tenantId,
            1L,
            "rollup-session",
            1L,
            toolName,
            outcome,
            durationMs,
            at.minusSeconds(durationMs / 1000L + 1L),
            at,
            at,
        )
    }

    private fun statsFor(
        hour: LocalDateTime,
        tenantId: Long,
        toolName: String = "send_email",
    ): Map<String, Any?>? = jdbc.queryForList(
        "SELECT calls, successes, errors, le_100ms, le_500ms, gt_30s, sum_duration_ms FROM tool_invocation_stats" +
            " WHERE stat_hour = ? AND tenant_id = ? AND kind = 'builtin' AND subject_id = 0 AND tool_name = ?",
        hour.format(DB_TS),
        tenantId,
        toolName,
    ).firstOrNull()

    /** The hour every run revisits: `rollUp` folds it whether or not the detail table calls it pending. */
    private fun currentHour(): LocalDateTime = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)

    private fun statsRows(): Int = jdbc.queryForObject("SELECT COUNT(*) FROM tool_invocation_stats", Int::class.java)!!

    private fun detailRows(): Int = jdbc.queryForObject("SELECT COUNT(*) FROM tool_invocation_log", Int::class.java)!!

    private fun detailCount(before: String): Int = jdbc.queryForObject("SELECT COUNT(*) FROM tool_invocation_log WHERE ts < ?", Int::class.java, before)!!

    /** The same cutoff the service computes, a few milliseconds apart from its own run. */
    private fun cutoff(): String = LocalDateTime.now().minusDays(RETENTION_DAYS).format(DB_TS)

    @BeforeEach
    fun clearRows() {
        jdbc.update("DELETE FROM tool_invocation_stats")
        jdbc.update("DELETE FROM tool_invocation_log")
    }

    @Test
    @DisplayName("an hour that was missed entirely is rolled on the next run")
    fun missedHourIsCaughtUp() {
        val missed = currentHour().minusHours(3)
        call(missed, TENANT_ID, "SUCCESS", 120L)
        call(missed.plusMinutes(5), TENANT_ID, "ERROR", 900L)

        // The missed hour, plus the two hours every run revisits.
        assertEquals(3, rollup.rollUp())

        val stats = requireNotNull(statsFor(missed, TENANT_ID))
        assertEquals(2L, (stats["calls"] as Number).toLong())
        assertEquals(1L, (stats["successes"] as Number).toLong())
        assertEquals(1L, (stats["errors"] as Number).toLong())
        // Half-open buckets: 120 ms lands in (100, 500], not in <=100ms.
        assertEquals(0L, (stats["le_100ms"] as Number).toLong())
        assertEquals(1L, (stats["le_500ms"] as Number).toLong())
    }

    @Test
    @DisplayName("the running hour is re-rolled so the page never trails it")
    fun currentHourIsRerolled() {
        // Both instants fall inside the running hour, and the second is not yet reached by the clock:
        // `upsertHour` bounds the hour by an instant range that closes after the hour ends, so a row written
        // into it is folded on the run that follows rather than on the first run after the hour closes.
        val hour = currentHour()
        call(hour.plusMinutes(1), TENANT_ID, "SUCCESS", 50L)
        rollup.rollUp()
        val afterFirst = requireNotNull(statsFor(hour, TENANT_ID))["calls"]

        call(hour.plusMinutes(2), TENANT_ID, "SUCCESS", 60L)
        rollup.rollUp()
        val afterSecond = requireNotNull(statsFor(hour, TENANT_ID))["calls"]

        assertEquals(1L, (afterFirst as Number).toLong())
        assertEquals(2L, (afterSecond as Number).toLong())
    }

    @Test
    @DisplayName("a row that lands after an hour was folded still gets folded")
    fun lateRowOfTheClosingHourIsFolded() {
        // The pending set stops naming an hour once that hour has an aggregate row, so only the forced
        // revisit of the hour before the running one can pick up detail arriving between an hour's last fold
        // and its close. Both runs here are assumed to fall inside one wall-clock hour, which is exactly the
        // spacing the :05 offset gives them in production.
        val closing = currentHour().minusHours(1)
        call(closing, TENANT_ID, "SUCCESS", 100L)
        rollup.rollUp()
        assertEquals(1L, (requireNotNull(statsFor(closing, TENANT_ID))["calls"] as Number).toLong())

        call(closing.plusMinutes(55), TENANT_ID, "SUCCESS", 100L)
        rollup.rollUp()
        assertEquals(2L, (requireNotNull(statsFor(closing, TENANT_ID))["calls"] as Number).toLong())
    }

    @Test
    @DisplayName("a detail row with no tenant is not rolled up at all")
    fun tenantlessRowIsNeverRolled() {
        // The aggregate table cannot hold it (tenant_id NOT NULL); reporting it as pending would make
        // the difference set never empty and starve the hours that can be rolled.
        call(currentHour().minusHours(2), null, "SUCCESS", 100L)

        // Only the two hours every run revisits.
        assertEquals(2, rollup.rollUp())
        assertEquals(0, statsRows())
    }

    @Test
    @DisplayName("a detail row dated in the future is not folded")
    fun futureDatedRowIsNotFolded() {
        // An instance whose clock runs ahead writes detail for an hour that has not ended, and the
        // aggregate must not take a partial value for it. Without the guard this hour is folded now and
        // never re-opened, because the pending set stops naming it once a row exists. Six hours ahead keeps
        // the row future to every run this case makes, not merely future to midnight.
        val future = currentHour().plusHours(6)
        call(future, TENANT_ID, "SUCCESS", 100L)

        assertEquals(2, rollup.rollUp())
        assertNull(statsFor(future, TENANT_ID))
    }

    @Test
    @DisplayName("an expired hour is released only once its rollup exists")
    fun expiredRowWaitsForItsRollup() {
        val stale = currentHour().minusDays(RETENTION_DAYS + 1L)
        call(stale, TENANT_ID, "SUCCESS", 100L)

        // The sweep runs after the fold in the same pass, and the floor is unbounded so that hour is
        // named as pending: one run both folds and releases. What has to hold is that the release
        // cannot happen before the aggregate took the hour over, so the pair is asserted together.
        rollup.rollUp()

        assertEquals(0, detailCount(cutoff()))
        assertEquals(1L, (requireNotNull(statsFor(stale, TENANT_ID))["calls"] as Number).toLong())
    }

    @Test
    @DisplayName("the sweep refuses an hour the rollup never folded")
    fun unrolledHourIsNeverReleased() {
        // This gate is what keeps I6 alive when a fold throws mid-run, and with a working rollup it is
        // unreachable through rollUp(): an unrolled hour is by definition still pending, so the fold
        // names it first. It is reachable at the mapper seam, and that is where the gate gets proven.
        call(currentHour().minusDays(RETENTION_DAYS + 1L), TENANT_ID, "SUCCESS", 100L)

        assertEquals(0, logMapper.deleteRolledOut(cutoff()))
        assertEquals(1, detailCount(cutoff()))

        rollup.rollUp()
        assertEquals(0, detailCount(cutoff()))
    }

    @Test
    @DisplayName("expired rows with no tenant are released without a rollup")
    fun tenantlessExpiredRowsAreReleased() {
        // They never enter the aggregate table, so waiting for one would keep them forever.
        call(currentHour().minusDays(RETENTION_DAYS + 1L), null, "SUCCESS", 100L)

        rollup.rollUp()

        assertEquals(0, detailCount(cutoff()))
        assertEquals(0, statsRows())
    }

    @Test
    @DisplayName("a row just inside the window survives")
    fun rowInsideWindowSurvives() {
        // This is what makes the window's value observable rather than a number copied between the test
        // and application-it.yml: if the retention override drifted back to 90 this row is deleted, and
        // the case goes red instead of quietly asserting over an empty range.
        val recent = currentHour().minusDays(RETENTION_DAYS - 1L)
        call(recent, TENANT_ID, "SUCCESS", 100L)

        rollup.rollUp()

        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM tool_invocation_log WHERE ts = ?",
                Int::class.java,
                recent.format(DB_TS),
            ),
        )
    }

    @Test
    @DisplayName("the kill switch leaves both tables untouched")
    fun killSwitchWritesNothing() {
        // application-it.yml holds `harnax.metrics.rollup-enabled: false`, which is also what keeps a
        // cron firing at :05 out from under every other case here. rollUpHourly() is the only reader of
        // that flag, so through it nothing is folded and nothing is released.
        call(currentHour().minusHours(3), TENANT_ID, "SUCCESS", 100L)
        call(currentHour().minusDays(RETENTION_DAYS + 1L), TENANT_ID, "SUCCESS", 100L)

        rollup.rollUpHourly()

        assertEquals(0, statsRows())
        assertEquals(2, detailRows())
    }

    @Test
    @DisplayName("the hourly rows add up to the calls they were folded from")
    fun hourlyRowsSumToTheirDetail() {
        // Three calls over two hours: one in the hour that closed, two in the running one. Nothing is seeded
        // forward, because rollUp skips hours after the running one — a future row's counters would stay at
        // zero and this would become a clock check instead of an equality check.
        val current = currentHour()
        call(current.minusHours(1), TENANT_ID, "SUCCESS", 80L, toolName = "read_file")
        call(current.plusMinutes(1), TENANT_ID, "SUCCESS", 400L)
        call(current.plusMinutes(6), TENANT_ID, "ERROR", 900L, toolName = "read_file")
        rollup.rollUp()

        val perHour = jdbc.query(
            "SELECT SUM(calls) AS c, SUM(le_100ms) AS b1, SUM(le_500ms) AS b2 FROM tool_invocation_stats " +
                "WHERE tenant_id = $TENANT_ID GROUP BY stat_hour ORDER BY stat_hour",
        ) { row, _ -> "${row.getLong("c")}/${row.getLong("b1")}+${row.getLong("b2")}" }

        // The buckets travel with the hour: 80 ms is le_100ms, 400 ms is le_500ms, and 900 ms is le_2s, which
        // is neither of the two summed columns. A day window is these two rows added, so both must be right.
        assertEquals(listOf("1/1+0", "2/0+1"), perHour)
    }

    @Test
    @DisplayName("folding the same hour twice writes the same numbers")
    fun foldingTheSameHourTwiceChangesNothing() {
        call(currentHour(), TENANT_ID, "SUCCESS", 10L)
        rollup.rollUp()
        val before = jdbc.queryForList(
            "SELECT stat_hour, calls, successes, sum_duration_ms FROM tool_invocation_stats ORDER BY stat_hour, id",
        )

        rollup.rollUp()
        val after = jdbc.queryForList(
            "SELECT stat_hour, calls, successes, sum_duration_ms FROM tool_invocation_stats ORDER BY stat_hour, id",
        )

        // A recompute rather than an increment is exactly what lets a second replica run this without a lock.
        assertEquals(before, after)
    }

    private companion object {
        const val TENANT_ID = 1L
        const val RETENTION_DAYS = 365L
        val DB_TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}

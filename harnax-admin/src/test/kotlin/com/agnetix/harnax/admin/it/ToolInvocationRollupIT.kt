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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The hourly rollup against a real MySQL: which days get recomputed, and what the retention sweep may delete.
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
        date: LocalDate,
        tenantId: Long,
    ): Map<String, Any?>? = jdbc.queryForList(
        "SELECT calls, successes, errors, le_100ms, le_500ms, gt_30s, sum_duration_ms FROM tool_invocation_stats" +
            " WHERE stat_date = ? AND tenant_id = ? AND kind = 'builtin' AND subject_id = 0 AND tool_name = 'send_email'",
        date.toString(),
        tenantId,
    ).firstOrNull()

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
    @DisplayName("a day that was missed entirely is rolled on the next run")
    fun missedDayIsCaughtUp() {
        val missed = LocalDate.now().minusDays(3)
        call(missed.atTime(9, 0), TENANT_ID, "SUCCESS", 120L)
        call(missed.atTime(9, 5), TENANT_ID, "ERROR", 900L)

        // The missed day, plus the two days every run revisits.
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
    @DisplayName("today is re-rolled so the last run of the day is the day's final value")
    fun todayIsRerolled() {
        // Both instants are dated inside today by the calendar, not by `now - 2h`: within two hours of
        // midnight the wall-clock form would put the first row on yesterday and `statsFor(today)` would
        // have nothing to return. `upsertDay` bounds the day by an instant range and `selectUnrolledDates`
        // carries only a lower bound, so an instant later in the same calendar day still belongs to
        // today's row even when the clock has not reached it.
        val today = LocalDate.now()
        call(today.atTime(1, 0), TENANT_ID, "SUCCESS", 50L)
        rollup.rollUp()
        val afterFirst = requireNotNull(statsFor(today, TENANT_ID))["calls"]

        call(today.atTime(2, 0), TENANT_ID, "SUCCESS", 60L)
        rollup.rollUp()
        val afterSecond = requireNotNull(statsFor(today, TENANT_ID))["calls"]

        assertEquals(1L, (afterFirst as Number).toLong())
        assertEquals(2L, (afterSecond as Number).toLong())
    }

    @Test
    @DisplayName("a row that lands after a day was folded still gets folded")
    fun lateRowOfYesterdayIsFolded() {
        // The pending set stops naming a day once that day has an aggregate row, so only the forced
        // revisit of yesterday can pick up detail arriving between a day's last fold and midnight.
        val yesterday = LocalDate.now().minusDays(1)
        call(yesterday.atTime(22, 0), TENANT_ID, "SUCCESS", 100L)
        rollup.rollUp()
        assertEquals(1L, (requireNotNull(statsFor(yesterday, TENANT_ID))["calls"] as Number).toLong())

        call(yesterday.atTime(23, 58), TENANT_ID, "SUCCESS", 100L)
        rollup.rollUp()
        assertEquals(2L, (requireNotNull(statsFor(yesterday, TENANT_ID))["calls"] as Number).toLong())
    }

    @Test
    @DisplayName("a detail row with no tenant is not rolled up at all")
    fun tenantlessRowIsNeverRolled() {
        // The aggregate table cannot hold it (tenant_id NOT NULL); reporting it as pending would make
        // the difference set never empty and starve the days that can be rolled.
        call(LocalDate.now().minusDays(2).atTime(9, 0), null, "SUCCESS", 100L)

        // Only the two days every run revisits.
        assertEquals(2, rollup.rollUp())
        assertEquals(0, statsRows())
    }

    @Test
    @DisplayName("a detail row dated in the future is not folded")
    fun futureDatedRowIsNotFolded() {
        // An instance whose clock runs ahead writes detail for a day that has not ended, and the
        // aggregate must not take a partial value for it. Without the guard this day is folded now and
        // never re-opened, because the pending set stops naming it once a row exists.
        val tomorrow = LocalDate.now().plusDays(1)
        call(tomorrow.atTime(9, 0), TENANT_ID, "SUCCESS", 100L)

        assertEquals(2, rollup.rollUp())
        assertNull(statsFor(tomorrow, TENANT_ID))
    }

    @Test
    @DisplayName("an expired day is released only once its rollup exists")
    fun expiredRowWaitsForItsRollup() {
        val stale = LocalDate.now().minusDays(RETENTION_DAYS + 1L)
        call(stale.atTime(9, 0), TENANT_ID, "SUCCESS", 100L)

        // The sweep runs after the fold in the same pass, and the floor is unbounded so that day is
        // named as pending: one run both folds and releases. What has to hold is that the release
        // cannot happen before the aggregate took the day over, so the pair is asserted together.
        rollup.rollUp()

        assertEquals(0, detailCount(cutoff()))
        assertEquals(1L, (requireNotNull(statsFor(stale, TENANT_ID))["calls"] as Number).toLong())
    }

    @Test
    @DisplayName("the sweep refuses a day the rollup never folded")
    fun unrolledDayIsNeverReleased() {
        // This gate is what keeps I6 alive when a fold throws mid-run, and with a working rollup it is
        // unreachable through rollUp(): an unrolled day is by definition still pending, so the fold
        // names it first. It is reachable at the mapper seam, and that is where the gate gets proven.
        call(LocalDate.now().minusDays(RETENTION_DAYS + 1L).atTime(9, 0), TENANT_ID, "SUCCESS", 100L)

        assertEquals(0, logMapper.deleteRolledOut(cutoff()))
        assertEquals(1, detailCount(cutoff()))

        rollup.rollUp()
        assertEquals(0, detailCount(cutoff()))
    }

    @Test
    @DisplayName("expired rows with no tenant are released without a rollup")
    fun tenantlessExpiredRowsAreReleased() {
        // They never enter the aggregate table, so waiting for one would keep them forever.
        call(LocalDate.now().minusDays(RETENTION_DAYS + 1L).atTime(9, 0), null, "SUCCESS", 100L)

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
        val recent = LocalDate.now().minusDays(RETENTION_DAYS - 1L).atTime(9, 0)
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
        call(LocalDate.now().minusDays(3).atTime(9, 0), TENANT_ID, "SUCCESS", 100L)
        call(LocalDate.now().minusDays(RETENTION_DAYS + 1L).atTime(9, 0), TENANT_ID, "SUCCESS", 100L)

        rollup.rollUpHourly()

        assertEquals(0, statsRows())
        assertEquals(2, detailRows())
    }

    private companion object {
        const val TENANT_ID = 1L
        const val RETENTION_DAYS = 365L
        val DB_TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}

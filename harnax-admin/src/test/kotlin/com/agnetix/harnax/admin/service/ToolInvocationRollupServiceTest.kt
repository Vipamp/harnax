package com.agnetix.harnax.admin.service

import com.agnetix.harnax.mapper.ToolInvocationLogMapper
import com.agnetix.harnax.mapper.ToolInvocationStatsMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.atLeast
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.whenever
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The one number this service computes from a configuration value rather than from the rows, so it is the one
 * a mis-set env can move out of the reachable range without any statement failing.
 *
 * `ToolInvocationRollupIT` owns the rest of the contract — which hours get folded, what the sweep may and may
 * not release — against a real MySQL with `retention-days: 365`. It cannot reach an out-of-band window: the
 * container's configuration is one value for the whole class, and a row the sweep must not touch is the only
 * way a wrong cutoff shows up there. This class constructs the service directly for that one case.
 */
class ToolInvocationRollupServiceTest {

    private val logMapper = mock(ToolInvocationLogMapper::class.java)
    private val statsMapper = mock(ToolInvocationStatsMapper::class.java)

    /** The window inside the band, so the clamp is not what this class is checking. */
    private val service = ToolInvocationRollupService(
        toolInvocationLogMapper = logMapper,
        toolInvocationStatsMapper = statsMapper,
        retentionDays = 90L,
        rollupEnabled = true,
    )

    @Test
    @DisplayName("the run folds the closing hour and the hour still being written, unasked")
    fun `rollUp folds the current and the previous hour whatever the pending set says`() {
        val before = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        whenever(logMapper.selectUnrolledHours(any())).thenReturn(emptyList())

        service.rollUp()

        val hours = argumentCaptor<String>()
        verify(statsMapper, atLeast(2)).upsertHour(hours.capture())
        val folded = hours.allValues.map { LocalDateTime.parse(it, DB_TS) }.toSet()
        // The newest folded hour is the run's own current hour: at or after the instant captured above, and
        // less than an hour later. Asserted as a relation rather than as a literal stamp so that a run which
        // crosses the hour boundary between the capture and the call is not a false failure.
        val newest = requireNotNull(folded.maxOrNull()) { "nothing was folded" }
        val drift = Duration.between(before, newest)
        assertFalse(drift.isNegative || drift >= Duration.ofHours(1), "folded $folded for a window starting $before")
        // The hour before it is folded too, and unasked: the sweep fires at :05, so a detail row that arrives
        // between an hour's last fold and its close is already covered by an aggregate row and never re-enters
        // the pending set - without this the closing slice of every hour is folded never, and deleteRolledOut
        // releases those rows anyway.
        assertTrue(folded.contains(newest.minusHours(1)), "the closing hour is missing from $folded")
    }

    @Test
    @DisplayName("a retention window of zero is clamped before the sweep is handed its cutoff")
    fun `a retention window of zero is clamped instead of releasing the whole detail table`() {
        // `LocalDateTime.now().minusDays(0)` is `now`, and `deleteRolledOut` releases every row older than its
        // argument whose day has already been folded — which is every row the rollup has just folded. So the
        // value survives the run and empties the detail table at the next :05, silently: the aggregate still
        // answers by tool, while the agent and session dimensions and the drill-down drawer go blank.
        val service = ToolInvocationRollupService(
            toolInvocationLogMapper = logMapper,
            toolInvocationStatsMapper = statsMapper,
            retentionDays = 0L,
            rollupEnabled = true,
        )

        // Nothing is pending, so the run folds only the two days every run revisits — and reaches the sweep.
        assertEquals(2, service.rollUp())

        // Asserted on the cutoff the mapper is actually handed, not on the constructor having survived: at the
        // band's floor of 1 day the cutoff is a day back, never the running instant, and never the 90-day
        // default either. `ToolInvocationRollupIT` is the control that a window inside the band still reaches
        // the statement, so clamping here must not read as flattening the value.
        verify(logMapper).deleteRolledOut(
            argThat { before: String ->
                val age = Duration.between(LocalDateTime.parse(before, DB_TS), LocalDateTime.now())
                !age.isNegative && age >= Duration.ofHours(20) && age < Duration.ofDays(2)
            },
        )
    }

    private companion object {
        /** The same wall-clock shape the service formats its cutoff with. */
        val DB_TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}

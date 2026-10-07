package com.agnetix.harnax.admin.service

import com.agnetix.harnax.mapper.ToolInvocationLogMapper
import com.agnetix.harnax.mapper.ToolInvocationStatsMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.argThat
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The one number this service computes from a configuration value rather than from the rows, so it is the one
 * a mis-set env can move out of the reachable range without any statement failing.
 *
 * `ToolInvocationRollupIT` owns the rest of the contract — which days get folded, what the sweep may and may
 * not release — against a real MySQL with `retention-days: 365`. It cannot reach an out-of-band window: the
 * container's configuration is one value for the whole class, and a row the sweep must not touch is the only
 * way a wrong cutoff shows up there. This class constructs the service directly for that one case.
 */
class ToolInvocationRollupServiceTest {

    private val logMapper = mock(ToolInvocationLogMapper::class.java)
    private val statsMapper = mock(ToolInvocationStatsMapper::class.java)

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

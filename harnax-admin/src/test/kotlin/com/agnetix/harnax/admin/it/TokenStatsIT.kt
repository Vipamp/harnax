package com.agnetix.harnax.admin.it

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Token statistics regression: /api/admin/token-stats
 *
 * A fresh database has no token consumption records, so these tests assert the
 * endpoints respond successfully with empty/zero aggregations rather than
 * concrete consumption numbers.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TokenStatsIT : BaseAdminIT() {

    @Test
    fun `aggregation stats with default range returns zeroed overall stats`() {
        val data = assertOk(getJson("/api/admin/token-stats/aggregation"))
        val overall = data["overall"]
        assertNotNull(overall, "overall stats should be present: $data")
        assertEquals(0L, overall["grandTotalToken"].asLong())
        assertEquals(0L, overall["totalInputToken"].asLong())
        assertEquals(0L, overall["totalOutputToken"].asLong())
    }

    @Test
    fun `aggregation stats accepts explicit time range`() {
        // '+' is decoded as a space by the servlet container; a raw '%20' would be double-encoded by RestTemplate
        val query = "startTime=2026-01-01+00:00:00&endTime=2026-01-02+00:00:00"
        val data = assertOk(getJson("/api/admin/token-stats/aggregation?$query"))
        assertNotNull(data["overall"])
    }

    @Test
    fun `time series with day granularity returns data list`() {
        val data = assertOk(getJson("/api/admin/token-stats/time-series?granularity=day"))
        val series = data["timeSeriesData"]
        assertTrue(series == null || series.isNull || series.isArray, "timeSeriesData should be a list or null: $data")
    }

    @Test
    fun `time series with hour granularity succeeds`() {
        assertOk(getJson("/api/admin/token-stats/time-series?granularity=hour"))
    }

    @Test
    fun `model agent and session dimension time series succeed`() {
        assertOk(getJson("/api/admin/token-stats/time-series/model?granularity=day"))
        assertOk(getJson("/api/admin/token-stats/time-series/agent?granularity=day"))
        assertOk(getJson("/api/admin/token-stats/time-series/session?granularity=day"))
    }
}

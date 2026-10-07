package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.DashboardOverviewResponse
import com.agnetix.harnax.admin.dto.DashboardRankItem
import com.agnetix.harnax.admin.dto.DashboardTrendPoint
import com.agnetix.harnax.admin.service.DashboardService
import com.agnetix.harnax.mapper.DashboardMapper
import com.agnetix.harnax.mapper.TokenStatsMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Builds the landing-page overview out of the existing aggregation reads plus two dashboard-shaped ones.
 *
 * Read-only, no transaction and no cache: every number is a live count over one tenant, and a cached
 * landing page would keep showing a workspace's own activity as it was a minute ago with no way to tell.
 *
 * Nothing here calls [LocalDateTime.now]. The clock arrives as a parameter so all four windows hang off
 * one instant — reading it twice inside a request would let "today so far" and "yesterday up to the same
 * hour" disagree about which hour they mean, and the comparison would drift by whatever the two reads
 * took.
 */
@Service
class DashboardServiceImpl(
    private val tokenStatsMapper: TokenStatsMapper,
    private val dashboardMapper: DashboardMapper,
) : DashboardService {

    private val log = LoggerFactory.getLogger(DashboardServiceImpl::class.java)

    override fun overview(
        tenantId: Long,
        now: LocalDateTime,
    ): DashboardOverviewResponse {
        val todayStart = now.toLocalDate().atStartOfDay()
        val todayFrom = timestamp(todayStart)
        val todayTo = timestamp(now)

        // Yesterday's matching span: the same clock point one day back. Comparing today-so-far against the
        // whole of yesterday would read as a decline at nine every morning and call that a trend.
        val yesterdayFrom = timestamp(todayStart.minusDays(1))
        val yesterdayTo = timestamp(now.minusDays(1))

        // Fourteen day points ending today, so the window opens at the start of the thirteenth day back.
        // The trend, both rankings and the fee total share this one window.
        val trendStart = todayStart.minusDays((TREND_DAYS - 1).toLong())
        val trendFrom = timestamp(trendStart)
        val trendTo = todayTo

        // The active-user window has no end to state: the count is "logged in since", and `now` already
        // bounds it from above.
        val activeUserSince = timestamp(now.minusDays(ACTIVE_USER_DAYS))

        val assetRow = dashboardMapper.getTenantAssetCounts(tenantId, activeUserSince)
        val platformRow = dashboardMapper.getPlatformCounts()

        val response =
            DashboardOverviewResponse(
                today = windowStats(todayFrom, todayTo, tenantId),
                yesterdaySameSpan = windowStats(yesterdayFrom, yesterdayTo, tenantId),
                trend = trend(trendFrom, trendTo, trendStart, tenantId),
                topAgents = rank(tokenStatsMapper.aggregateByAgent(trendFrom, trendTo, tenantId), "agentName"),
                topModels = rank(tokenStatsMapper.aggregateByModel(trendFrom, trendTo, tenantId), "modelName", "providerName"),
                assets = DashboardOverviewResponse.mapToAssetCounts(assetRow),
                platform = DashboardOverviewResponse.mapToPlatformCounts(platformRow),
                pendingSkillDrafts = DashboardOverviewResponse.pendingSkillDraftsOf(assetRow),
                totalUsers = DashboardOverviewResponse.totalUsersOf(assetRow),
                activeUsersLast7Days = DashboardOverviewResponse.activeUsersOf(assetRow),
                recent14dFee = DashboardOverviewResponse.recent14dFeeOf(tokenStatsMapper.getOverallStats(trendFrom, trendTo, tenantId)),
                serverTime = todayTo,
            )

        log.info(
            "Dashboard overview built, tenantId: {}, today {} to {}, trend from {}",
            tenantId,
            todayFrom,
            todayTo,
            trendFrom,
        )
        return response
    }

    /**
     * One window of consumption.
     *
     * The aggregate always answers a row — COUNT over no rows is 0 and the SUM is COALESCEd — but a null
     * row still has to read as a zeroed window rather than as a failed request.
     */
    private fun windowStats(
        from: String,
        to: String,
        tenantId: Long,
    ) = DashboardOverviewResponse.mapToWindowStats(tokenStatsMapper.getDashboardWindowStats(from, to, tenantId))

    /**
     * The 14-day series, padded to exactly one point per day.
     *
     * The query reports only the days holding consumption. The page plots a fixed 14-point line, so every
     * day without a row is produced here at zero rather than left out of the series.
     */
    private fun trend(
        from: String,
        to: String,
        trendStart: LocalDateTime,
        tenantId: Long,
    ): List<DashboardTrendPoint> {
        val rows = tokenStatsMapper.getDashboardDailyTrend(from, to, tenantId).orEmpty().filterNotNull()

        // One row per day is what GROUP BY on the day bucket gives. The key is the day rather than the
        // full timestamp, so a bucket and a generated point agree on which day a row belongs to.
        val rowByDay = mutableMapOf<String, MutableMap<String?, Any?>>()
        for (row in rows) {
            val day = dayOf(row["timePoint"]) ?: continue
            rowByDay[day] = row
        }

        return (0 until TREND_DAYS).map { offset ->
            val day = trendStart.plusDays(offset.toLong()).format(DAY_FORMATTER)
            val row = rowByDay[day]
            if (row == null) DashboardOverviewResponse.emptyTrendPoint(day) else DashboardOverviewResponse.mapToTrendPoint(day, row)
        }
    }

    /**
     * Top 5 of an already ordered aggregate.
     *
     * `aggregateByAgent` / `aggregateByModel` come back ordered by `grandTotalToken DESC`, so this takes
     * the head of that list and sorts nothing of its own — re-sorting here would re-decide the order over
     * the widened types the driver hands back, and the page would disagree with the token monitor.
     *
     * @param qualifierKey The column holding the second label, or null when the dimension has none. Only
     * the model aggregate has one: it groups by `chat_model_id`, so the same model name registered under
     * two providers answers as two rows, and `providerName` is what separates them.
     */
    private fun rank(
        rows: MutableList<MutableMap<String?, Any?>?>?,
        nameKey: String,
        qualifierKey: String? = null,
    ): List<DashboardRankItem> = rows.orEmpty().filterNotNull().take(RANK_LIMIT).map {
        DashboardOverviewResponse.mapToRankItem(it, nameKey, qualifierKey)
    }

    /**
     * The day a bucket row belongs to, `yyyy-MM-dd`.
     *
     * The statement CASTs its bucket to DATETIME, so the driver hands back a [LocalDateTime]; a row of
     * any other shape keys on no day and gets dropped, the same rule the token-monitor series applies to
     * its own buckets.
     */
    private fun dayOf(timePoint: Any?): String? = when (timePoint) {
        is LocalDateTime -> timePoint.format(DAY_FORMATTER)
        is java.sql.Timestamp -> timePoint.toLocalDateTime().format(DAY_FORMATTER)
        is String -> timePoint.substringBefore(' ').takeIf { it.length == DAY_LENGTH }
        else -> null
    }

    private fun timestamp(time: LocalDateTime): String = time.format(TIMESTAMP_FORMATTER)

    companion object {
        /** Day points in the trend, and the days both rankings and the fee window look back over. */
        private const val TREND_DAYS = 14

        /** Days the active-user count looks back over. */
        private const val ACTIVE_USER_DAYS = 7L

        /** Entries per ranking; the page has room for five, and the server is what enforces that. */
        private const val RANK_LIMIT = 5

        private const val DAY_LENGTH = 10

        /** The format every window bound is handed to the mappers in, matching the shared fragment. */
        private val TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /** The format the padded trend is keyed and answered in. */
        private val DAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}

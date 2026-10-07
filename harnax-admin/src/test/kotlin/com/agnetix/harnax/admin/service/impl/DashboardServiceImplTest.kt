package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.DashboardAssetCounts
import com.agnetix.harnax.admin.dto.DashboardPlatformCounts
import com.agnetix.harnax.admin.dto.DashboardRankItem
import com.agnetix.harnax.admin.dto.DashboardWindowStats
import com.agnetix.harnax.mapper.DashboardMapper
import com.agnetix.harnax.mapper.TokenStatsMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.quality.Strictness
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Unit tests for [DashboardServiceImpl].
 *
 * The service is built through its own constructor rather than injected as a field, so this class states
 * which two collaborators the overview is allowed to have and a moved signature breaks one test instead
 * of the whole file.
 *
 * Two things shape how the assertions are written, and both come from the same hazard: every read here
 * falls back to zero when the mapper answers nothing, so a stub that quietly matched no call would leave
 * a fully green test over a service that had stopped asking for anything. The stubs therefore match on
 * argument wildcards only, and what the service actually handed the mappers is asserted separately —
 * captured verbatim for the window bounds and re-verified with the literal tenant for the pass-through.
 *
 * The clock is pinned because four windows are derived from one injected instant: against a real
 * [LocalDateTime.now] the boundaries would move while the test ran and nothing could be named exactly.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DashboardServiceImplTest {

    @Mock
    private lateinit var tokenStatsMapper: TokenStatsMapper

    @Mock
    private lateinit var dashboardMapper: DashboardMapper

    private fun service(): DashboardServiceImpl = DashboardServiceImpl(tokenStatsMapper, dashboardMapper)

    private fun windowRow(
        calls: Long,
        tokens: Long,
        sessions: Long,
        agents: Long,
    ): MutableMap<String?, Any?> = mutableMapOf(
        "calls" to calls,
        "tokens" to tokens,
        "sessions" to sessions,
        "agents" to agents,
    )

    private fun trendRow(
        timePoint: Any?,
        calls: Long,
        tokens: Long,
    ): MutableMap<String?, Any?> = mutableMapOf(
        "timePoint" to timePoint,
        "calls" to calls,
        "tokens" to tokens,
    )

    private fun rankRow(
        nameKey: String,
        name: String?,
        tokens: Long,
    ): MutableMap<String?, Any?> = mutableMapOf(nameKey to name, "grandTotalToken" to tokens)

    /** A model ranking row: the statement also selects the provider, which is the item's second label. */
    private fun modelRow(
        name: String?,
        provider: String?,
        tokens: Long,
    ): MutableMap<String?, Any?> = mutableMapOf(
        "modelName" to name,
        "providerName" to provider,
        "grandTotalToken" to tokens,
    )

    @BeforeEach
    fun stubSilentCollaborators() {
        // Every read answers "nothing" by default, so a test only sees the rows it deliberately seeded.
        `when`(tokenStatsMapper.getDashboardWindowStats(anyOrNull(), anyOrNull(), anyLong())).thenReturn(null)
        `when`(tokenStatsMapper.getDashboardDailyTrend(anyOrNull(), anyOrNull(), anyLong())).thenReturn(mutableListOf())
        `when`(tokenStatsMapper.getOverallStats(anyOrNull(), anyOrNull(), anyLong())).thenReturn(null)
        `when`(tokenStatsMapper.aggregateByAgent(anyOrNull(), anyOrNull(), anyLong())).thenReturn(null)
        `when`(tokenStatsMapper.aggregateByModel(anyOrNull(), anyOrNull(), anyLong())).thenReturn(null)
        `when`(dashboardMapper.getTenantAssetCounts(anyLong(), anyOrNull())).thenReturn(null)
        `when`(dashboardMapper.getPlatformCounts()).thenReturn(null)
    }

    @Test
    @DisplayName("the four windows handed to the mappers are the injected clock's, to the second")
    fun windowsComeFromTheInjectedClock() {
        val starts = argumentCaptor<String>()
        val ends = argumentCaptor<String>()

        val response = service().overview(TENANT_ID, NOW)

        // Exactly the two window reads, each carrying one of the two expected pairs. A service reading its
        // own clock would name today's real date instead of 2026-10-05 and both pairs fail; so would one
        // that mixed the two up, or that widened yesterday's span to the whole of yesterday.
        verify(tokenStatsMapper, times(2)).getDashboardWindowStats(starts.capture(), ends.capture(), anyLong())
        assertEquals(
            setOf(TODAY_FROM to TODAY_TO, YESTERDAY_FROM to YESTERDAY_TO),
            starts.allValues.zip(ends.allValues).toSet(),
        )

        // One 14-day window, shared by the trend, both rankings and the fee read.
        verify(tokenStatsMapper).getDashboardDailyTrend(eq(TREND_FROM), eq(TREND_TO), eq(TENANT_ID))
        verify(tokenStatsMapper).getOverallStats(eq(TREND_FROM), eq(TREND_TO), eq(TENANT_ID))
        verify(tokenStatsMapper).aggregateByAgent(eq(TREND_FROM), eq(TREND_TO), eq(TENANT_ID))
        verify(tokenStatsMapper).aggregateByModel(eq(TREND_FROM), eq(TREND_TO), eq(TENANT_ID))

        // The 7-day window has only a start to hand over, and it travels as the asset read's own argument.
        verify(dashboardMapper).getTenantAssetCounts(eq(TENANT_ID), eq(ACTIVE_USER_SINCE))

        // The instant the page shows as "data as of" is the one it was given, not a later one.
        assertEquals(TODAY_TO, response.serverTime)
    }

    @Test
    @DisplayName("every scoped read carries the tenant it was given, and the platform read carries none")
    fun scopedReadsCarryTheGivenTenantAndThePlatformReadCarriesNone() {
        service().overview(TENANT_ID, NOW)

        verify(tokenStatsMapper, times(2)).getDashboardWindowStats(anyOrNull(), anyOrNull(), eq(TENANT_ID))
        verify(tokenStatsMapper).getDashboardDailyTrend(anyOrNull(), anyOrNull(), eq(TENANT_ID))
        verify(tokenStatsMapper).getOverallStats(anyOrNull(), anyOrNull(), eq(TENANT_ID))
        verify(tokenStatsMapper).aggregateByAgent(anyOrNull(), anyOrNull(), eq(TENANT_ID))
        verify(tokenStatsMapper).aggregateByModel(anyOrNull(), anyOrNull(), eq(TENANT_ID))
        verify(dashboardMapper).getTenantAssetCounts(eq(TENANT_ID), anyOrNull())

        // 7 is not the default workspace, so a service that resolved a tenant of its own instead of using
        // the parameter would show up as a call for another id.
        verify(tokenStatsMapper, never()).getDashboardWindowStats(anyOrNull(), anyOrNull(), eq(OTHER_TENANT_ID))
        verify(tokenStatsMapper, never()).getDashboardDailyTrend(anyOrNull(), anyOrNull(), eq(OTHER_TENANT_ID))
        verify(tokenStatsMapper, never()).getOverallStats(anyOrNull(), anyOrNull(), eq(OTHER_TENANT_ID))
        verify(dashboardMapper, never()).getTenantAssetCounts(eq(OTHER_TENANT_ID), anyOrNull())

        // The registry read takes no tenant argument at all: the signature is the guarantee, and this line
        // is the one that has to move if a tenant parameter ever creeps into it.
        verify(dashboardMapper, times(1)).getPlatformCounts()
    }

    @Test
    @DisplayName("a day with no consumption still gets its point, at zero")
    fun trendIsPaddedToFourteenDayPoints() {
        val rows = mutableListOf<MutableMap<String?, Any?>?>()
        for (day in TREND_DAY_DATES) {
            if (day in GAP_DATES) continue
            val bucket = if (day == TIMED_DAY) {
                // One bucket handed back with a time on it. The statement always truncates to midnight, so
                // this pins that a point keys on its day rather than on the full timestamp.
                LocalDateTime.parse("$day 14:30:00", TIMESTAMP)
            } else {
                LocalDateTime.parse("$day 00:00:00", TIMESTAMP)
            }
            // Every day carries a value derived from itself: a series shifted by one day then disagrees
            // with its own labels everywhere at once, rather than looking plausible at a glance.
            val value = day.substring(DAY_FIELD_START).toLong()
            rows.add(trendRow(bucket, value, value * 1000L))
        }
        assertEquals(11, rows.size, "three days left out of the eleven the query would answer")
        `when`(tokenStatsMapper.getDashboardDailyTrend(anyOrNull(), anyOrNull(), anyLong())).thenReturn(rows)

        val trend = service().overview(TENANT_ID, NOW).trend

        assertEquals(14, trend.size, "the series is always fourteen points")
        assertEquals(TREND_DAY_DATES, trend.map { it.date }, "one point per day, oldest first")

        GAP_DATES.forEach { gap ->
            val point = trend.first { it.date == gap }
            assertEquals(0L, point.calls, "$gap holds no consumption")
            assertEquals(0L, point.tokens, "$gap holds no consumption")
        }
        TREND_DAY_DATES.filterNot { it in GAP_DATES }.forEach { day ->
            val point = trend.first { it.date == day }
            val value = day.substring(DAY_FIELD_START).toLong()
            assertEquals(value, point.calls, "$day keeps its own row")
            assertEquals(value * 1000L, point.tokens, "$day keeps its own row")
        }
    }

    @Test
    @DisplayName("the rankings keep the order the SQL gave and stop at five")
    fun rankingsAreTruncatedNotResorted() {
        // Deliberately not descending. The statements already order by grandTotalToken DESC; a service that
        // sorted again here would reorder these rows and decide the order over the widened types the
        // driver hands back, disagreeing with the token monitor next door.
        val agentRows = mutableListOf<MutableMap<String?, Any?>?>(
            rankRow("agentName", "Eleven", 10L),
            rankRow("agentName", null, 900L),
            rankRow("agentName", "Twenty", 20L),
            rankRow("agentName", "ThreeHundred", 300L),
            rankRow("agentName", "Forty", 40L),
            rankRow("agentName", "FiveHundred", 500L),
            rankRow("agentName", "Sixty", 60L),
        )
        val modelRows = mutableListOf<MutableMap<String?, Any?>?>(
            rankRow("modelName", "Alpha", 1L),
            rankRow("modelName", "Beta", 2L),
            rankRow("modelName", "Gamma", 3L),
            rankRow("modelName", "Delta", 4L),
            rankRow("modelName", "Epsilon", 5L),
            rankRow("modelName", "Zeta", 6L),
            rankRow("modelName", "Eta", 7L),
        )
        `when`(tokenStatsMapper.aggregateByAgent(anyOrNull(), anyOrNull(), anyLong())).thenReturn(agentRows)
        `when`(tokenStatsMapper.aggregateByModel(anyOrNull(), anyOrNull(), anyLong())).thenReturn(modelRows)

        val response = service().overview(TENANT_ID, NOW)

        assertEquals(5, response.topAgents.size, "the server is what caps a ranking at five")
        assertEquals(5, response.topModels.size, "and the same for the other list")
        assertEquals(
            listOf("Eleven" to 10L, "" to 900L, "Twenty" to 20L, "ThreeHundred" to 300L, "Forty" to 40L),
            response.topAgents.map { it.name to it.tokens },
            "the first five rows, in the order the SQL returned them",
        )
        assertEquals(
            listOf("Alpha" to 1L, "Beta" to 2L, "Gamma" to 3L, "Delta" to 4L, "Epsilon" to 5L),
            response.topModels.map { it.name to it.tokens },
        )
    }

    @Test
    @DisplayName("the model ranking carries the provider as its discriminator, the agent ranking carries none")
    fun modelRankingCarriesTheProviderQualifier() {
        // Two model rows legitimately share a name: the statement groups by the model row, so the same
        // display name under two providers comes back twice, and the page would show the same line twice.
        // The provider is the only other label on the row that tells them apart.
        val modelRows = mutableListOf<MutableMap<String?, Any?>?>(
            modelRow("qwen3.7-flash", "Aliyun", 250_710L),
            modelRow("qwen3.7-flash", "Internal Gateway", 180_000L),
            // A consumed row whose model was deleted: both LEFT JOINed names are null, and the tokens still
            // count, so the item carries two empty strings rather than being dropped.
            modelRow(null, null, 6_400L),
        )
        `when`(tokenStatsMapper.aggregateByModel(anyOrNull(), anyOrNull(), anyLong())).thenReturn(modelRows)
        `when`(tokenStatsMapper.aggregateByAgent(anyOrNull(), anyOrNull(), anyLong())).thenReturn(
            mutableListOf<MutableMap<String?, Any?>?>(rankRow("agentName", "Assistant", 10L)),
        )

        val response = service().overview(TENANT_ID, NOW)

        assertEquals(
            listOf(
                DashboardRankItem(name = "qwen3.7-flash", qualifier = "Aliyun", tokens = 250_710L),
                DashboardRankItem(name = "qwen3.7-flash", qualifier = "Internal Gateway", tokens = 180_000L),
                DashboardRankItem(name = "", qualifier = "", tokens = 6_400L),
            ),
            response.topModels,
        )

        // The agent dimension has no second label to carry. A ranking built through the one-argument shape
        // must come back with an empty qualifier, not with the agent name duplicated into it.
        assertEquals(
            listOf(DashboardRankItem(name = "Assistant", qualifier = "", tokens = 10L)),
            response.topAgents,
        )
    }

    @Test
    @DisplayName("each response field is filled from the window or row that owns it")
    fun rowsMapOntoTheResponseFields() {
        // Keyed on the window each read was asked for, so today and yesterday cannot be swapped without
        // the response saying so.
        `when`(tokenStatsMapper.getDashboardWindowStats(anyOrNull(), anyOrNull(), anyLong())).thenAnswer { call ->
            when (call.getArgument<String?>(0)) {
                TODAY_FROM -> windowRow(111L, 222L, 333L, 444L)
                YESTERDAY_FROM -> windowRow(555L, 666L, 777L, 888L)
                else -> null
            }
        }
        `when`(tokenStatsMapper.getOverallStats(anyOrNull(), anyOrNull(), anyLong()))
            .thenReturn(mutableMapOf("totalFee" to BigDecimal("17")))
        `when`(dashboardMapper.getTenantAssetCounts(anyLong(), anyOrNull())).thenReturn(
            mutableMapOf(
                "agents" to 12L,
                "skills" to 4L,
                "models" to 3L,
                "mcpServers" to 2L,
                "channels" to 1L,
                "teams" to 6L,
                "sessions" to 9L,
                "users" to 7L,
                "activeUsers" to 5L,
                "pendingSkillDrafts" to 8L,
            ),
        )
        `when`(dashboardMapper.getPlatformCounts())
            .thenReturn(mutableMapOf("tools" to 40L, "cliPackages" to 3L))

        val response = service().overview(TENANT_ID, NOW)

        assertEquals(DashboardWindowStats(111L, 222L, 333L, 444L), response.today)
        assertEquals(DashboardWindowStats(555L, 666L, 777L, 888L), response.yesterdaySameSpan)
        assertEquals(
            DashboardAssetCounts(
                agents = 12L,
                skills = 4L,
                models = 3L,
                mcpServers = 2L,
                channels = 1L,
                teams = 6L,
                sessions = 9L,
                users = 7L,
            ),
            response.assets,
        )
        assertEquals(DashboardPlatformCounts(40L, 3L), response.platform)
        assertEquals(7L, response.totalUsers, "the strip's user cell and the todo line are one count")
        assertEquals(5L, response.activeUsersLast7Days)
        assertEquals(8L, response.pendingSkillDrafts)
        assertEquals(BigDecimal("17"), response.recent14dFee)
    }

    @Test
    @DisplayName("a tenant with nothing in it reads as zeros and fourteen empty days, not as an error")
    fun anEmptyTenantStillAnswersAWholePage() {
        // The default stubs stand: every read answers no row at all.
        val response = service().overview(TENANT_ID, NOW)

        assertEquals(DashboardWindowStats(), response.today)
        assertEquals(DashboardWindowStats(), response.yesterdaySameSpan)
        assertEquals(DashboardAssetCounts(), response.assets)
        assertEquals(DashboardPlatformCounts(), response.platform)
        assertEquals(BigDecimal.ZERO, response.recent14dFee)
        assertEquals(emptyList<DashboardRankItem>(), response.topAgents)
        assertEquals(14, response.trend.size, "an empty window still plots fourteen points")
        assertEquals(TREND_DAY_DATES, response.trend.map { it.date })
        response.trend.forEach { point ->
            assertEquals(0L, point.calls, "${point.date} padded to zero")
            assertEquals(0L, point.tokens, "${point.date} padded to zero")
        }
    }

    private companion object {
        /** Not the default workspace, so a service that resolved a tenant of its own cannot pass. */
        const val TENANT_ID = 7L

        const val OTHER_TENANT_ID = 8L

        /** A Monday afternoon, named so every boundary below can be written out instead of recomputed. */
        val NOW: LocalDateTime = LocalDateTime.of(2026, 10, 5, 13, 45, 30)

        val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        const val TODAY_FROM = "2026-10-05 00:00:00"
        const val TODAY_TO = "2026-10-05 13:45:30"

        // Yesterday's own midnight and the same clock point one day back: the pair that makes the
        // comparison mean anything, rather than today-so-far against the whole of yesterday.
        const val YESTERDAY_FROM = "2026-10-04 00:00:00"
        const val YESTERDAY_TO = "2026-10-04 13:45:30"

        // Fourteen day points ending today, so the window opens at midnight on the thirteenth day back.
        const val TREND_FROM = "2026-09-22 00:00:00"
        const val TREND_TO = "2026-10-05 13:45:30"

        const val ACTIVE_USER_SINCE = "2026-09-28 13:45:30"

        /** The days the padded series is expected to cover, in the order the page plots them. */
        val TREND_DAY_DATES = listOf(
            "2026-09-22",
            "2026-09-23",
            "2026-09-24",
            "2026-09-25",
            "2026-09-26",
            "2026-09-27",
            "2026-09-28",
            "2026-09-29",
            "2026-09-30",
            "2026-10-01",
            "2026-10-02",
            "2026-10-03",
            "2026-10-04",
            "2026-10-05",
        )

        val GAP_DATES = setOf("2026-09-22", "2026-09-28", "2026-10-05")

        /** The day whose row comes back with a time on it, to pin the day-normalisation. */
        const val TIMED_DAY = "2026-10-01"

        /** Where the day-of-month starts in a `yyyy-MM-dd` string. */
        const val DAY_FIELD_START = 8
    }
}

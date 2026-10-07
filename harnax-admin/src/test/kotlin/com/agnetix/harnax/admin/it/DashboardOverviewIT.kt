package com.agnetix.harnax.admin.it

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The landing-page overview against real rows (design 6.2).
 *
 * Everything asserted here is a **delta**, not an absolute value, and that is forced by the shape of the
 * page: "today" can only be measured against the wall clock, so it cannot be pinned to a quiet March
 * window the way `TokenStatsAggregationIT` pins its own reads. The shared container also keeps every row
 * written by the other classes in the same JVM run, so an absolute count would be a number nobody chose.
 * The answer is to read the endpoint, seed, read it again, and require the difference to be exactly what
 * was just inserted — which catches a missing window bound or a dropped join as faithfully as an absolute
 * assertion would, without depending on what ran before this class.
 *
 * Ids are local to this class and sit in a range nothing else uses, so the seeded rows cannot collide with
 * another class's fixtures.
 */
class DashboardOverviewIT : BaseAdminIT() {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private val agentId = 960_001L
    private val secondAgentId = 960_002L
    private val providerOneId = 960_003L
    private val providerTwoId = 960_004L
    private val modelOneId = 960_005L
    private val modelTwoId = 960_006L
    private val sessionOne = "dashboard-overview-it-session-1"
    private val sessionTwo = "dashboard-overview-it-session-2"
    private val sessionThree = "dashboard-overview-it-session-3"

    /** What the three seeded calls add up to: 150 + 270 + 380, over two sessions and one agent. */
    private val seededTokens = 800L
    private val seededFee = 6L

    @AfterEach
    fun clearRows() {
        jdbc.update("DELETE FROM token_stats WHERE tenant_id = ? AND session_id IN (?, ?, ?)", TENANT_ID, sessionOne, sessionTwo, sessionThree)
        jdbc.update("DELETE FROM session WHERE tenant_id = ? AND session_id IN (?, ?)", TENANT_ID, sessionOne, sessionTwo)
        jdbc.update("DELETE FROM `model` WHERE id IN (?, ?)", modelOneId, modelTwoId)
        jdbc.update("DELETE FROM model_provider WHERE id IN (?, ?)", providerOneId, providerTwoId)
        jdbc.update("DELETE FROM agent WHERE id IN (?, ?)", agentId, secondAgentId)
    }

    private fun insertStats(
        sessionId: String,
        input: Long,
        output: Long,
        fee: Long,
    ) {
        // The row is written at the clock reading the page is about to measure, because the window it has
        // to fall inside is "today so far". Seconds precision matches the column and the boundary format.
        jdbc.update(
            "INSERT INTO token_stats (tenant_id, agent_id, session_id, chat_model_id, input_token, output_token, total_token, fee, ts) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            TENANT_ID,
            agentId,
            sessionId,
            null,
            input,
            output,
            input + output,
            fee,
            LocalDateTime.now().format(TIMESTAMP),
        )
    }

    @Test
    @DisplayName("seeding one page of activity moves today by exactly that activity, and yesterday's span by nothing")
    fun todayWindowMovesByExactlyWhatWasSeeded() {
        val before = assertOk(getJson(OVERVIEW, tenantId = TENANT_ID))

        jdbc.update(
            "INSERT INTO agent (id, tenant_id, name, creator, active) VALUES (?, ?, 'Dashboard Overview Agent', 'admin', 1)",
            agentId,
            TENANT_ID,
        )
        jdbc.update(
            "INSERT INTO session (tenant_id, title, session_id, agent_id, creator, active) VALUES (?, 'Overview Session One', ?, ?, 'admin', 1)",
            TENANT_ID,
            sessionOne,
            agentId,
        )
        jdbc.update(
            "INSERT INTO session (tenant_id, title, session_id, agent_id, creator, active) VALUES (?, 'Overview Session Two', ?, ?, 'admin', 1)",
            TENANT_ID,
            sessionTwo,
            agentId,
        )
        insertStats(sessionOne, 100L, 50L, 1L)
        insertStats(sessionOne, 200L, 70L, 2L)
        insertStats(sessionTwo, 300L, 80L, 3L)

        val after = assertOk(getJson(OVERVIEW, tenantId = TENANT_ID))

        // Two calls in one conversation and one in another, over a single agent.
        assertEquals(3L, delta(before, after, "today", "calls"), "three model calls were inserted")
        assertEquals(seededTokens, delta(before, after, "today", "tokens"), "the three rows' totals")
        assertEquals(2L, delta(before, after, "today", "sessions"), "COUNT(DISTINCT session_id)")
        assertEquals(1L, delta(before, after, "today", "agents"), "COUNT(DISTINCT agent_id)")

        // Yesterday's matching span must stay untouched. Handing the yesterday read today's end bound —
        // the minusDays(1) dropped from the upper bound only — would sweep these three rows into it and
        // turn the page's comparison into a fabricated fall.
        assertEquals(0L, delta(before, after, "yesterdaySameSpan", "calls"), "yesterday's span ends a day earlier than today's")
        assertEquals(0L, delta(before, after, "yesterdaySameSpan", "tokens"), "and holds none of today's consumption")

        // The fee card is the fourteen-day total read off the existing overall statement, so it moves too.
        assertEquals(
            seededFee,
            topDelta(before, after, "recent14dFee"),
            "the seeded fees reach the 14-day total",
        )

        // The last trend point is today. This is the assertion that the day bucket survives the driver:
        // a timePoint that does not come back as a LocalDateTime keys on nothing and the point stays 0.
        val beforeToday = trendPoint(before, TREND_POINTS - 1)
        val afterToday = trendPoint(after, TREND_POINTS - 1)
        assertEquals(3L, afterToday["calls"].asLong() - beforeToday["calls"].asLong(), "today's trend point gained the three calls")
        assertEquals(
            seededTokens,
            afterToday["tokens"].asLong() - beforeToday["tokens"].asLong(),
            "today's trend point gained their tokens",
        )

        // Asset counts are plain tenant-scoped rows: one agent and two sessions were added.
        assertEquals(1L, delta(before, after, "assets", "agents"), "the seeded agent is an asset")
        assertEquals(2L, delta(before, after, "assets", "sessions"), "and so are the two sessions")

        // One more agent moves the stock-take and nothing else: consumption did not happen.
        jdbc.update(
            "INSERT INTO agent (id, tenant_id, name, creator, active) VALUES (?, ?, 'Dashboard Overview Second Agent', 'admin', 1)",
            secondAgentId,
            TENANT_ID,
        )
        val afterAssetOnly = assertOk(getJson(OVERVIEW, tenantId = TENANT_ID))
        assertEquals(1L, delta(after, afterAssetOnly, "assets", "agents"), "the second agent is counted")
        assertEquals(0L, delta(after, afterAssetOnly, "today", "calls"), "an asset row is not consumption")
        assertEquals(0L, delta(after, afterAssetOnly, "today", "tokens"), "and brings no tokens with it")
    }

    @Test
    @DisplayName("the response carries every field it promises, with no null holes for the page to trip over")
    fun responseCarriesEveryFieldWithDefaults() {
        val data = assertOk(getJson(OVERVIEW, tenantId = TENANT_ID))

        listOf(
            "today",
            "yesterdaySameSpan",
            "trend",
            "topAgents",
            "topModels",
            "assets",
            "platform",
            "pendingSkillDrafts",
            "totalUsers",
            "activeUsersLast7Days",
            "recent14dFee",
            "serverTime",
        ).forEach { field ->
            assertTrue(data.has(field) && !data[field].isNull, "$field must be present: admin's JSON writer drops null keys")
        }

        listOf("calls", "tokens", "sessions", "agents").forEach { field ->
            assertTrue(data["today"].has(field), "a window block names $field even at zero")
        }
        listOf("agents", "skills", "models", "mcpServers", "channels", "teams", "sessions", "users").forEach { field ->
            assertTrue(data["assets"].has(field), "the asset strip names $field even at zero")
        }
        listOf("tools", "cliPackages").forEach { field ->
            assertTrue(data["platform"].has(field), "the platform line names $field")
        }

        // Fourteen points whatever the tenant has consumed, so the page can plot without padding itself.
        assertEquals(TREND_POINTS, data["trend"].size(), "the trend is always fourteen points")
        assertTrue(data["trend"][0]["date"].asText().length == 10, "a point is labelled by its day")
        assertTrue(data["topAgents"].size() <= RANK_LIMIT, "the server caps a ranking at five")
        assertTrue(data["topModels"].size() <= RANK_LIMIT, "in both lists")
        assertTrue(data["serverTime"].asText().isNotEmpty(), "the page shows the data as of a stated instant")
    }

    /**
     * Two model rows carrying one display name have to come back tellable apart (design 3.2).
     *
     * The ranking groups by the model row, so a tenant that registers the same display name under two
     * providers legitimately gets two entries with one label — that is what the deployed workspace shows.
     * Only the provider distinguishes them, and this is the case that catches it being lost in SQL, in the
     * DTO or on the wire, which no unit test can see.
     */
    @Test
    @DisplayName("two same-named model rows each carry their provider, and the agent list invents no discriminator")
    fun sameNamedModelRowsCarryTheirProvider() {
        val before = assertOk(getJson(OVERVIEW, tenantId = TENANT_ID))
        assertEquals(0, rankEntries(before, "topModels", SHARED_MODEL_NAME).size, "nothing of that name exists before the seed")

        jdbc.update(
            "INSERT INTO agent (id, tenant_id, name, creator, active) VALUES (?, ?, 'Dashboard Ranking Agent', 'admin', 1)",
            agentId,
            TENANT_ID,
        )
        jdbc.update(
            "INSERT INTO model_provider (id, tenant_id, type, name, creator) VALUES (?, ?, 'openai', 'Dashboard Provider One', 'admin')",
            providerOneId,
            TENANT_ID,
        )
        jdbc.update(
            "INSERT INTO model_provider (id, tenant_id, type, name, creator) VALUES (?, ?, 'openai', 'Dashboard Provider Two', 'admin')",
            providerTwoId,
            TENANT_ID,
        )
        jdbc.update(
            "INSERT INTO `model` (id, tenant_id, name, model_name, provider_id, model_type, active) VALUES (?, ?, 'Dashboard Shared Model', 'dashboard-shared-a', ?, 'chat', 1)",
            modelOneId,
            TENANT_ID,
            providerOneId,
        )
        jdbc.update(
            "INSERT INTO `model` (id, tenant_id, name, model_name, provider_id, model_type, active) VALUES (?, ?, 'Dashboard Shared Model', 'dashboard-shared-b', ?, 'chat', 1)",
            modelTwoId,
            TENANT_ID,
            providerTwoId,
        )
        insertModelStats(modelOneId, 710_000L)
        insertModelStats(modelTwoId, 720_000L)

        val after = assertOk(getJson(OVERVIEW, tenantId = TENANT_ID))
        val rows = rankEntries(after, "topModels", SHARED_MODEL_NAME)

        assertEquals(2, rows.size, "both model rows reach the ranking under the one display name")
        assertEquals(
            listOf("Dashboard Provider One", "Dashboard Provider Two"),
            rows.map { it["qualifier"].asText() }.sorted(),
            "each entry carries the provider that tells it from its twin",
        )
        assertEquals(
            listOf(720_000L, 710_000L),
            rows.map { it["tokens"].asLong() },
            "the list stays in the order the SQL gave",
        )

        // The agent dimension has no second label to carry. A non-empty qualifier here would mean the
        // one-argument ranking filled it out of another column.
        val agentRow = rankEntries(after, "topAgents", "Dashboard Ranking Agent").single()
        assertEquals("", agentRow["qualifier"].asText(), "the agent ranking answers an empty qualifier, not an invented one")
    }

    /**
     * Consumption attributed to one model row, at a size that tops the ranking.
     *
     * The statement orders by tokens DESC and the endpoint keeps five, so a seed that landed below the cut
     * would leave the assertions above counting an empty list and proving nothing.
     */
    private fun insertModelStats(
        modelId: Long,
        tokens: Long,
    ) {
        jdbc.update(
            "INSERT INTO token_stats (tenant_id, agent_id, session_id, chat_model_id, input_token, output_token, total_token, fee, ts) " +
                "VALUES (?, ?, ?, ?, ?, 0, ?, 0, ?)",
            TENANT_ID,
            agentId,
            sessionThree,
            modelId,
            tokens,
            tokens,
            LocalDateTime.now().format(TIMESTAMP),
        )
    }

    /** The entries of one ranking that carry a given display name. */
    private fun rankEntries(
        data: JsonNode,
        list: String,
        name: String,
    ): List<JsonNode> = data[list].filter { it["name"].asText() == name }

    private fun trendPoint(
        data: JsonNode,
        index: Int,
    ): JsonNode = data["trend"][index]

    /** One top-level numeric field of the delta between two reads. */
    private fun topDelta(
        before: JsonNode,
        after: JsonNode,
        field: String,
    ): Long = after[field].asLong() - before[field].asLong()

    /** One field of the delta between two reads, addressed as `block.field`. */
    private fun delta(
        before: JsonNode,
        after: JsonNode,
        block: String,
        field: String,
    ): Long = after[block][field].asLong() - before[block][field].asLong()

    private companion object {
        /** The workspace the admin token stands in, which is what the endpoint resolves. */
        const val TENANT_ID = 1L

        const val OVERVIEW = "/api/admin/dashboard/overview"

        const val TREND_POINTS = 14

        const val RANK_LIMIT = 5

        /** The display name both seeded model rows carry; only their provider differs. */
        const val SHARED_MODEL_NAME = "Dashboard Shared Model"

        val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}

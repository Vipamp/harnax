package com.agnetix.harnax.admin.it

import com.agnetix.harnax.mapper.TokenStatsMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Token statistics aggregation over real rows (AGENT-01).
 *
 * [TokenStatsIT] only proves the endpoints answer on an empty window, which is exactly the shape the
 * missing statements hid: a zeroed response looks like a working page. This class seeds consumption
 * rows and asserts the numbers survive the SQL, the camelCase labels and the joins.
 *
 * Everything sits in March 2020 on purpose. The shared container keeps every row this JVM writes, and
 * the sibling class asserts zeros over the default last-7-days window.
 */
class TokenStatsAggregationIT : BaseAdminIT() {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var tokenStatsMapper: TokenStatsMapper

    private val agentId = 930_001L
    private val modelId = 930_002L
    private val providerId = 930_003L
    private val sessionId = "token-stats-it-session"

    private val window = "startTime=2020-03-05+00:00:00&endTime=2020-03-05+23:59:59"
    private val from = "2020-03-05 00:00:00"
    private val to = "2020-03-05 23:59:59"
    private val quarter = "startTime=2020-01-01+00:00:00&endTime=2020-03-31+23:59:59"

    @BeforeEach
    fun seedRows() {
        clearRows()
        jdbc.update(
            "INSERT INTO model_provider (id, tenant_id, type, name, creator) VALUES (?, 1, 'openai', 'Stats Provider', 'admin')",
            providerId,
        )
        jdbc.update(
            "INSERT INTO `model` (id, tenant_id, name, model_name, provider_id, model_type) VALUES (?, 1, 'Stats Model', 'stats-model', ?, 'chat')",
            modelId,
            providerId,
        )
        jdbc.update(
            "INSERT INTO agent (id, tenant_id, name, creator, active) VALUES (?, 1, 'Stats Agent', 'admin', 1)",
            agentId,
        )
        jdbc.update(
            "INSERT INTO session (tenant_id, title, session_id, agent_id, creator, active) VALUES (1, 'Stats Session', ?, ?, 'admin', 1)",
            sessionId,
            agentId,
        )
        insertStats("2020-03-05 10:15:00", 100L, 50L)
        insertStats("2020-03-05 11:40:00", 200L, 70L)
    }

    @AfterEach
    fun clearRows() {
        jdbc.update("DELETE FROM token_stats WHERE session_id = ?", sessionId)
        jdbc.update("DELETE FROM session WHERE session_id = ?", sessionId)
        jdbc.update("DELETE FROM agent WHERE id = ?", agentId)
        jdbc.update("DELETE FROM `model` WHERE id = ?", modelId)
        jdbc.update("DELETE FROM model_provider WHERE id = ?", providerId)
    }

    private fun insertStats(
        ts: String,
        input: Long,
        output: Long,
    ) {
        jdbc.update(
            "INSERT INTO token_stats (agent_id, session_id, chat_model_id, input_token, output_token, total_token, fee, ts) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            agentId,
            sessionId,
            modelId,
            input,
            output,
            input + output,
            3L,
            ts,
        )
    }

    @Test
    @DisplayName("aggregation sums the real rows and carries the dimension names back")
    fun aggregationSumsSeededRows() {
        val data = assertOk(getJson("/api/admin/token-stats/aggregation?$window"))
        val overall = data["overall"]
        assertEquals(300L, overall["totalInputToken"].asLong())
        assertEquals(120L, overall["totalOutputToken"].asLong())
        assertEquals(420L, overall["grandTotalToken"].asLong())
        assertEquals(6L, overall["totalFee"].asLong())
        assertEquals(1L, overall["agentCount"].asLong())
        assertEquals(1L, overall["sessionCount"].asLong())
        assertEquals(1L, overall["modelCount"].asLong())

        val modelStats = data["modelStats"].find { it["modelId"].asLong() == modelId }
        assertNotNull(modelStats, "seeded model should show up: ${data["modelStats"]}")
        assertEquals("Stats Model", modelStats["modelName"].asText())
        assertEquals("Stats Provider", modelStats["providerName"].asText())
        assertEquals(420L, modelStats["grandTotalToken"].asLong())

        val agentStats = data["agentStats"].find { it["agentId"].asLong() == agentId }
        assertNotNull(agentStats, "seeded agent should show up: ${data["agentStats"]}")
        assertEquals("Stats Agent", agentStats["agentName"].asText())

        val sessionStats = data["sessionStats"].find { it["sessionId"].asText() == sessionId }
        assertNotNull(sessionStats, "seeded session should show up: ${data["sessionStats"]}")
        assertEquals("Stats Session", sessionStats["sessionTitle"].asText())
    }

    @Test
    @DisplayName("the day series lands the consumption on that one day point")
    fun daySeriesPlacesConsumptionOnItsDay() {
        val points = series("/api/admin/token-stats/time-series?granularity=day&$window")
        // One day requested, so exactly one point — and it has to carry the total rather than 0.
        // A timePoint that does not come back as a LocalDateTime is dropped by the service and every
        // point reads 0, which is the failure this assertion exists to catch.
        assertEquals(1, points.size(), "expected a single day point")
        assertEquals("2020-03-05 00:00:00", points[0]["timePoint"].asText())
        assertEquals(420L, points[0]["grandTotalToken"].asLong())
        assertEquals(300L, points[0]["totalInputToken"].asLong())
    }

    @Test
    @DisplayName("the hour series truncates to the hour")
    fun hourSeriesTruncatesToTheHour() {
        val byHour = series("/api/admin/token-stats/time-series?granularity=hour&$window")
            .associate { it["timePoint"].asText() to it["grandTotalToken"].asLong() }
        // A full day of hourly points, with the two writes landing in their own hours.
        assertEquals(24, byHour.size)
        assertEquals(150L, byHour["2020-03-05 10:00:00"])
        assertEquals(270L, byHour["2020-03-05 11:00:00"])
        assertEquals(0L, byHour["2020-03-05 12:00:00"])
    }

    @Test
    @DisplayName("each of the three dimension series carries its own dimension identity")
    fun dimensionSeriesCarryTheirDimension() {
        assertEquals(420L, singlePoint("/time-series/model", "dimensionId", modelId.toString())["grandTotalToken"].asLong())
        assertEquals(
            "Stats Model",
            singlePoint("/time-series/model", "dimensionName", "Stats Model")["dimensionName"].asText(),
        )
        assertEquals(420L, singlePoint("/time-series/agent", "dimensionId", agentId.toString())["grandTotalToken"].asLong())
        assertEquals(420L, singlePoint("/time-series/session", "dimensionId", sessionId)["grandTotalToken"].asLong())
    }

    @Test
    @DisplayName("the week series lands the consumption on the Monday it belongs to")
    fun weekSeriesPlacesConsumptionOnItsMonday() {
        // 2020-03-05 is a Thursday, so the window belongs to the week starting Monday 2020-03-02.
        val points = series("/api/admin/token-stats/time-series?granularity=week&$window")
        assertEquals(1, points.size(), "expected a single week point")
        assertEquals("2020-03-02 00:00:00", points[0]["timePoint"].asText())
        assertEquals(420L, points[0]["grandTotalToken"].asLong())
    }

    @Test
    @DisplayName("the month series lands the consumption on its month and zero-fills the other months")
    fun monthSeriesPlacesConsumptionOnItsMonth() {
        val byMonth = series("/api/admin/token-stats/time-series?granularity=month&$quarter")
            .associate { it["timePoint"].asText() to it["grandTotalToken"].asLong() }
        // The month key used to be formatted `yyyy-MM` while rows keyed on the full timestamp, so every
        // month point came back zero-filled and the count looked fine.
        assertEquals(3, byMonth.size)
        assertEquals(420L, byMonth["2020-03-01 00:00:00"])
        assertEquals(0L, byMonth["2020-01-01 00:00:00"])
        assertEquals(0L, byMonth["2020-02-01 00:00:00"])
    }

    @Test
    @DisplayName("all three dimensions support week and month")
    fun dimensionSeriesAnswerWeekAndMonth() {
        val dimensions = listOf(
            "/time-series/model" to "Stats Model",
            "/time-series/agent" to "Stats Agent",
            "/time-series/session" to "Stats Session",
        )
        for ((path, dimensionName) in dimensions) {
            // Filtering by name is also the assertion that a zero-filled bucket kept its dimension
            val weeks = series("/api/admin/token-stats$path?granularity=week&$window")
                .filter { it["dimensionName"].asText() == dimensionName }
            assertEquals(1, weeks.size, "$path week bucket count")
            assertEquals("2020-03-02 00:00:00", weeks[0]["timePoint"].asText(), "$path week bucket")
            assertEquals(420L, weeks[0]["grandTotalToken"].asLong(), "$path week total")

            val months = series("/api/admin/token-stats$path?granularity=month&$quarter")
                .filter { it["dimensionName"].asText() == dimensionName }
            assertEquals(3, months.size, "$path month bucket count")
            assertEquals(0L, months.first()["grandTotalToken"].asLong(), "$path January stays empty")
            assertEquals(420L, months.last()["grandTotalToken"].asLong(), "$path month total")
            assertEquals("2020-03-01 00:00:00", months.last()["timePoint"].asText(), "$path month bucket")
        }
    }

    @Test
    @DisplayName("the per-hour dimension statements no endpoint reaches run on the real database and bucket correctly")
    fun statementsNoEndpointReachesStillAggregate() {
        // The page only asks the dimension endpoints for one granularity at a time, and the hour rows would
        // add 24 points per dimension to the HTTP tests above. These three statements would otherwise stay
        // unexecuted, so they run against the real database here.
        assertBucket("model/hour", tokenStatsMapper.getModelTimeSeriesByHour(from, to), LocalDateTime.of(2020, 3, 5, 10, 0), 2)
        assertBucket("agent/hour", tokenStatsMapper.getAgentTimeSeriesByHour(from, to), LocalDateTime.of(2020, 3, 5, 10, 0), 2)
        assertBucket("session/hour", tokenStatsMapper.getSessionTimeSeriesByHour(from, to), LocalDateTime.of(2020, 3, 5, 10, 0), 2)
    }

    private fun singlePoint(
        path: String,
        field: String,
        value: String,
    ) = series("/api/admin/token-stats$path?granularity=day&$window")
        .filter { it[field].asText() == value }
        .also { assertEquals(1, it.size, "one $field=$value point expected in ${field}series") }
        .first()

    private fun series(path: String) = assertOk(getJson(path))["timeSeriesData"]
        .also { assertNotNull(it, "timeSeriesData missing for $path") }

    private fun assertBucket(
        label: String,
        rows: MutableList<MutableMap<String?, Any?>?>?,
        expectedFirstPoint: LocalDateTime,
        expectedPoints: Int = 1,
    ) {
        val series = rows.orEmpty().filterNotNull()
        assertEquals(expectedPoints, series.size, "$label bucket count")
        val raw = series.first()["timePoint"]
        val timePoint = raw as? LocalDateTime
        assertNotNull(
            timePoint,
            "$label timePoint must come back as a LocalDateTime — the service indexes by that type and drops " +
                "anything else, which is the all-zeros shape this class exists to catch (got $raw)",
        )
        assertEquals(expectedFirstPoint, timePoint, "$label first bucket")
        assertEquals(420L, series.sumOf { (it["grandTotalToken"] as Number).toLong() }, "$label grand total")
    }
}

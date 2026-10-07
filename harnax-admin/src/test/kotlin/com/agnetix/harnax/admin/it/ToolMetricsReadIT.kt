package com.agnetix.harnax.admin.it

import com.agnetix.harnax.admin.service.ToolInvocationRollupService
import com.agnetix.harnax.entity.ToolInvocationLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The three read endpoints over a seeded pair of tables.
 *
 * Numbers are asserted from the JSON and not from the mapper because the contract the page consumes is the
 * JSON: `successRate`, which operator the P95 answers with, and which column `lastSeenAt` came from are all
 * made in the service.
 *
 * Fixture days are relative to `LocalDate.now()` rather than an absolute month: the rollup's retention sweep
 * runs in `seedAggregate` below, an absolute window far enough back to be interesting is also far enough
 * back to be pruned, and then the tenant and window assertions would be reading an empty table.
 */
class ToolMetricsReadIT : BaseAdminIT() {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var rollup: ToolInvocationRollupService

    private fun call(
        at: LocalDateTime,
        tenantId: Long,
        kind: String,
        toolName: String,
        outcome: String,
        durationMs: Long,
        mcpId: Long? = null,
        cliId: Long? = null,
        session: String = SESSION,
    ) {
        jdbc.update(
            """
                INSERT INTO tool_invocation_log
                (tenant_id, agent_id, session_id, user_id, kind, tool_name, mcp_id, cli_id, outcome,
                 duration_ms, start_time, end_time, ts)
                VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            tenantId,
            session,
            1L,
            kind,
            toolName,
            mcpId,
            cliId,
            outcome,
            durationMs,
            at.minusSeconds(1L),
            at,
            at,
        )
    }

    private fun data(
        path: String,
        tenantId: Long? = null,
    ): JsonNode {
        val envelope = getJson(path, tenantId)
        assertEquals(200, envelope["code"].asInt(), envelope.toString())
        // A 200 envelope with no data node is a contract failure of its own, and naming the path beats a
        // Kotlin null-pointer in the middle of an unrelated assertion.
        return envelope["data"] ?: throw AssertionError("$path answered no data node: $envelope")
    }

    private fun names(rows: JsonNode): List<String> = rows.map { it["subjectKey"].asString() }

    @BeforeEach
    fun seedRows() {
        jdbc.update("DELETE FROM tool_invocation_stats")
        jdbc.update("DELETE FROM tool_invocation_log")

        val day2 = LocalDate.now().minusDays(2L)
        val day1 = LocalDate.now().minusDays(1L)
        // Four subjects of this tenant across two days: 50, 400, 4000 and 120 ms of send_email, one MCP tool
        // at 900 ms, one CLI package over 30 s, and the four terminal outcomes all present.
        call(day2.atTime(10, 0), TENANT_ID, ToolInvocationLog.KIND_BUILTIN, "send_email", "SUCCESS", 50L)
        call(day2.atTime(10, 1), TENANT_ID, ToolInvocationLog.KIND_BUILTIN, "send_email", "ERROR", 400L)
        call(day2.atTime(10, 2), TENANT_ID, ToolInvocationLog.KIND_BUILTIN, "send_email", "DENIED", 4_000L)
        call(day2.atTime(10, 3), TENANT_ID, ToolInvocationLog.KIND_MCP, "fetch_url", "SUCCESS", 900L, mcpId = 77L)
        call(day2.atTime(10, 4), TENANT_ID, ToolInvocationLog.KIND_CLI, "gh", "SUCCESS", 40_000L, cliId = 88L)
        call(day1.atTime(9, 0), TENANT_ID, ToolInvocationLog.KIND_BUILTIN, "list_files", "INTERRUPTED", 300L)
        call(day1.atTime(10, 0), TENANT_ID, ToolInvocationLog.KIND_BUILTIN, "send_email", "SUCCESS", 120L)
        // Tomorrow: no window that ends now includes it. This is the row that makes the detail reads' upper
        // bound falsifiable — take `ts <= to` out and every page count below grows by one.
        call(LocalDate.now().plusDays(1L).atTime(9, 0), TENANT_ID, ToolInvocationLog.KIND_BUILTIN, "send_email", "SUCCESS", 80L)
        // A neighbour's two rows, and only its own rows. Its session id differs because a session belongs to
        // one tenant, and sharing the string would let a missing tenant predicate look like a correct answer.
        call(day2.atTime(11, 0), NEIGHBOUR_TENANT_ID, ToolInvocationLog.KIND_BUILTIN, "leaked-a", "SUCCESS", 10L, session = "neighbour-session")
        call(day2.atTime(11, 1), NEIGHBOUR_TENANT_ID, ToolInvocationLog.KIND_BUILTIN, "leaked-b", "ERROR", 20L, session = "neighbour-session")

        rollup.rollUp()
    }

    @Test
    @DisplayName("summary answers per subject with the rates the page shows")
    fun summaryAnswersPerSubject() {
        val body = data("/api/admin/tool-metrics/summary?days=7")
        val rows = body["rows"]
        val emails = rows.first { it["toolName"].asString() == "send_email" }
        assertEquals(4, emails["calls"].asInt())
        assertEquals(2, emails["successes"].asInt())
        assertEquals(4.0 / 7.0, body["successRate"].asDouble(), 1e-9)
        assertEquals(0.5, emails["successRate"].asDouble(), 1e-9)
        // (50+400+4000+120)/4 = 1142.5 -> 1142: the mean is integer floor of sum over calls
        assertEquals(1142L, emails["avgDurationMs"].asLong())
        assertEquals("builtin", emails["kind"].asString())
        // The tool dimension's key is the tool name itself, and a builtin call has no subject id to carry.
        assertEquals("send_email", emails["subjectKey"].asString())
        assertFalse(emails.has("subjectId"), emails.toString())
        assertEquals("$DAY1 00:00:00", emails["lastSeenAt"].asString())

        // The card block reads the daily aggregate on its own, so its counts cover every subject.
        assertEquals(7, body["totalCalls"].asInt())
        assertEquals(4, body["totalSuccesses"].asInt())
        assertEquals(3, body["failingCalls"].asInt())
        assertEquals("tool", body["groupBy"].asString())
        // The four terminal outcomes partition the calls, so the card's two numbers have to add back up.
        assertEquals(body["totalCalls"].asInt(), body["totalSuccesses"].asInt() + body["failingCalls"].asInt())
        // Rows tie at one call each, so only the unique maximum is a safe ordering claim.
        assertEquals("send_email", rows[0]["toolName"].asString())
    }

    @Test
    @DisplayName("p95 is the bucket the accumulated count reaches 95 percent in")
    fun p95LandsInABucket() {
        val body = data("/api/admin/tool-metrics/summary?days=7")
        // send_email: 50 -> <=100ms, 400 and 120 -> <=500ms, 4000 -> <=10s. 4 calls need ceil(3.8)=4, which
        // the accumulation reaches in the 10s bucket.
        val emails = body["rows"].first { it["toolName"].asString() == "send_email" }
        assertEquals("<=", emails["p95Operator"].asString())
        assertEquals(10_000L, emails["p95Ms"].asLong())
        // gh is the only call over 30 s, so its own p95 is the open-ended bucket.
        val gh = body["rows"].first { it["toolName"].asString() == "gh" }
        assertEquals(">", gh["p95Operator"].asString())
        assertEquals(30_000L, gh["p95Ms"].asLong())
        // Window level: 7 calls need ceil(6.65)=7, and only the open-ended bucket gets there.
        assertEquals(">", body["p95Operator"].asString())
        assertEquals(30_000L, body["p95Ms"].asLong())
    }

    @Test
    @DisplayName("one tenant's reads never answer another's rows")
    fun tenantIsConvergedInBothDirections() {
        val mine = data("/api/admin/tool-metrics/summary?days=7")
        assertEquals(7, mine["totalCalls"].asInt())
        assertEquals(setOf("send_email", "fetch_url", "gh", "list_files"), names(mine["rows"]).toSet())

        val theirs = data("/api/admin/tool-metrics/summary?days=7", NEIGHBOUR_TENANT_ID)
        assertEquals(2, theirs["totalCalls"].asInt())
        assertEquals(setOf("leaked-a", "leaked-b"), names(theirs["rows"]).toSet())

        // The window's upper bound and the paging seam: total stays the full count while records is one page,
        // and ts DESC puts the newest row first.
        val page = data("/api/admin/tool-metrics/invocations?days=7&pageNum=1&pageSize=2")
        assertEquals(7, page["total"].asInt())
        assertEquals(2, page["records"].size())
        assertEquals("$DAY1 10:00:00", page["records"][0]["ts"].asString())
        assertEquals("$DAY1 09:00:00", page["records"][1]["ts"].asString())
        assertEquals(120L, page["records"][0]["durationMs"].asLong())

        val theirPage = data("/api/admin/tool-metrics/invocations?days=7", NEIGHBOUR_TENANT_ID)
        assertEquals(2, theirPage["total"].asInt())
        assertTrue(theirPage["records"].none { it["toolName"].asString() == "send_email" }, theirPage.toString())
    }

    @Test
    @DisplayName("groupBy agent and session read the detail table and carry only the id a drill-down needs")
    fun detailDimensionsReadTheDetailTable() {
        val sessions = data("/api/admin/tool-metrics/summary?days=7&groupBy=session")
        val session = sessions["rows"].single { it["subjectKey"].asString() == SESSION }
        assertEquals(7, session["calls"].asInt())
        // A session id is not a row id, so there is nothing to hand back for the drill-down besides the key.
        assertFalse(session.has("subjectId"), session.toString())
        // The detail path carries the exact instant; the aggregate path can only carry a day.
        assertEquals("$DAY1 10:00:00", session["lastSeenAt"].asString())

        val agents = data("/api/admin/tool-metrics/summary?days=7&groupBy=agent")
        val agent = agents["rows"].single()
        assertEquals("1", agent["subjectKey"].asString())
        assertEquals(1L, agent["subjectId"].asLong())
        assertEquals(7, agent["calls"].asInt())

        // An unrecognised dimension answers the default and says so in the field the page renders from.
        assertEquals("tool", data("/api/admin/tool-metrics/summary?days=7&groupBy=client")["groupBy"].asString())

        // The eight optional predicates are what the drill-down drawer is built from.
        val errors = data("/api/admin/tool-metrics/invocations?days=7&outcome=ERROR")
        assertEquals(1, errors["total"].asInt())
        assertEquals("send_email", errors["records"][0]["toolName"].asString())
    }

    @Test
    @DisplayName("time-series buckets the days and zero-fills the empty ones")
    fun timeSeriesZeroFills() {
        val points = data("/api/admin/tool-metrics/time-series?days=7")["points"]
        val days = points.map { it["timePoint"].asString().substring(0, 10) }.distinct()
        // A 7-day window answers 7 buckets, not the two that have rows and not eight.
        assertEquals(7, days.size)
        // Every subject is filled into every bucket, so a quiet day draws a line instead of a jump.
        assertEquals(28, points.size())
        val quiet = points.filter { it["timePoint"].asString().startsWith(QUIET_DAY) }
        assertEquals(4, quiet.size)
        assertTrue(quiet.all { it["calls"].asInt() == 0 }, quiet.toString())

        val busy = points.first { it["timePoint"].asString().startsWith(DAY2) && it["dimensionName"].asString() == "send_email" }
        assertEquals(3, busy["calls"].asInt())
        // (50+400+4000)/3 = 1483.33 -> 1483
        assertEquals(1483L, busy["avgDurationMs"].asLong())
        assertEquals("$DAY2 00:00:00", busy["timePoint"].asString())
        assertFalse(busy.has("dimensionId"), busy.toString())
        val mcp = points.first { it["dimensionName"].asString() == "fetch_url" }
        assertEquals(77L, mcp["dimensionId"].asLong())
    }

    private companion object {
        const val TENANT_ID = 1L
        const val NEIGHBOUR_TENANT_ID = 950_500L
        const val SESSION = "metrics-session"
        val DAY2: String = LocalDate.now().minusDays(2L).toString()
        val DAY1: String = LocalDate.now().minusDays(1L).toString()
        val QUIET_DAY: String = LocalDate.now().minusDays(5L).toString()
    }
}

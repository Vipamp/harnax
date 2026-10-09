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
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The three read endpoints over a seeded pair of tables.
 *
 * Numbers are asserted from the JSON and not from the mapper because the contract the page consumes is the
 * JSON: `successRate`, which operator the P95 answers with, and which column `lastSeenAt` came from are all
 * made in the service.
 *
 * Fixture days are relative to `LocalDate.now()` rather than an absolute month: [seedRows] rolls the rows up
 * and the retention sweep runs with it, so an absolute window far enough back to be interesting is also far
 * enough back to be pruned, and then the tenant and window assertions would be reading an empty table.
 *
 * The same relativity is why [SEVEN_DAYS] names its bounds as days rather than as a length: the range a
 * request sends is what the server clamps, and the range it echoes back in `from` / `to` is where a clamp is
 * observable — the log line is not an assertion.
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
        agentId: Long = 1L,
    ) {
        jdbc.update(
            """
                INSERT INTO tool_invocation_log
                (tenant_id, agent_id, session_id, user_id, kind, tool_name, mcp_id, cli_id, outcome,
                 duration_ms, start_time, end_time, ts)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            tenantId,
            agentId,
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

    /**
     * An hour as the picker sends it, with the space left raw: the request path is treated as a URI template
     * and encoded once, so a hand-written `%20` would arrive as the literal text `%20` and count as unparseable.
     */
    private fun hour(
        value: LocalDateTime,
    ): String = value.format(HOUR_PARAM)

    /**
     * The registration rows this class seeds, under ids no seed data uses. Cleared before every test because
     * the container is shared and a rerun would otherwise answer a duplicate key, and again when a test is
     * done because another class reads these tables expecting only what it inserted itself — an agent row
     * left behind here would show up in an agent list count.
     */
    private fun clearOwnedRegistrations() {
        jdbc.update("DELETE FROM mcp_server WHERE id = 77")
        jdbc.update("DELETE FROM cli WHERE id = 55")
        jdbc.update("DELETE FROM agent WHERE id = 900001")
        jdbc.update("DELETE FROM session WHERE session_id = 's-dup'")
    }

    @BeforeEach
    fun seedRows() {
        clearOwnedRegistrations()
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
        val body = data("/api/admin/tool-metrics/summary?$SEVEN_DAYS")
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
        // The newest hour that has a row, not its day: read through the aggregate this still answers 10:00.
        assertEquals("$DAY1 10:00:00", emails["lastSeenAt"].asString())

        // The card block reads the hourly aggregate on its own, so its counts cover every subject.
        assertEquals(7, body["totalCalls"].asInt())
        assertEquals(4, body["totalSuccesses"].asInt())
        assertEquals(3, body["failingCalls"].asInt())
        assertEquals("tool", body["groupBy"].asString())
        // The bounds come back as the request named them, now as hours: a bare day is read as its 00:00
        // opening. A response that swapped them, or widened them to the default span, would otherwise only
        // show up as a chart drawn over the wrong days.
        assertEquals("${LocalDate.now().minusDays(6L)} 00:00:00", body["from"].asString())
        assertEquals("${LocalDate.now()} 00:00:00", body["to"].asString())
        // The four terminal outcomes partition the calls, so the card's two numbers have to add back up.
        assertEquals(body["totalCalls"].asInt(), body["totalSuccesses"].asInt() + body["failingCalls"].asInt())
        // Rows tie at one call each, so only the unique maximum is a safe ordering claim.
        assertEquals("send_email", rows[0]["toolName"].asString())
    }

    @Test
    @DisplayName("p95 is the bucket the accumulated count reaches 95 percent in")
    fun p95LandsInABucket() {
        val body = data("/api/admin/tool-metrics/summary?$SEVEN_DAYS")
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
        val mine = data("/api/admin/tool-metrics/summary?$SEVEN_DAYS")
        assertEquals(7, mine["totalCalls"].asInt())
        assertEquals(setOf("send_email", "fetch_url", "gh", "list_files"), names(mine["rows"]).toSet())

        val theirs = data("/api/admin/tool-metrics/summary?$SEVEN_DAYS", NEIGHBOUR_TENANT_ID)
        assertEquals(2, theirs["totalCalls"].asInt())
        assertEquals(setOf("leaked-a", "leaked-b"), names(theirs["rows"]).toSet())

        // The window's upper bound and the paging seam: total stays the full count while records is one page,
        // and ts DESC puts the newest row first.
        val page = data("/api/admin/tool-metrics/invocations?$SEVEN_DAYS&pageNum=1&pageSize=2")
        assertEquals(7, page["total"].asInt())
        assertEquals(2, page["records"].size())
        assertEquals("$DAY1 10:00:00", page["records"][0]["ts"].asString())
        assertEquals("$DAY1 09:00:00", page["records"][1]["ts"].asString())
        assertEquals(120L, page["records"][0]["durationMs"].asLong())

        val theirPage = data("/api/admin/tool-metrics/invocations?$SEVEN_DAYS", NEIGHBOUR_TENANT_ID)
        assertEquals(2, theirPage["total"].asInt())
        assertTrue(theirPage["records"].none { it["toolName"].asString() == "send_email" }, theirPage.toString())
    }

    @Test
    @DisplayName("groupBy agent and session read the detail table and carry only the id a drill-down needs")
    fun detailDimensionsReadTheDetailTable() {
        val sessions = data("/api/admin/tool-metrics/summary?$SEVEN_DAYS&groupBy=session")
        val session = sessions["rows"].single { it["subjectKey"].asString() == SESSION }
        assertEquals(7, session["calls"].asInt())
        // A session id is not a row id, so there is nothing to hand back for the drill-down besides the key.
        assertFalse(session.has("subjectId"), session.toString())
        // The detail path carries the call's own instant; the aggregate path carries the hour it fell in.
        assertEquals("$DAY1 10:00:00", session["lastSeenAt"].asString())

        val agents = data("/api/admin/tool-metrics/summary?$SEVEN_DAYS&groupBy=agent")
        val agent = agents["rows"].single()
        assertEquals("1", agent["subjectKey"].asString())
        assertEquals(1L, agent["subjectId"].asLong())
        assertEquals(7, agent["calls"].asInt())

        // An unrecognised dimension answers the default and says so in the field the page renders from.
        assertEquals("tool", data("/api/admin/tool-metrics/summary?$SEVEN_DAYS&groupBy=client")["groupBy"].asString())

        // The eight optional predicates are what the drill-down drawer is built from.
        val errors = data("/api/admin/tool-metrics/invocations?$SEVEN_DAYS&outcome=ERROR")
        assertEquals(1, errors["total"].asInt())
        assertEquals("send_email", errors["records"][0]["toolName"].asString())
    }

    @Test
    @DisplayName("groupBy mcp folds one server's several tools into a single row")
    fun mcpDimensionFoldsOneServerIntoOneRow() {
        val hour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        // 77 already carries the fixture's fetch_url, so these two names join it into a three-call row; 88
        // runs a tool name 77 also runs and has to stay its own row.
        call(hour, TENANT_ID, ToolInvocationLog.KIND_MCP, "search_nodes", "SUCCESS", 80L, mcpId = 77L)
        call(hour.plusMinutes(20L), TENANT_ID, ToolInvocationLog.KIND_MCP, "fetch_doc", "SUCCESS", 400L, mcpId = 77L)
        call(hour.plusMinutes(30L), TENANT_ID, ToolInvocationLog.KIND_MCP, "search_nodes", "ERROR", 900L, mcpId = 88L)
        rollup.rollUp()

        val body = data("/api/admin/tool-metrics/summary?kind=mcp&groupBy=mcp")
        val rows = body["rows"]
        assertEquals(2, rows.size())
        assertEquals(listOf(77L, 88L), rows.map { it["subjectId"].asLong() })
        assertEquals("mcp", body["groupBy"].asString())
        // A row is one server now, so no single tool name belongs on it.
        assertEquals("", rows[0]["toolName"].asString())
        assertEquals(3L, rows[0]["calls"].asLong())
        assertEquals(3L, rows[0]["successes"].asLong())
        // The buckets add over hours: 80 goes in <=100ms, 400 in <=500ms and 900 in <=2s, and the 3 calls
        // need ceil(0.95*3)=3, first reached in the 2s bucket.
        assertEquals("<=", rows[0]["p95Operator"].asString())
        assertEquals(2_000L, rows[0]["p95Ms"].asLong())
        assertEquals(460L, rows[0]["avgDurationMs"].asLong())
        assertEquals(1L, rows[1]["calls"].asLong())
        assertEquals(1L, rows[1]["errors"].asLong())
        // The cards answer the same filter without grouping, so they add the two rows up.
        assertEquals(4L, body["totalCalls"].asLong())
    }

    @Test
    @DisplayName("groupBy cli folds one package's several commands into a single row")
    fun cliDimensionFoldsOnePackageIntoOneRow() {
        val hour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        call(hour, TENANT_ID, ToolInvocationLog.KIND_CLI, "gh-pr", "SUCCESS", 60L, cliId = 88L)
        call(hour.plusMinutes(10L), TENANT_ID, ToolInvocationLog.KIND_CLI, "gh-issue", "DENIED", 70L, cliId = 88L)
        call(hour.plusMinutes(20L), TENANT_ID, ToolInvocationLog.KIND_CLI, "kubectl", "SUCCESS", 500L, cliId = 99L)
        rollup.rollUp()

        val rows = data("/api/admin/tool-metrics/summary?kind=cli&groupBy=cli")["rows"]
        // 88 is the fixture's own 40-second gh plus these two; 99 stays separate on its own id.
        assertEquals(listOf(88L, 99L), rows.map { it["subjectId"].asLong() })
        assertEquals(3L, rows[0]["calls"].asLong())
        assertEquals(1L, rows[0]["denials"].asLong())
        assertEquals(1L, rows[1]["calls"].asLong())
    }

    @Test
    @DisplayName("the mcp and cli dimensions pin their own kind when the caller names none")
    fun aggregateDimensionsPinTheirOwnKind() {
        // The two aggregate dimensions ARE an origin bucket, so naming the dimension is enough to bound the
        // row set. Without that pin the server list here answers three rows: the server, the CLI package, and
        // a phantom one for the builtin bucket — every builtin call shares subject_id 0, so it comes back
        // named `0` and its drill-down lists the tenant's builtins under a heading that says MCP.
        val mcpRows = data("/api/admin/tool-metrics/summary?$SEVEN_DAYS&groupBy=mcp")["rows"]
        assertEquals(1, mcpRows.size(), mcpRows.toString())
        assertEquals("mcp", mcpRows[0]["kind"].asString())
        assertEquals("77", mcpRows[0]["subjectKey"].asString())

        val cliRows = data("/api/admin/tool-metrics/summary?$SEVEN_DAYS&groupBy=cli")["rows"]
        assertEquals(1, cliRows.size(), cliRows.toString())
        assertEquals("cli", cliRows[0]["kind"].asString())
        assertEquals("88", cliRows[0]["subjectKey"].asString())

        // The cards take the same bucket: a caller that names a foreign kind beside these dimensions still gets
        // a total that adds up with the rows under it, not a card counting builtins beside a table of servers.
        val crossed = data("/api/admin/tool-metrics/summary?$SEVEN_DAYS&groupBy=mcp&kind=builtin")
        assertEquals(1, crossed["rows"].size(), crossed.toString())
        assertEquals(crossed["rows"][0]["calls"].asLong(), crossed["totalCalls"].asLong())
    }

    @Test
    @DisplayName("every dimension names its subject and falls back to the key when nothing is registered")
    fun everyDimensionResolvesAName() {
        val hour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        // Every id here is named rather than left to the auto-increment: the name is matched on
        // `id = subject_id`, and the sequence's next value is not a fact this test may assume. No agent row is
        // seeded by the migrations, so the registered one is inserted here.
        jdbc.update("INSERT INTO mcp_server (id, tenant_id, name, type) VALUES (77, ?, '文档检索服务', 'stdio')", TENANT_ID)
        jdbc.update("INSERT INTO cli (id, name) VALUES (55, 'lark-cli')")
        jdbc.update("INSERT INTO agent (id, tenant_id, name) VALUES (900001, ?, '取数助手')", TENANT_ID)
        call(hour, TENANT_ID, ToolInvocationLog.KIND_MCP, "search_nodes", "SUCCESS", 80L, mcpId = 77L)
        call(hour, TENANT_ID, ToolInvocationLog.KIND_CLI, "lark", "SUCCESS", 90L, cliId = 55L)
        call(hour, TENANT_ID, ToolInvocationLog.KIND_BUILTIN, "read_file", "SUCCESS", 20L, agentId = 900001L)
        call(hour, TENANT_ID, ToolInvocationLog.KIND_BUILTIN, "write_file", "SUCCESS", 15L, agentId = 900002L)
        rollup.rollUp()

        assertEquals("文档检索服务", data("/api/admin/tool-metrics/summary?kind=mcp&groupBy=mcp")["rows"][0]["subjectName"].asString())
        val cliRows = data("/api/admin/tool-metrics/summary?kind=cli&groupBy=cli")["rows"]
        assertEquals("lark-cli", cliRows.first { it["subjectId"].asLong() == 55L }["subjectName"].asString())
        // The mcp and cli keys are the ids themselves, so a page that keys its rows on one stays unique.
        assertEquals("77", data("/api/admin/tool-metrics/summary?kind=mcp&groupBy=mcp")["rows"][0]["subjectKey"].asString())

        // The tool dimension names the tool and still says which server answered it.
        val toolRow = data("/api/admin/tool-metrics/summary?kind=mcp&groupBy=tool")["rows"][0]
        assertEquals("search_nodes", toolRow["subjectName"].asString())
        assertEquals("文档检索服务", toolRow["parentName"].asString())

        // The agent dimension reads the detail table through a primary-key join: the registered one answers
        // with its name, and the one with no row behind it falls back to its id. The unregistered id is this
        // class's own reserved band rather than the fixture's agent 1 — another class's agent row can land on
        // any id the sequence hands out, and 1 is the first one it hands out.
        val agents = data("/api/admin/tool-metrics/summary?groupBy=agent")["rows"]
        assertEquals("取数助手", agents.first { it["subjectKey"].asString() == "900001" }["subjectName"].asString())
        assertEquals("900002", agents.first { it["subjectKey"].asString() == "900002" }["subjectName"].asString())

        // The fixture's session has no `session` row, so its own id is the only name there is.
        val sessions = data("/api/admin/tool-metrics/summary?groupBy=session")["rows"]
        assertEquals(SESSION, sessions.first { it["subjectKey"].asString() == SESSION }["subjectName"].asString())

        // Registration removed: the name falls back to the key instead of an empty cell the page cannot read.
        jdbc.update("DELETE FROM mcp_server WHERE id = 77")
        val orphan = data("/api/admin/tool-metrics/summary?kind=mcp&groupBy=mcp")["rows"][0]
        assertEquals(orphan["subjectKey"].asString(), orphan["subjectName"].asString())
        clearOwnedRegistrations()
    }

    @Test
    @DisplayName("two session rows sharing one id name the row without doubling its counts")
    fun duplicateSessionRowsDoNotFanOut() {
        val hour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        call(hour, TENANT_ID, ToolInvocationLog.KIND_BUILTIN, "read_file", "SUCCESS", 10L, session = "s-dup")
        call(hour.plusMinutes(5L), TENANT_ID, ToolInvocationLog.KIND_BUILTIN, "read_file", "SUCCESS", 10L, session = "s-dup")
        // `session.session_id` is only documented as unique and carries no unique index, so one id really can
        // own several rows. The title is read by a scalar subquery that picks the newest row.
        jdbc.update("INSERT INTO session (tenant_id, session_id, title) VALUES (?, 's-dup', '标题甲')", TENANT_ID)
        jdbc.update("INSERT INTO session (tenant_id, session_id, title) VALUES (?, 's-dup', '标题乙')", TENANT_ID)
        rollup.rollUp()

        val row = data("/api/admin/tool-metrics/summary?groupBy=session")["rows"]
            .first { it["subjectKey"].asString() == "s-dup" }
        // Had the title been joined rather than subqueried, this group would be two rows of two calls each.
        assertEquals(2L, row["calls"].asLong())
        assertEquals(2L, row["successes"].asLong())
        assertEquals("标题乙", row["subjectName"].asString())

        // The records drawer reads its session name off the same id and the same newest row.
        val records = data("/api/admin/tool-metrics/invocations?sessionId=s-dup&pageSize=2")["records"]
        assertEquals(2, records.size())
        assertEquals("标题乙", records[0]["sessionName"].asString())
        // The fixture's other session has no `session` row, so no title comes back and the drawer falls to the
        // id it already holds rather than leaving the cell blank.
        val unregistered = data("/api/admin/tool-metrics/invocations?toolName=send_email&pageSize=1")["records"][0]
        // `non_null` serialisation drops the key rather than sending an empty one, and the drawer's `||` needs
        // exactly that absence to reach the id it already holds.
        assertFalse(unregistered.has("sessionName"), unregistered.toString())
        assertEquals(SESSION, unregistered["sessionId"].asString())
        clearOwnedRegistrations()
    }

    @Test
    @DisplayName("time-series buckets the days and zero-fills the empty ones")
    fun timeSeriesZeroFills() {
        val points = data("/api/admin/tool-metrics/time-series?$SEVEN_DAYS")["points"]
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

    @Test
    @DisplayName("an end the clock has not reached clamps to the current hour")
    fun futureEndClampsToTheCurrentHour() {
        val currentHour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        // Both bounds echo as whole hours. An echo of the requested instant instead would tell the page it
        // answered a range it never queried. The window is six hours wide and the fixture sits two days
        // back, so only the range is claimed here.
        val body = data(
            "/api/admin/tool-metrics/summary?start=${hour(currentHour.minusHours(6L))}&end=${hour(currentHour.plusHours(9L))}",
        )
        assertEquals(currentHour.minusHours(6L).format(HOUR_STAMP), body["from"].asString())
        assertEquals(currentHour.format(HOUR_STAMP), body["to"].asString())
    }

    @Test
    @DisplayName("start after end collapses onto the end hour, an unparseable pair falls back to the default span")
    fun invertedAndUnparseableWindows() {
        val currentHour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        // The shape a hand-edited URL arrives in. Collapsing beats reversing: a reversed range answers an
        // empty table while still claiming a window.
        val collapsed = data(
            "/api/admin/tool-metrics/summary?start=${hour(currentHour.plusHours(3L))}&end=${hour(currentHour)}",
        )
        assertEquals(collapsed["to"].asString(), collapsed["from"].asString())

        // Neither value parses, which is a bug on the page rather than in the data: the default span answers,
        // so a broken picker still shows a chart instead of an error card. The fixture sits inside that span.
        val defaulted = data("/api/admin/tool-metrics/summary?start=also-not-an-hour&end=not-a-day")
        val from = LocalDateTime.parse(defaulted["from"].asString(), HOUR_STAMP)
        val to = LocalDateTime.parse(defaulted["to"].asString(), HOUR_STAMP)
        assertEquals(719L, ChronoUnit.HOURS.between(from, to))
        assertEquals(7, defaulted["totalCalls"].asInt())
    }

    @Test
    @DisplayName("a span wider than the tables can answer keeps its end and pushes its start forward")
    fun wideWindowPushesTheStartForward() {
        val currentHour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        val wide = data("/api/admin/tool-metrics/summary?start=${hour(currentHour.minusYears(2L))}&end=${hour(currentHour)}")
        // Ten years is the ceiling, so the start lands on the 8760th hour back rather than the request being
        // refused — refusing would blank the whole card row over a range no reader can ask.
        assertEquals(currentHour.minusHours(8759L).format(HOUR_STAMP), wide["from"].asString())
        assertEquals(currentHour.format(HOUR_STAMP), wide["to"].asString())
        assertEquals(7, wide["totalCalls"].asInt())
    }

    @Test
    @DisplayName("the trend bucket follows the span on both sides of each boundary")
    fun granularityFollowsTheSpan() {
        val currentHour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        fun granularityOf(hours: Long): String = data(
            "/api/admin/tool-metrics/time-series?start=${hour(currentHour.minusHours(hours - 1L))}&end=${hour(currentHour)}",
        )["granularity"].asString()

        // Both sides of the 48-hour cut, and both sides of the 92-day one. The boundary itself belongs to the
        // finer bucket, because the page asks for a readable number of points, not for a range of days.
        assertEquals("hour", granularityOf(48L))
        assertEquals("day", granularityOf(49L))
        assertEquals("day", granularityOf(24L * 92L))
        assertEquals("week", granularityOf(24L * 93L))
        // An explicit request still speaks louder than the span.
        assertEquals(
            "month",
            data(
                "/api/admin/tool-metrics/time-series?start=${hour(currentHour.minusHours(72L))}&end=${hour(currentHour)}&granularity=month",
            )["granularity"].asString(),
        )
    }

    private companion object {
        const val TENANT_ID = 1L
        const val NEIGHBOUR_TENANT_ID = 950_500L
        const val SESSION = "metrics-session"

        /** The fixture's two days plus the quiet ones, as the inclusive range every request below sends. */
        val SEVEN_DAYS: String = "start=${LocalDate.now().minusDays(6L)}&end=${LocalDate.now()}"
        val DAY2: String = LocalDate.now().minusDays(2L).toString()
        val DAY1: String = LocalDate.now().minusDays(1L).toString()
        val QUIET_DAY: String = LocalDate.now().minusDays(5L).toString()

        /** The shape the picker sends an hour in, and the shape the server echoes a bound back as. */
        val HOUR_PARAM: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val HOUR_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}

package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.ToolInvocationLog
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The daily rollup recomputes a whole day in one statement.
 *
 * These gates are the invariants the design claims for the aggregate table: buckets and counters
 * both sum to `calls` (I4), an identical re-run changes nothing (I5), a detail day the rollup has not
 * folded yet survives the retention sweep (I6), and one row never carries two subjects' calls. Only a real MySQL answers them — `ON DUPLICATE KEY UPDATE`
 * and `DATE()` over a `datetime(3)` are exactly what an in-memory engine gets wrong.
 *
 * `@MybatisTest` wraps each case in a transaction it rolls back, while the read-back below goes over a
 * second, independent MySQL session that cannot see another session's uncommitted rows and would read the
 * whole fixture as empty — a green run that asserts nothing. `NOT_SUPPORTED` lets each statement commit as
 * it is written, so what a case reads back is what the database stored. The price is that committed rows
 * outlive the case that wrote them, and these cases all share one tenant and one day, so the fixture the
 * next case counts would be the previous case's leftovers; the `@BeforeEach` sweep below is what keeps the
 * eight seeded calls eight.
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
open class ToolInvocationStatsMapperTest {

    companion object {
        @Container
        val mysqlContainer = MySQLContainer("mysql:8.0")
            .withDatabaseName("harnax_test")
            .withUsername("root")
            .withPassword("test")
            .withInitScript("schema-test.sql")

        /** Own tenant again: the seed data lives in tenant 1 and a neighbour class may use another. */
        private const val TENANT_ID = 22L

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysqlContainer::getJdbcUrl)
            registry.add("spring.datasource.username", mysqlContainer::getUsername)
            registry.add("spring.datasource.password", mysqlContainer::getPassword)
        }
    }

    @Autowired
    private lateinit var logMapper: ToolInvocationLogMapper

    @Autowired
    private lateinit var statsMapper: ToolInvocationStatsMapper

    /**
     * Rows commit under `NOT_SUPPORTED`, so without this every case would inherit the rows and the
     * aggregate keys its predecessors left behind for this tenant.
     */
    @BeforeEach
    fun clearTenantRows() {
        execute("DELETE FROM tool_invocation_stats WHERE tenant_id = $TENANT_ID")
        execute("DELETE FROM tool_invocation_log WHERE tenant_id = $TENANT_ID")
    }

    private fun day(): String = java.time.LocalDate.now().minusDays(3).toString()

    private fun at(hour: Int): LocalDateTime = java.time.LocalDate.now().minusDays(3).atTime(hour, 0, 0).truncatedTo(ChronoUnit.SECONDS)

    private fun row(
        hour: Int,
        toolName: String,
        outcome: String,
        durationMs: Long,
        mcpId: Long? = null,
        cliId: Long? = null,
    ) = ToolInvocationLog().apply {
        tenantId = TENANT_ID
        agentId = 7L
        sessionId = "web-rollup-fixture"
        kind = when {
            mcpId != null -> ToolInvocationLog.KIND_MCP
            cliId != null -> ToolInvocationLog.KIND_CLI
            else -> ToolInvocationLog.KIND_BUILTIN
        }
        this.toolName = toolName
        this.mcpId = mcpId
        this.cliId = cliId
        this.outcome = outcome
        this.durationMs = durationMs
        startTime = at(hour)
        endTime = at(hour)
        ts = at(hour)
    }

    /**
     * Eight calls on one day: three buckets, four outcomes, two MCP servers and one CLI package. Chosen so
     * the bucket sums and the outcome sums both have to come to eight, so a bucket boundary is exercised
     * from both sides (500 ms lands in le_500ms, 501 ms in le_2s), and so a rollup that folded the two MCP
     * servers into one subject could not reach the totals below.
     */
    private fun seedDay() {
        logMapper.batchInsert(
            listOf(
                row(1, "read_file", ToolInvocationLog.OUTCOME_SUCCESS, 80L),
                row(2, "read_file", ToolInvocationLog.OUTCOME_SUCCESS, 500L),
                row(3, "read_file", ToolInvocationLog.OUTCOME_ERROR, 501L),
                row(4, "write_file", ToolInvocationLog.OUTCOME_DENIED, 2000L),
                row(5, "write_file", ToolInvocationLog.OUTCOME_INTERRUPTED, 30000L),
                row(6, "search", ToolInvocationLog.OUTCOME_SUCCESS, 30001L, mcpId = 900L),
                row(7, "fetch", ToolInvocationLog.OUTCOME_SUCCESS, 400L, mcpId = 901L),
                row(8, "gh", ToolInvocationLog.OUTCOME_ERROR, 120L, cliId = 55L),
            ),
        )
    }

    private fun query(sql: String): List<Map<String, Any?>> {
        val out = mutableListOf<MutableMap<String, Any?>>()
        mysqlContainer.createConnection("").use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.executeQuery().use { rs ->
                    val md = rs.metaData
                    while (rs.next()) {
                        val row = mutableMapOf<String, Any?>()
                        for (i in 1..md.columnCount) row[md.getColumnLabel(i)] = rs.getObject(i)
                        out += row
                    }
                }
            }
        }
        return out
    }

    private fun execute(sql: String) {
        mysqlContainer.createConnection("").use { conn ->
            conn.prepareStatement(sql).use { it.executeUpdate() }
        }
    }

    private fun statsRows(): List<Map<String, Any?>> = query("SELECT * FROM tool_invocation_stats WHERE tenant_id = $TENANT_ID AND stat_date = '${day()}'")

    private fun cutoff(): String = java.time.LocalDate.now().minusDays(2).atStartOfDay().toString().replace('T', ' ')

    @Nested
    inner class Rollup {
        @Test
        fun `counters and buckets each sum to calls`() {
            seedDay()
            statsMapper.upsertDay(day())
            val rows = statsRows()
            assertTrue(rows.isNotEmpty())
            rows.forEach {
                val calls = (it["calls"] as Number).toInt()
                val outcomes = listOf("successes", "errors", "denials", "interruptions")
                    .sumOf { c -> (it[c] as Number).toInt() }
                val buckets = listOf("le_100ms", "le_500ms", "le_2s", "le_10s", "le_30s", "gt_30s")
                    .sumOf { c -> (it[c] as Number).toInt() }
                assertEquals(calls, outcomes, "outcome counters must equal calls")
                assertEquals(calls, buckets, "duration buckets must equal calls")
            }
            assertEquals(8, rows.sumOf { (it["calls"] as Number).toInt() })
        }

        @Test
        fun `recomputing the same day twice changes nothing`() {
            seedDay()
            statsMapper.upsertDay(day())
            val first = statsRows().sortedBy { "${it["kind"]}/${it["tool_name"]}/${it["subject_id"]}" }
            statsMapper.upsertDay(day())
            val second = statsRows().sortedBy { "${it["kind"]}/${it["tool_name"]}/${it["subject_id"]}" }
            assertEquals(first, second, "the rollup must be idempotent, so a second replica is harmless")
        }

        @Test
        fun `a day not yet rolled up survives the retention sweep`() {
            seedDay()
            val before = query("SELECT COUNT(*) AS c FROM tool_invocation_log WHERE tenant_id = $TENANT_ID").first()["c"]
            // Everything is three days old, so a window of two days would delete it all if the sweep did
            // not gate on "already rolled up" (I6).
            val deleted = logMapper.deleteRolledOut(cutoff())
            assertEquals(0, deleted)
            val after = query("SELECT COUNT(*) AS c FROM tool_invocation_log WHERE tenant_id = $TENANT_ID").first()["c"]
            assertEquals(before, after)

            statsMapper.upsertDay(day())
            val rolledDeleted = logMapper.deleteRolledOut(cutoff())
            assertEquals(8, rolledDeleted)
        }

        @Test
        fun `each mcp server and each cli package gets its own row`() {
            seedDay()
            statsMapper.upsertDay(day())
            val callsBySubject = statsRows()
                .filter { it["kind"] != ToolInvocationLog.KIND_BUILTIN }
                .associate { "${it["kind"]}/${it["subject_id"]}" to (it["calls"] as Number).toInt() }
            // `subject_id` is documented as this row's own mcp_id or cli_id. Folding two servers into one
            // group hands the higher id the other server's calls, and the read API then names a server the
            // call never went through.
            assertEquals(
                mapOf(
                    "${ToolInvocationLog.KIND_MCP}/900" to 1,
                    "${ToolInvocationLog.KIND_MCP}/901" to 1,
                    "${ToolInvocationLog.KIND_CLI}/55" to 1,
                ),
                callsBySubject,
            )
        }

        @Test
        fun `the rollup only owes a day it has not folded yet`() {
            seedDay()
            // The pending list is how the hourly job finds work: it names the seeded day and nothing else.
            assertEquals(listOf(day()), logMapper.selectUnrolledDates(day()))

            statsMapper.upsertDay(day())
            assertTrue(
                logMapper.selectUnrolledDates(day()).isEmpty(),
                "a folded day must stop showing up as pending, or the job runs forever",
            )
        }
    }
}

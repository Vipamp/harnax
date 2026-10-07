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
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The daily rollup recomputes a whole day in one statement.
 *
 * These gates are the invariants the design claims for the aggregate table.
 * Buckets and counters both sum to `calls` (I4), and every group's row is the fold of its own detail rows
 * and of nothing else. An identical re-run changes nothing (I5). A detail day the rollup has not folded yet
 * survives the retention sweep (I6), with the tenantless day as that rule's one exception.
 * One row never carries two subjects' calls, and never two tools' either.
 * Only a real MySQL answers them — `ON DUPLICATE KEY UPDATE` and the day range over a `datetime(3)` column
 * are exactly what an in-memory engine gets wrong.
 *
 * `@MybatisTest` wraps each case in a transaction it rolls back, while the read-back below goes over a
 * second, independent MySQL session that cannot see another session's uncommitted rows and would read the
 * whole fixture as empty — a green run that asserts nothing. `NOT_SUPPORTED` lets each statement commit as
 * it is written, so what a case reads back is what the database stored. The price is that committed rows
 * outlive the case that wrote them, and these cases all share one tenant and one day, so the fixture the
 * next case counts would be the previous case's leftovers; the `@BeforeEach` sweep below is what keeps the
 * day holding exactly the rows the case just seeded.
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

        /** The four outcome counters, whose sum the design claims is `calls` (I4). */
        private val OUTCOME_COLUMNS = listOf("successes", "errors", "denials", "interruptions")

        /** The six duration buckets, whose sum the design also claims is `calls` (I4). */
        private val BUCKET_COLUMNS = listOf("le_100ms", "le_500ms", "le_2s", "le_10s", "le_30s", "gt_30s")

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
     * One day for every case, read once instead of at each use: a run that crossed midnight between seeding
     * and folding would otherwise fold a different day than the one it wrote, and see its own fixture gone.
     */
    private val fixtureDay: LocalDate = LocalDate.now().minusDays(3)

    /** A second day, equally past the retention cutoff, held by a row that names no tenant at all. */
    private val orphanDay: LocalDate = fixtureDay.minusDays(1)

    /**
     * Rows commit under `NOT_SUPPORTED`, so without this every case would inherit the rows and the
     * aggregate keys its predecessors left behind for this tenant.
     */
    @BeforeEach
    fun clearTenantRows() {
        execute("DELETE FROM tool_invocation_stats WHERE tenant_id = $TENANT_ID")
        execute("DELETE FROM tool_invocation_log WHERE tenant_id = $TENANT_ID")
    }

    /**
     * Nineteen calls on one day, over the seven groups the key can build, laid out so no column can cheat.
     *
     * Two builtin tools, plus a second tool on the `900` server, so a fold that dropped `tool_name` out of
     * the key would merge rows the page lists apart and every vector below would then disagree. Within
     * `read_file` the four outcome counters are 3, 2, 1 and 0 — pairwise distinct, so swapping any two
     * outcome predicates changes that row even though the day's totals stay right. And every bucket
     * boundary is seeded from both sides (100, 500, 2000, 10000 and 30000 ms inside, 501, 2001, 10001 and
     * 30001 ms outside), so a boundary that moves either way shows up as a wrong bucket, not as a wrong
     * total that the I4 sum check would still pass.
     */
    private val fixture: List<ToolInvocationLog> = listOf(
        row(1, "read_file", ToolInvocationLog.OUTCOME_SUCCESS, 80L),
        row(2, "read_file", ToolInvocationLog.OUTCOME_SUCCESS, 100L),
        row(3, "read_file", ToolInvocationLog.OUTCOME_SUCCESS, 500L),
        row(4, "read_file", ToolInvocationLog.OUTCOME_ERROR, 501L),
        row(5, "read_file", ToolInvocationLog.OUTCOME_ERROR, 2000L),
        row(6, "read_file", ToolInvocationLog.OUTCOME_DENIED, 2001L),
        row(7, "send_email", ToolInvocationLog.OUTCOME_SUCCESS, 9999L),
        row(8, "send_email", ToolInvocationLog.OUTCOME_SUCCESS, 10000L),
        row(9, "send_email", ToolInvocationLog.OUTCOME_ERROR, 10001L),
        row(10, "send_email", ToolInvocationLog.OUTCOME_DENIED, 30000L),
        row(11, "send_email", ToolInvocationLog.OUTCOME_INTERRUPTED, 30001L),
        row(12, "search", ToolInvocationLog.OUTCOME_SUCCESS, 500L, mcpId = 900L),
        row(13, "search", ToolInvocationLog.OUTCOME_ERROR, 150L, mcpId = 900L),
        row(14, "fetch", ToolInvocationLog.OUTCOME_SUCCESS, 100L, mcpId = 900L),
        row(15, "fetch", ToolInvocationLog.OUTCOME_SUCCESS, 1200L, mcpId = 901L),
        row(16, "fetch", ToolInvocationLog.OUTCOME_DENIED, 12000L, mcpId = 901L),
        row(17, "gh", ToolInvocationLog.OUTCOME_SUCCESS, 60L, cliId = 55L),
        row(18, "gh", ToolInvocationLog.OUTCOME_ERROR, 2500L, cliId = 55L),
        row(19, "git", ToolInvocationLog.OUTCOME_DENIED, 200000L, cliId = 55L),
    )

    /** The whole expected vector of each group, keyed by the identity the unique key gives it. */
    private val expectedVectors: Map<String, Vector> = mapOf(
        "builtin/0/read_file" to Vector(
            calls = 6, successes = 3, errors = 2, denials = 1, interruptions = 0,
            le100ms = 2, le500ms = 1, le2s = 2, le10s = 1, le30s = 0, gt30s = 0,
            sumDurationMs = 5182L, maxDurationMs = 2001L,
        ),
        "builtin/0/send_email" to Vector(
            calls = 5, successes = 2, errors = 1, denials = 1, interruptions = 1,
            le100ms = 0, le500ms = 0, le2s = 0, le10s = 2, le30s = 2, gt30s = 1,
            sumDurationMs = 90001L, maxDurationMs = 30001L,
        ),
        "mcp/900/search" to Vector(
            calls = 2, successes = 1, errors = 1, denials = 0, interruptions = 0,
            le100ms = 0, le500ms = 2, le2s = 0, le10s = 0, le30s = 0, gt30s = 0,
            sumDurationMs = 650L, maxDurationMs = 500L,
        ),
        "mcp/900/fetch" to Vector(
            calls = 1, successes = 1, errors = 0, denials = 0, interruptions = 0,
            le100ms = 1, le500ms = 0, le2s = 0, le10s = 0, le30s = 0, gt30s = 0,
            sumDurationMs = 100L, maxDurationMs = 100L,
        ),
        "mcp/901/fetch" to Vector(
            calls = 2, successes = 1, errors = 0, denials = 1, interruptions = 0,
            le100ms = 0, le500ms = 0, le2s = 1, le10s = 0, le30s = 1, gt30s = 0,
            sumDurationMs = 13200L, maxDurationMs = 12000L,
        ),
        "cli/55/gh" to Vector(
            calls = 2, successes = 1, errors = 1, denials = 0, interruptions = 0,
            le100ms = 1, le500ms = 0, le2s = 0, le10s = 1, le30s = 0, gt30s = 0,
            sumDurationMs = 2560L, maxDurationMs = 2500L,
        ),
        "cli/55/git" to Vector(
            calls = 1, successes = 0, errors = 0, denials = 1, interruptions = 0,
            le100ms = 0, le500ms = 0, le2s = 0, le10s = 0, le30s = 0, gt30s = 1,
            sumDurationMs = 200000L, maxDurationMs = 200000L,
        ),
    )

    private fun at(day: LocalDate, hour: Int): LocalDateTime = day.atTime(hour, 0, 0)

    private fun row(
        hour: Int,
        toolName: String,
        outcome: String,
        durationMs: Long,
        mcpId: Long? = null,
        cliId: Long? = null,
        tenantId: Long? = TENANT_ID,
        day: LocalDate = fixtureDay,
    ) = ToolInvocationLog().apply {
        this.tenantId = tenantId
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
        startTime = at(day, hour)
        endTime = at(day, hour)
        ts = at(day, hour)
    }

    private fun seedDay() {
        logMapper.batchInsert(fixture)
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

    private fun count(sql: String): Long = (query(sql).first()["c"] as Number).toLong()

    /**
     * Detail rows still in the table for [day] under [tenant] (null means the tenantless rows).
     *
     * Counted per tenant and per day on purpose: the sweep is a whole-server statement, so a total over the
     * table would answer for rows this class never seeded.
     */
    private fun detailCount(tenant: Long?, day: LocalDate): Long {
        val owner = if (tenant == null) "tenant_id IS NULL" else "tenant_id = $tenant"
        return count(
            "SELECT COUNT(*) AS c FROM tool_invocation_log WHERE $owner " +
                "AND ts >= '$day' AND ts < DATE_ADD('$day', INTERVAL 1 DAY)",
        )
    }

    private fun statsRows(): List<Map<String, Any?>> = query(
        "SELECT * FROM tool_invocation_stats WHERE tenant_id = $TENANT_ID AND stat_date = '${day()}'",
    )

    /** The identity the aggregate's unique key gives a row, minus the date and the tenant this class fixes. */
    private fun keyOf(row: Map<String, Any?>): String = "${row["kind"]}/${row["subject_id"]}/${row["tool_name"]}"

    private fun vectorOf(row: Map<String, Any?>): Vector {
        fun number(column: String): Number = row[column] as Number
        return Vector(
            calls = number("calls").toInt(),
            successes = number("successes").toInt(),
            errors = number("errors").toInt(),
            denials = number("denials").toInt(),
            interruptions = number("interruptions").toInt(),
            le100ms = number("le_100ms").toInt(),
            le500ms = number("le_500ms").toInt(),
            le2s = number("le_2s").toInt(),
            le10s = number("le_10s").toInt(),
            le30s = number("le_30s").toInt(),
            gt30s = number("gt_30s").toInt(),
            sumDurationMs = number("sum_duration_ms").toLong(),
            maxDurationMs = number("max_duration_ms").toLong(),
        )
    }

    private fun vectorsByKey(): Map<String, Vector> {
        val rows = statsRows()
        val vectors = rows.associate { keyOf(it) to vectorOf(it) }
        assertEquals(rows.size, vectors.size, "two aggregate rows share one key: ${rows.map { keyOf(it) }.sorted()}")
        return vectors
    }

    private fun day(): String = fixtureDay.toString()

    /** The retention cutoff: the start of the day after [fixtureDay], so both seeded days sit behind it. */
    private fun cutoff(): String = "${fixtureDay.plusDays(1)} 00:00:00"

    /** Every measured column of one aggregate row, so a case can assert the whole vector at once. */
    private data class Vector(
        val calls: Int,
        val successes: Int,
        val errors: Int,
        val denials: Int,
        val interruptions: Int,
        val le100ms: Int,
        val le500ms: Int,
        val le2s: Int,
        val le10s: Int,
        val le30s: Int,
        val gt30s: Int,
        val sumDurationMs: Long,
        val maxDurationMs: Long,
    )

    @Nested
    inner class Rollup {
        @Test
        fun `counters and buckets each sum to calls`() {
            seedDay()
            statsMapper.upsertDay(day())
            val rows = statsRows()
            assertTrue(rows.isNotEmpty(), "a fold that wrote no row makes every claim below vacuous")
            rows.forEach {
                val calls = (it["calls"] as Number).toInt()
                val outcomes = OUTCOME_COLUMNS.sumOf { c -> (it[c] as Number).toInt() }
                val buckets = BUCKET_COLUMNS.sumOf { c -> (it[c] as Number).toInt() }
                assertEquals(calls, outcomes, "outcome counters must equal calls in ${keyOf(it)}")
                assertEquals(calls, buckets, "duration buckets must equal calls in ${keyOf(it)}")
            }
            assertEquals(
                fixture.size,
                rows.sumOf { (it["calls"] as Number).toInt() },
                "no call may be dropped by the fold or counted into two rows",
            )
        }

        @Test
        fun `each group's row is the fold of its own detail rows`() {
            seedDay()
            statsMapper.upsertDay(day())

            assertEquals(
                expectedVectors,
                vectorsByKey(),
                "every counter, bucket, sum and max must be this group's own fold",
            )
        }

        @Test
        fun `two tools of one subject stay two rows`() {
            seedDay()
            statsMapper.upsertDay(day())
            val keys = vectorsByKey().keys

            // The key is (kind, subject_id, tool_name), and `tool_name` is written by every kind. A fold that
            // blanked it for anything but cli stays internally consistent — its totals all still add up — so
            // this lookup by name is the only thing that notices two tools were folded into one row.
            assertEquals(
                listOf("builtin/0/read_file", "builtin/0/send_email"),
                keys.filter { it.startsWith("builtin/") }.sorted(),
                "both builtin tools must keep their own row on one day and tenant",
            )
            assertEquals(
                listOf("mcp/900/fetch", "mcp/900/search"),
                keys.filter { it.startsWith("mcp/900/") }.sorted(),
                "the two tools of one MCP server must not share a row",
            )
        }

        @Test
        fun `recomputing the same day twice changes nothing`() {
            seedDay()
            statsMapper.upsertDay(day())
            val first = statsRows().sortedBy { keyOf(it) }
            assertTrue(first.isNotEmpty(), "two empty lists are equal too, and that would prove nothing")

            statsMapper.upsertDay(day())
            val second = statsRows().sortedBy { keyOf(it) }
            assertEquals(first.size, second.size, "a recompute must not add or lose a group")
            assertEquals(first, second, "the rollup must be idempotent, so a second replica is harmless")
        }

        @Test
        fun `each mcp server and each cli package gets its own row`() {
            seedDay()
            statsMapper.upsertDay(day())
            val callsBySubject = statsRows()
                .filter { it["kind"] != ToolInvocationLog.KIND_BUILTIN }
                .groupBy { "${it["kind"]}/${it["subject_id"]}" }
                .mapValues { entry -> entry.value.sumOf { (it["calls"] as Number).toInt() } }
            // `subject_id` is documented as this row's own mcp_id or cli_id. Folding two servers into one
            // group hands the higher id the other server's calls, and the read API then names a server the
            // call never went through.
            assertEquals(
                mapOf(
                    "${ToolInvocationLog.KIND_MCP}/900" to 3,
                    "${ToolInvocationLog.KIND_MCP}/901" to 2,
                    "${ToolInvocationLog.KIND_CLI}/55" to 3,
                ),
                callsBySubject,
            )
        }

        @Test
        fun `a day not yet rolled up survives the retention sweep`() {
            seedDay()
            val seeded = detailCount(TENANT_ID, fixtureDay)
            assertEquals(fixture.size.toLong(), seeded, "the fixture must be committed before the sweep is judged")
            // Everything is three days old, so a window of two days would delete it all if the sweep did
            // not gate on "already rolled up" (I6).
            logMapper.deleteRolledOut(cutoff())
            assertEquals(seeded, detailCount(TENANT_ID, fixtureDay), "an unrolled day must stay queryable (I6)")

            statsMapper.upsertDay(day())
            logMapper.deleteRolledOut(cutoff())
            assertEquals(0L, detailCount(TENANT_ID, fixtureDay), "the folded day is released by the next sweep")
        }

        @Test
        fun `the tenantless row is the one exception to the rolled out gate`() {
            logMapper.batchInsert(
                listOf(row(2, "read_file", ToolInvocationLog.OUTCOME_SUCCESS, 80L, tenantId = null, day = orphanDay)),
            )

            // Nothing ever folds a tenantless day, so it cannot be owed to the rollup: the aggregate's
            // `tenant_id` is NOT NULL and the pending list is a difference against that table.
            assertEquals(
                emptyList(),
                logMapper.selectUnrolledDates(orphanDay.toString()),
                "the tenantless day must never be named as pending",
            )

            logMapper.deleteRolledOut(cutoff())
            assertEquals(0L, detailCount(null, orphanDay), "the tenantless row leaves on the window alone")

            // A row of the same age that does name a tenant stays, because nothing has folded its day yet.
            logMapper.batchInsert(listOf(row(3, "send_email", ToolInvocationLog.OUTCOME_SUCCESS, 90L, day = orphanDay)))
            logMapper.deleteRolledOut(cutoff())
            assertEquals(0L, detailCount(null, orphanDay), "the tenantless row stays released")
            assertEquals(1L, detailCount(TENANT_ID, orphanDay), "the tenant's own unfolded day survives (I6)")
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

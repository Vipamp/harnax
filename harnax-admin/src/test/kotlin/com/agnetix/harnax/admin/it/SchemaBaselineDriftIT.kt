package com.agnetix.harnax.admin.it

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.nio.file.Files
import java.nio.file.Paths
import javax.sql.DataSource

/**
 * Keeps the mapper-layer schema baseline in step with the migrations.
 *
 * Two schemas build the same tables two different ways: admin's ITs (and production) replay
 * `db/migration/V*.sql`, while `harnax-entity`'s mapper tests run the hand-maintained
 * `schema-test.sql`. Nothing linked them, and the link is load-bearing — a table the baseline lacks makes
 * every mapper over it fail with `BadSqlGrammar`, and a column the baseline lacks fails only the query that
 * happens to name it, which is how `selectPackageObjects` would have died had the baseline kept
 * `package_object` out. The drift has been repaired by hand twice already (AGENT-19 mirrored `cli` and
 * `agent_cli_binding`, the env round mirrored two binding tables), so this is the check that stops needing
 * somebody to remember it.
 *
 * The migrated schema is the authoritative side because it is the one the application actually runs on, and
 * reading it from `information_schema` means this class never reimplements `ALTER TABLE` semantics — column
 * renames, generated columns and drops all resolve themselves in the live schema, where they would
 * otherwise be a parser to get wrong here.
 */
class SchemaBaselineDriftIT : BaseAdminIT() {

    @Autowired
    private lateinit var dataSource: DataSource

    @Test
    fun `every table the migrations create also exists in the mapper baseline`() {
        val missing = migratedTables() - baselineTables()
        assertTrue(
            missing.isEmpty(),
            "schema-test.sql has no table(s) the migrations create: ${missing.sorted()}. " +
                "Mirror the CREATE TABLE there, or no mapper over them can be tested at all.",
        )
    }

    /**
     * The direction that has actually bitten twice: a migration adds a column, the baseline keeps the old
     * shape, and only the query naming that column fails — at which point the natural diagnosis is the
     * query, not the fixture.
     */
    @Test
    fun `every column the migrations create also exists in the mapper baseline`() {
        val report = diffAgainst(migratedColumns(), baselineColumns())
        assertTrue(report.isEmpty(), "schema-test.sql is behind db/migration:\n$report")
    }

    /**
     * The reverse direction, because a baseline still carrying a dropped column is how a mapper test passes
     * on a statement production cannot answer.
     */
    @Test
    fun `the mapper baseline declares no column the migrated schema lacks`() {
        val report = diffAgainst(baselineColumns(), migratedColumns())
        assertTrue(
            report.isEmpty(),
            "schema-test.sql is ahead of db/migration, these columns are not in the migrated schema:\n$report",
        )
    }

    // ── the two schema sources ──

    private fun migratedTables(): Set<String> = queryRows("SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()")
        .map { it[0].lowercase() }
        .filterNot { it.isInfrastructure() }
        .toSet()

    private fun migratedColumns(): Map<String, Set<String>> = queryRows(
        "SELECT table_name, column_name FROM information_schema.columns " +
            "WHERE table_schema = DATABASE() ORDER BY table_name, ordinal_position",
    ).map { (table, column) -> table.lowercase() to column.lowercase() }
        .filterNot { (table, _) -> table.isInfrastructure() }
        .groupBy({ (table, _) -> table }, { (_, column) -> column })
        .mapValues { (_, columns) -> columns.toSet() }

    /**
     * Reads the hand-maintained baseline out of the sibling module rather than the classpath: it is
     * `harnax-entity`'s test resource, and test resources are not published to dependants. Fails when the
     * file cannot be found — a baseline this class silently never read would make every check above pass.
     */
    private fun baselineSql(): String {
        val relative = Paths.get("harnax-entity", "src", "test", "resources", "schema-test.sql")
        val candidates = listOf(
            Paths.get("..").resolve(relative),
            relative,
            Paths.get(System.getProperty("basedir") ?: ".", relative.toString()),
        )
        val path = candidates.firstOrNull { Files.isRegularFile(it) }
        return requireNotNull(path) {
            "schema-test.sql not found from ${Paths.get("").toAbsolutePath()}, looked at $candidates"
        }.let { Files.readString(it) }
    }

    private fun queryRows(sql: String): List<List<String>> = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).let { result ->
                val width = result.metaData.columnCount
                buildList {
                    while (result.next()) {
                        add((1..width).map { result.getString(it) ?: "" })
                    }
                }
            }
        }
    }

    // ── baseline parsing ──

    private fun baselineTables(): Set<String> = CREATE_TABLE.findAll(baselineSql()).map { it.groupValues[1].lowercase() }.toSet()

    /**
     * Column names per `CREATE TABLE` block.
     *
     * Only a line whose first token is a backticked name counts, so constraint lines are out by shape
     * rather than by keyword list. The baseline is a checked-in file this project writes to one style, which
     * is what makes that enough — the migrated side deliberately does not go through this parser.
     */
    private fun baselineColumns(): Map<String, Set<String>> = CREATE_TABLE.findAll(baselineSql()).associate { match ->
        val body = match.groupValues[2]
        match.groupValues[1].lowercase() to body.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("`") }
            .mapNotNull { COLUMN_NAME.find(it)?.groupValues?.get(1)?.lowercase() }
            .toSet()
    }

    /** `from` minus `to`, limited to tables both sides name — a table only one side has is the other check's business. */
    private fun diffAgainst(from: Map<String, Set<String>>, to: Map<String, Set<String>>): String = buildString {
        for ((table, columns) in from.toSortedMap()) {
            val against = to[table] ?: continue
            val extra = (columns - against).sorted()
            if (extra.isNotEmpty()) {
                append("  ").append(table).append(": ").append(extra).append('\n')
            }
        }
    }

    private fun String.isInfrastructure(): Boolean = startsWith("flyway_")

    private companion object {
        /** `CREATE TABLE ... \`name\` ( ...body... )` where the body stops at the line that closes it. */
        val CREATE_TABLE = Regex(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`([^`]+)`\\s*\\((.*?)\\n\\)",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )

        val COLUMN_NAME = Regex("^`([^`]+)`")
    }
}

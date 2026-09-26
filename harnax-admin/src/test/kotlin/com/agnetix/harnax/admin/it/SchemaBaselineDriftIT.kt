package com.agnetix.harnax.admin.it

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.io.File
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
 *
 * Tables and columns are only half the schema, though, and not the half that has been drifting. V43, V45,
 * V46 and V49 are uniqueness work: between them they add four unique keys and drop four — V46 replaces the
 * key V45 had just created — and a diff over table and column names calls every one of those changes a
 * match. The last checks of this
 * class therefore compare the keys themselves — name, ordered columns, uniqueness. There the
 * `information_schema` shortcut is not available for the interesting half: `ALTER … ADD UNIQUE KEY` and
 * `ALTER … DROP INDEX` and MySQL's removal of a key when its last column is dropped only make sense in
 * version order, so `db/migration` is replayed rather than read statement by statement. That is a parser,
 * which is exactly what the paragraph above warns about, so the parser is bounded to the shapes the tree
 * actually contains and `the migration replay understands every index statement in the tree` fails the run
 * the moment it meets one it has not modelled — an incomplete replay is a red test, never a silent pass.
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

    // ── indexes and unique keys ──

    /**
     * The dimension the three checks above are blind to, and the one the uniqueness rounds actually change.
     *
     * Every entry [TOLERATED_KEY_DRIFT] names is printed with the table and the key that drifted, so a
     * failure reads as `token_stats.idx_ts: …` rather than as a count.
     */
    @Test
    fun `every index and unique key the migrations create also exists in the mapper baseline`() {
        val drift = keyDrift(migratedKeys(), baselineKeys()).rejectTolerated()
        assertTrue(
            drift.isEmpty(),
            "schema-test.sql is behind db/migration on indexes and unique keys:\n" +
                drift.joinToString("\n") { it.describe(fromSide = "the migrations", toSide = "the baseline") },
        )
    }

    /**
     * Baseline-side keys production never created. This is the direction the baseline has drifted in for
     * years, and the one with a real cost: a unique key the fixture invented makes a mapper test reject two
     * rows production would happily take, so the behaviour under test is the fixture's, not the schema's.
     */
    @Test
    fun `the mapper baseline declares no index or unique key the migrated schema lacks`() {
        val drift = keyDrift(baselineKeys(), migratedKeys()).rejectTolerated()
        assertTrue(
            drift.isEmpty(),
            "schema-test.sql carries indexes and unique keys no migration ever created:\n" +
                drift.joinToString("\n") { it.describe(fromSide = "the baseline", toSide = "the migrations") },
        )
    }

    /**
     * An allow-list entry that no longer matches real drift is worse than no allow-list at all: it sits
     * there ready to cover the next accidental key the moment somebody reuses the name. Each entry has to
     * still be earning its place, which also means the test goes red when a tolerated item is fixed and
     * reminds whoever fixed it to delete the line.
     */
    @Test
    fun `the tolerated key drift list still describes real drift`() {
        val real = (keyDrift(migratedKeys(), baselineKeys()) + keyDrift(baselineKeys(), migratedKeys()))
            .map { it.id }
            .toSet()
        val stale = TOLERATED_KEY_DRIFT - real
        assertTrue(
            stale.isEmpty(),
            "these TOLERATED_KEY_DRIFT entries no longer match any drift and must be deleted: ${stale.sorted()}",
        )
    }

    /**
     * The guard on the guard. The key replay models only the DDL shapes this repo uses; meet a `RENAME
     * INDEX` or a `FULLTEXT KEY` for the first time and it would read that statement as having no effect on
     * keys and report a clean schema. Anything it could not classify is collected here and fails loudly.
     */
    @Test
    fun `the migration replay understands every index statement in the tree`() {
        val unparsed = migrationParse.unparsed + baselineParse.unparsed
        assertTrue(
            unparsed.isEmpty(),
            "the key replay met DDL it does not model, so the key sets it reports are incomplete:\n" +
                unparsed.joinToString("\n") { "  $it" } +
                "\nTeach parseTableBody or applyAlterClause that shape instead of relaxing a check — an " +
                "index the parser never saw looks exactly like an index nobody needs.",
        )
    }

    // ── key replay ──

    /** One key of one table. Order is part of equality: `(a, b)` and `(b, a)` are different indexes. */
    private data class Key(val columns: List<String>, val unique: Boolean) {
        fun render(name: String): String = (if (unique) "UNIQUE KEY " else "KEY ") + name + columns.joinToString(", ", " (", ")")
    }

    /** One key present on one side and absent — or differently shaped — on the other. */
    private data class KeyDrift(val table: String, val name: String, val expected: Key, val actual: Key?) {
        val id: String get() = "$table.$name"

        fun describe(fromSide: String, toSide: String): String = if (actual == null) {
            "  $id: $fromSide have ${expected.render(name)}, $toSide has no such key"
        } else {
            "  $id: $fromSide have ${expected.render(name)} but $toSide has ${actual.render(name)}"
        }
    }

    /** The mutable per-table state the replay walks forward, statement by statement. */
    private class TableState {
        val keys = LinkedHashMap<String, Key>()
        val columns = LinkedHashSet<String>()

        fun add(name: String, columns: List<String>, unique: Boolean) {
            keys[name] = Key(columns, unique)
        }

        /**
         * MySQL drops a key outright once the last column it covers goes, and otherwise only loses that
         * part. V34 leans on the first rule: `team.lead_agent_id` goes without a matching `DROP INDEX`, and
         * its index goes with it.
         */
        fun dropColumn(column: String) {
            columns.remove(column)
            for ((name, key) in keys.toMap()) {
                if (column !in key.columns) continue
                val kept = key.columns.filter { it != column }
                if (kept.isEmpty()) keys.remove(name) else keys[name] = key.copy(columns = kept)
            }
        }

        /** A renamed column keeps its index, which is why `CHANGE COLUMN` reaches the key lists too. */
        fun renameColumn(from: String, to: String) {
            columns.remove(from)
            columns.add(to)
            for ((name, key) in keys.toMap()) {
                if (from in key.columns) keys[name] = key.copy(columns = key.columns.map { if (it == from) to else it })
            }
        }
    }

    /** A parsed schema: the per-table key state and everything the parser refused to guess at. */
    private class Parse {
        val tables = LinkedHashMap<String, TableState>()
        val unparsed = mutableListOf<String>()

        fun keys(): Map<String, Map<String, Key>> = tables.entries.associate { (table, state) -> table to state.keys.toMap() }
    }

    private val migrationParse: Parse by lazy { replayMigrations() }

    private val baselineParse: Parse by lazy { parseBaseline() }

    private fun migratedKeys(): Map<String, Map<String, Key>> = migrationParse.keys()

    private fun baselineKeys(): Map<String, Map<String, Key>> = baselineParse.keys()

    private fun replayMigrations(): Parse {
        val parse = Parse()
        for (file in migrationFiles()) {
            for (statement in splitTopLevel(stripSqlComments(Files.readString(file.toPath())), ';')) {
                applyStatement(parse, statement.flatten())
            }
        }
        require(parse.tables.isNotEmpty()) {
            "the key replay found no CREATE TABLE in ${migrationDir().absolutePath}, so it compared nothing"
        }
        return parse
    }

    private fun parseBaseline(): Parse {
        val parse = Parse()
        for (statement in splitTopLevel(stripSqlComments(baselineSql()), ';').map { it.flatten() }) {
            if (statement.isEmpty()) continue
            val create = createTableOf(statement)
            if (create == null) {
                // The fixture is CREATE TABLEs plus seed rows today. Should it ever carry an ALTER or a
                // DROP, that has to be replayed here exactly as it is on the migration side, so an
                // unexpected statement is named rather than skipped.
                if (!NO_KEY_EFFECT_PREFIX.containsMatchIn(statement)) parse.unparsed += "baseline statement `$statement`"
                continue
            }
            val state = TableState()
            parseTableBody(create.second, state, parse.unparsed)
            parse.tables[create.first] = state
        }
        require(parse.tables.isNotEmpty()) { "the key parser found no CREATE TABLE in schema-test.sql" }
        return parse
    }

    private fun applyStatement(parse: Parse, flat: String) {
        if (flat.isEmpty()) return
        val create = createTableOf(flat)
        if (create != null) {
            val state = TableState()
            parseTableBody(create.second, state, parse.unparsed)
            parse.tables[create.first] = state
            return
        }
        val alter = ALTER_TABLE.find(flat)
        if (alter != null) {
            val table = tableNameOf(alter.groupValues[1])
            if (table == null) {
                parse.unparsed += "alter table name `${alter.groupValues[1]}`"
            } else {
                val state = parse.tables.getOrPut(table) { TableState() }
                for (clause in splitTopLevel(alter.groupValues[2], ',').map { it.flatten() }) {
                    applyAlterClause(table, state, clause, parse.unparsed)
                }
            }
            return
        }
        val rename = RENAME_TABLE.find(flat)
        if (rename != null) {
            for (pair in splitTopLevel(rename.groupValues[1], ',').map { it.flatten() }) {
                val parts = RENAME_PAIR.find(pair)
                val from = parts?.groupValues?.get(1)?.let { tableNameOf(it) }
                val to = parts?.groupValues?.get(2)?.let { tableNameOf(it) }
                if (from == null || to == null) {
                    parse.unparsed += "rename pair `$pair`"
                } else {
                    parse.tables.remove(from)?.let { parse.tables[to] = it }
                }
            }
            return
        }
        val drop = DROP_TABLE.find(flat)
        if (drop != null) {
            for (token in splitTopLevel(drop.groupValues[1], ',')) {
                tableNameOf(token)?.let(parse.tables::remove)
            }
            return
        }
        when {
            // Data and DML say nothing about keys.
            NO_KEY_EFFECT_PREFIX.containsMatchIn(flat) -> Unit
            // V28 has no `ADD INDEX IF NOT EXISTS` in MySQL, so it builds its ALTER as a string and runs
            // it through PREPARE. The guard only decides whether a live server already has the index, so
            // replaying the payload is what the migration ends up meaning.
            SESSION_DDL_PREFIX.containsMatchIn(flat) -> embeddedDdl(flat).forEach { applyStatement(parse, it.flatten()) }
            else -> parse.unparsed += "statement `$flat`"
        }
    }

    private fun applyAlterClause(table: String, state: TableState, clause: String, unparsed: MutableList<String>) {
        if (clause.isEmpty()) return
        val addKey = ALTER_ADD_KEY.matchEntire(clause)
        if (addKey != null) {
            val name = identifierOf(addKey.groupValues[2])
            if (name != null) state.add(name, columnListOf(addKey.groupValues[3], unparsed), addKey.groupValues[1].isNotBlank())
            return
        }
        val dropKey = ALTER_DROP_KEY.matchEntire(clause)
        if (dropKey != null) {
            identifierOf(dropKey.groupValues[1])?.let { state.keys.remove(it) }
            return
        }
        if (RENAME_KEY_PREFIX.containsMatchIn(clause)) {
            unparsed += "rename key on $table: `$clause`"
            return
        }
        val change = ALTER_CHANGE_COLUMN.matchEntire(clause)
        if (change != null) {
            val from = identifierOf(change.groupValues[1])
            val to = identifierOf(change.groupValues[2])
            if (from != null && to != null) state.renameColumn(from, to)
            return
        }
        val dropColumn = ALTER_DROP_COLUMN.matchEntire(clause)
        if (dropColumn != null) {
            identifierOf(dropColumn.groupValues[1])?.let { state.dropColumn(it) }
            return
        }
        val addColumn = ALTER_ADD_COLUMN.matchEntire(clause)
        if (addColumn != null) {
            val column = identifierOf(addColumn.groupValues[1])
            if (column != null) {
                state.columns.add(column)
                if (INLINE_UNIQUE.containsMatchIn(stripLiterals(clause).flatten())) state.add(column, listOf(column), true)
            }
            return
        }
        // Anything else an ALTER can do to a column's type or default, or to the table options, leaves the
        // key sets alone — but only the shapes listed here, so an unknown one still has to be named.
        if (!COLUMN_ONLY_ACTION_PREFIX.containsMatchIn(clause) && !TABLE_ONLY_ACTION_PREFIX.containsMatchIn(clause)) {
            unparsed += "alter clause on $table: `$clause`"
        }
    }

    /**
     * `PRIMARY KEY (…)`, `[UNIQUE] KEY|INDEX name (…)` and the two column-level shorthands, off the body of
     * a `CREATE TABLE`. Both schema sources go through here, which is what makes the comparison honest: a
     * shape only one side can read shows up as drift rather than as a pass.
     */
    private fun parseTableBody(body: String, state: TableState, unparsed: MutableList<String>) {
        for (item in splitTopLevel(body, ',').map { it.flatten() }) {
            if (item.isEmpty()) continue
            val primaryKey = PRIMARY_KEY_ITEM.matchEntire(item)
            if (primaryKey != null) {
                state.add(PRIMARY, columnListOf(primaryKey.groupValues[1], unparsed), true)
                continue
            }
            val key = KEY_ITEM.matchEntire(item)
            if (key != null) {
                val name = identifierOf(key.groupValues[3])
                if (name != null) {
                    state.add(name, columnListOf(key.groupValues[4], unparsed), key.groupValues[1].isNotBlank())
                }
                continue
            }
            if (CONSTRAINT_PREFIX.containsMatchIn(item)) {
                unparsed += "table item `$item`"
                continue
            }
            // A column definition: its name is the first token, and only `PRIMARY KEY` / `UNIQUE` after the
            // type change the key set. Strings are blanked first so a COMMENT cannot look like a constraint.
            val bare = stripLiterals(item).flatten()
            val column = identifierOf(bare.substringBefore(' '))
            if (column == null) {
                unparsed += "column token `$item`"
                continue
            }
            state.columns.add(column)
            when {
                INLINE_PK.containsMatchIn(bare) -> state.add(PRIMARY, listOf(column), true)
                // `key_hash VARCHAR(64) NOT NULL UNIQUE` (V1's api_key) creates a unique index MySQL names
                // after the column, which the baseline spells out as `UNIQUE KEY key_hash (key_hash)`.
                INLINE_UNIQUE.containsMatchIn(bare) -> state.add(column, listOf(column), true)
                KEY_TOKEN.containsMatchIn(bare) -> unparsed += "column line `$item`"
            }
        }
    }

    /** `from` minus `to` over the tables both name; a table only one side has is the existence check's job. */
    private fun keyDrift(from: Map<String, Map<String, Key>>, to: Map<String, Map<String, Key>>): List<KeyDrift> {
        val drift = mutableListOf<KeyDrift>()
        for ((table, keys) in from.toSortedMap()) {
            val against = to[table] ?: continue
            for ((name, key) in keys.toSortedMap()) {
                val other = against[name]
                if (other != key) drift += KeyDrift(table, name, key, other)
            }
        }
        return drift
    }

    private fun List<KeyDrift>.rejectTolerated(): List<KeyDrift> = filterNot { it.id in TOLERATED_KEY_DRIFT }

    // ── bounded SQL scanning ──
    //
    // Quote and parenthesis aware throughout: a `DEFAULT ''` or a `DECIMAL(10, 4)` must never be read as a
    // clause boundary, and a COMMENT is free to contain anything from a `--` to the word UNIQUE.

    /** Index of the character just past the quote opened at [start]. */
    private fun skipQuoted(text: String, start: Int): Int {
        val quote = text[start]
        var i = start + 1
        while (i < text.length) {
            val c = text[i]
            if (quote != '`' && c == '\\') {
                i += 2
                continue
            }
            if (c == quote) {
                if (quote != '`' && i + 1 < text.length && text[i + 1] == quote) {
                    i += 2
                    continue
                }
                return i + 1
            }
            i++
        }
        return text.length
    }

    private fun indexOfOpenParen(text: String, from: Int): Int {
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (c == '\'' || c == '"' || c == '`') {
                i = skipQuoted(text, i)
                continue
            }
            if (c == '(') return i
            i++
        }
        return -1
    }

    private fun indexOfCloseParen(text: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < text.length) {
            val c = text[i]
            if (c == '\'' || c == '"' || c == '`') {
                i = skipQuoted(text, i)
                continue
            }
            when (c) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    /** Splits on [separator] at depth 0, outside quotes and backticks. */
    private fun splitTopLevel(text: String, separator: Char): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\'' || c == '"' || c == '`') {
                val end = skipQuoted(text, i)
                current.append(text, i, end)
                i = end
                continue
            }
            when (c) {
                '(' -> depth++
                ')' -> depth--
            }
            if (c == separator && depth == 0) {
                parts += current.toString()
                current.setLength(0)
            } else {
                current.append(c)
            }
            i++
        }
        parts += current.toString()
        return parts
    }

    private fun stripSqlComments(sql: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            if (c == '\'' || c == '"' || c == '`') {
                val end = skipQuoted(sql, i)
                out.append(sql, i, end)
                i = end
                continue
            }
            if (c == '-' && sql.startsWith("--", i) && (i + 2 >= sql.length || sql[i + 2] in "-- \t\r\n")) {
                val newline = sql.indexOf('\n', i)
                i = if (newline < 0) sql.length else newline
                continue
            }
            if (c == '/' && sql.startsWith("/*", i)) {
                val end = sql.indexOf("*/", i + 2)
                i = if (end < 0) sql.length else end + 2
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun stripLiterals(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\'' || c == '"') {
                out.append("''")
                i = skipQuoted(text, i)
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** Statements a session variable carries as a string rather than inline (see V28). */
    private fun embeddedDdl(text: String): List<String> {
        val found = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            if (text[i] != '\'') {
                i++
                continue
            }
            val end = skipQuoted(text, i)
            if (end > i + 1) {
                val body = text.substring(i + 1, end - 1).replace("''", "'").trim()
                if (TABLE_DDL_BODY_PREFIX.containsMatchIn(body)) found += body
            }
            i = end
        }
        return found
    }

    /** Table name plus body of a `CREATE TABLE`, or null when [flat] is some other statement. */
    private fun createTableOf(flat: String): Pair<String, String>? {
        val head = CREATE_TABLE_HEAD.find(flat) ?: return null
        val open = indexOfOpenParen(flat, head.range.last + 1)
        val close = if (open < 0) -1 else indexOfCloseParen(flat, open)
        if (close < 0) return null
        val table = tableNameOf(flat.substring(head.range.last + 1, open)) ?: return null
        return table to flat.substring(open + 1, close)
    }

    private fun columnListOf(inner: String, unparsed: MutableList<String>): List<String> {
        val columns = mutableListOf<String>()
        for (piece in splitTopLevel(inner, ',').map { it.flatten() }) {
            val token = COLUMN_TOKEN.find(piece)
            val name = token?.let { match ->
                identifierOf(match.groupValues[1].ifEmpty { match.groupValues[2] })
            }
            if (name == null) unparsed += "key column token `$piece`" else columns += name
        }
        return columns
    }

    private fun identifierOf(token: String): String? {
        val plain = token.trim().removeSurrounding("`")
        return plain.takeIf { IDENTIFIER.matches(it) }?.lowercase()
    }

    private fun tableNameOf(token: String): String? = identifierOf(IF_EXISTS.replace(token, "").trim())

    private fun String.flatten(): String = replace(WHITESPACE, " ").trim()

    /** Migrations in the order Flyway applies them, which is the order the replay has to follow. */
    private fun migrationFiles(): List<File> = migrationDir().listFiles { _, name -> MIGRATION_FILE.matches(name) }?.toList()
        ?.sortedBy { MIGRATION_FILE.find(it.name)!!.groupValues[1].toInt() }
        .orEmpty()

    private fun migrationDir(): File {
        val relative = Paths.get("src", "main", "resources", "db", "migration")
        val candidates = listOf(
            relative,
            Paths.get("..").resolve(Paths.get("harnax-admin").resolve(relative)),
            Paths.get(System.getProperty("basedir") ?: ".", relative.toString()),
        )
        val dir = candidates.firstOrNull { it.toFile().isDirectory }
        return requireNotNull(dir) {
            "db/migration not found from ${Paths.get("").toAbsolutePath()}, looked at $candidates"
        }.toFile()
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

        // ── the shapes the key replay models, and nothing else ──
        //
        // Every statement the tree contains is matched by exactly one of these, and `the migration replay
        // understands every index statement in the tree` fails if a new one turns up. Patterns that must
        // cover a whole clause end in `$` and go through `matchEntire`; the keyword-only classifiers are
        // anchored prefixes named `_PREFIX`, tested with `containsMatchIn`, and the three column clauses
        // carry `(?:\s+.*)?$` because a real one goes on to name the type.

        val CREATE_TABLE_HEAD = Regex("^CREATE\\s+TABLE\\b", RegexOption.IGNORE_CASE)
        val ALTER_TABLE = Regex("^ALTER\\s+TABLE\\s+(\\S+)\\s+(.*)$", RegexOption.IGNORE_CASE)
        val RENAME_TABLE = Regex("^RENAME\\s+TABLE\\s+(.*)$", RegexOption.IGNORE_CASE)
        val DROP_TABLE = Regex("^DROP\\s+TABLE\\b(.*)$", RegexOption.IGNORE_CASE)
        val NO_KEY_EFFECT_PREFIX = Regex("^(INSERT|UPDATE|DELETE|SELECT|TRUNCATE)\\b", RegexOption.IGNORE_CASE)
        val SESSION_DDL_PREFIX = Regex("^(SET|PREPARE|EXECUTE|DEALLOCATE)\\b", RegexOption.IGNORE_CASE)
        val TABLE_DDL_BODY_PREFIX = Regex("^(ALTER|CREATE|DROP)\\s+TABLE\\b", RegexOption.IGNORE_CASE)
        val RENAME_PAIR = Regex("(`[^`]+`|[A-Za-z0-9_$]+)\\s+TO\\s+(`[^`]+`|[A-Za-z0-9_$]+)", RegexOption.IGNORE_CASE)

        val ALTER_ADD_KEY = Regex(
            "^ADD\\s+(UNIQUE\\s+)?(?:KEY|INDEX)\\s+(`[^`]+`|[A-Za-z0-9_$]+)\\s*\\((.*)\\)$",
            RegexOption.IGNORE_CASE,
        )
        val ALTER_DROP_KEY = Regex("^DROP\\s+(?:KEY|INDEX)\\s+(`[^`]+`|[A-Za-z0-9_$]+)$", RegexOption.IGNORE_CASE)
        val RENAME_KEY_PREFIX = Regex("^RENAME\\s+(?:KEY|INDEX)\\b", RegexOption.IGNORE_CASE)
        val ALTER_CHANGE_COLUMN = Regex(
            "^CHANGE\\s+(?:COLUMN\\s+)?(`[^`]+`|[A-Za-z0-9_$]+)\\s+(`[^`]+`|[A-Za-z0-9_$]+)(?:\\s+.*)?$",
            RegexOption.IGNORE_CASE,
        )
        val ALTER_DROP_COLUMN = Regex(
            "^DROP\\s+COLUMN\\s+(`[^`]+`|[A-Za-z0-9_$]+)(?:\\s+.*)?$",
            RegexOption.IGNORE_CASE,
        )
        val ALTER_ADD_COLUMN = Regex(
            "^ADD\\s+COLUMN\\s+(`[^`]+`|[A-Za-z0-9_$]+)(?:\\s+.*)?$",
            RegexOption.IGNORE_CASE,
        )
        val COLUMN_ONLY_ACTION_PREFIX = Regex("^(ADD|DROP|CHANGE|MODIFY|ALTER)\\b", RegexOption.IGNORE_CASE)
        val TABLE_ONLY_ACTION_PREFIX = Regex(
            "^(RENAME|AUTO_INCREMENT|COMMENT|ENGINE|DEFAULT|CHARACTER|COLLATE|CONVERT|LOCK|ALGORITHM)\\b",
            RegexOption.IGNORE_CASE,
        )

        val PRIMARY_KEY_ITEM = Regex("^PRIMARY\\s+KEY\\s*\\((.*)\\)$", RegexOption.IGNORE_CASE)
        val KEY_ITEM = Regex(
            "^(UNIQUE\\s+)?(KEY|INDEX)\\s+(`[^`]+`|[A-Za-z0-9_$]+)\\s*\\((.*)\\)$",
            RegexOption.IGNORE_CASE,
        )
        val CONSTRAINT_PREFIX = Regex(
            "^(CONSTRAINT|FOREIGN\\s+KEY|FULLTEXT|SPATIAL|CHECK|PERIOD)\\b",
            RegexOption.IGNORE_CASE,
        )
        val INLINE_PK = Regex("\\bPRIMARY\\s+KEY\\b", RegexOption.IGNORE_CASE)
        val INLINE_UNIQUE = Regex("\\bUNIQUE(\\s+KEY)?\\b", RegexOption.IGNORE_CASE)
        val KEY_TOKEN = Regex("\\b(?:KEY|INDEX)\\b", RegexOption.IGNORE_CASE)
        val COLUMN_TOKEN = Regex("^(`[^`]+`|[A-Za-z0-9_$]+)", RegexOption.IGNORE_CASE)

        val IDENTIFIER = Regex("[A-Za-z0-9_$]+")
        val IF_EXISTS = Regex("^\\s*(?:IF\\s+NOT\\s+EXISTS|IF\\s+EXISTS)\\s+", RegexOption.IGNORE_CASE)
        val WHITESPACE = Regex("\\s+")
        val MIGRATION_FILE = Regex("^V(\\d+)__.*\\.sql$")

        /** Key name for the clustered index; MySQL spells it `PRIMARY`, both schema sources agree on it. */
        const val PRIMARY = "primary"

        /**
         * Key drift the guard reports and this commit does not repair.
         *
         * All 27 items predate the key comparison: V43, V45, V46, V49 and V50 are already identical on both
         * sides, which is what let the uniqueness rounds pass the old table-and-column check. Each entry is
         * a real mismatch rather than an exception to the rule, so each line states what makes it invisible
         * to a test today and which edit removes it. `the tolerated key drift list still describes real
         * drift` fails as soon as one stops being true, so a fixed item cannot outlive its fix — and nothing
         * here may grow to cover a fresh change, which has to be mirrored instead of listed.
         */
        val TOLERATED_KEY_DRIFT = setOf(
            // Same columns, same uniqueness, different name: the baseline follows `idx_<table>_<column>`
            // where the migration named the key `idx_<column>`. MySQL chooses the identical plan either way
            // and no statement in this repo names an index — `USE`, `FORCE` and `IGNORE INDEX` appear
            // nowhere — so nothing observable differs until somebody asserts on a plan.
            "team.idx_tenant_id", // name-only pair of baseline's idx_team_tenant_id; rename that baseline key to remove
            "team_member.idx_member_agent_id", // name-only pair of idx_team_member_agent_id; rename that baseline key to remove
            "team_artifact.idx_session_id", // name-only pair of idx_team_artifact_session_id; rename that baseline key to remove
            "team_artifact.idx_tenant_id", // name-only pair of idx_team_artifact_tenant_id; rename that baseline key to remove
            "token_stats.idx_agent_id", // name-only pair of idx_token_stats_agent_id; rename that baseline key to remove
            "token_stats.idx_chat_model_id", // name-only pair of idx_token_stats_chat_model_id; rename that baseline key to remove
            "token_stats.idx_session_id", // name-only pair of idx_token_stats_session_id; rename that baseline key to remove
            "token_stats.idx_ts", // name-only pair of idx_token_stats_ts; rename that baseline key to remove
            "team.idx_team_tenant_id", // the baseline-side half of the name-only pair above; rename it to idx_tenant_id to remove
            "team_member.idx_team_member_agent_id", // baseline-side half of a name-only pair; rename it to idx_member_agent_id to remove
            "team_artifact.idx_team_artifact_session_id", // baseline-side half of a name-only pair; rename it to idx_session_id to remove
            "team_artifact.idx_team_artifact_tenant_id", // baseline-side half of a name-only pair; rename it to idx_tenant_id to remove
            "token_stats.idx_token_stats_agent_id", // baseline-side half of a name-only pair; rename it to idx_agent_id to remove
            "token_stats.idx_token_stats_chat_model_id", // baseline-side half of a name-only pair; rename it to idx_chat_model_id to remove
            "token_stats.idx_token_stats_session_id", // baseline-side half of a name-only pair; rename it to idx_session_id to remove
            "token_stats.idx_token_stats_ts", // baseline-side half of a name-only pair; rename it to idx_ts to remove
            // Unique keys the fixture invented: production accepts the second row the baseline refuses, so
            // a test that leans on one is testing the fixture. No mapper test names any of them today —
            // nothing in `harnax-entity/src/test` asserts a duplicate-key error on these — so each only
            // needs its line deleted, which is a baseline change of its own and does not ride in here.
            "model_provider.uk_type", // V1 gave model_provider no key on `type` at all; delete the baseline line to remove
            "sys_token_blacklist.uk_token_hash", // V1 keyed this on idx_token_lookup (token_hash, expire_time), non-unique; delete to remove
            "sys_user.uk_email", // no migration ever made email unique; delete the baseline line to remove
            "sys_user.uk_phone", // no migration ever made phone unique; delete the baseline line to remove
            "tenant.uk_name", // V1's tenant carries a primary key and nothing else; delete the baseline line to remove
            "user_tenant.uk_user_tenant", // V1 gave user_tenant two plain indexes, no unique pair; delete to remove
            // Indexes V1 and V16 created and the baseline never mirrored. A missing index costs speed,
            // never a row, so no mapper test can tell; both remove by copying the KEY line across.
            "agent_skill_binding.idx_agent_skill_binding_skill_id", // V16's skill_id index, absent from the fixture; add it to remove
            "sys_token_blacklist.idx_token_lookup", // V1's (token_hash, expire_time) lookup index; add it, and drop uk_token_hash with it
            "sys_token_blacklist.idx_username", // V1's username index; add it to remove
            // V33 replaced idx_agent_skill_binding_agent_id with UNIQUE (agent_id, skill_id), and the
            // baseline header for this table says the unique key is kept out on purpose so the duplicate
            // rows the guards must tolerate stay testable. Both entries end together, when those rows move
            // to a table with no such claim or the deviation is dropped.
            "agent_skill_binding.uk_agent_skill_binding_agent_id_skill_id", // the UNIQUE V33 added, excluded from the fixture by design
            "agent_skill_binding.idx_agent_skill_binding_agent_id", // the key V33 dropped, kept by that same design; goes with the line above
        )
    }
}

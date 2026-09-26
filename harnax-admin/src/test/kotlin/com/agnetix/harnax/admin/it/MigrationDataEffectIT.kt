package com.agnetix.harnax.admin.it

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.MySQLContainer
import java.sql.DriverManager

/**
 * The data effects of the backfill and uniqueness migrations, replayed against rows that actually exist.
 *
 * Every other IT in this module migrates an empty schema once and then tests the application, so the
 * `UPDATE` statements in V43, V44, V47, V48 and V49 have never been observed doing anything: they ran
 * against zero rows. Each of those statements carries a documented rule — which duplicate keeps its name,
 * which row is deliberately left alone — and none of it was checkable, so a wrong `WHERE` clause would have
 * shipped as a silent no-op or as a mass rewrite of live data.
 *
 * A migration is only testable in the window around its own version, so each case opens its own schema,
 * stops Flyway at `V(n-1)`, seeds the rows the statement has to tell apart, applies `V(n)` alone, and reads
 * the rows back. Seeding outside the application is deliberate: the app now writes the correct tenant on
 * insert (that is what V50's guards do), so an API-seeded fixture could not produce the misattribution these
 * statements exist to repair.
 *
 * V49 is the one case where the failure is the contract, so it also gets the opposite check: two live
 * accounts sharing a name must stop the migration rather than let it pick one.
 */
class MigrationDataEffectIT {

    // ── V43: agent name uniqueness per tenant ──

    @Test
    fun `V43 keeps the oldest live name per tenant and renames the rest with a suffixed id`() = case(
        schema = "mig_v43",
        steps = listOf(
            "42" to { db ->
                db.exec(
                    agents(
                        row(901, 5, "alpha", active = 1),
                        row(902, 5, "alpha", active = 1),
                        row(903, 5, "alpha", active = 1),
                        row(904, 6, "alpha", active = 1),
                        row(905, 5, "alpha", active = 0),
                    ),
                    "INSERT INTO agent (id, tenant_id, name, active, update_time) " +
                        "VALUES (906, 5, REPEAT('x', 100), 1, '2020-01-01 00:00:00'), " +
                        "       (907, 5, REPEAT('x', 100), 1, '2020-01-01 00:00:00')",
                )
            },
            "43" to null,
        ),
    ) { db ->
        assertEquals("alpha", db.value("SELECT name FROM agent WHERE id = 901"), "the oldest row of the tenant keeps its name")
        assertEquals("alpha#dup-902", db.value("SELECT name FROM agent WHERE id = 902"))
        assertEquals("alpha#dup-903", db.value("SELECT name FROM agent WHERE id = 903"))
        assertEquals("alpha", db.value("SELECT name FROM agent WHERE id = 904"), "another tenant's same-named agent is not a duplicate")
        assertEquals("alpha", db.value("SELECT name FROM agent WHERE id = 905"), "soft-deleted rows are outside the partition")
        assertEquals(
            db.value("SELECT CONCAT(REPEAT('x', 80), '#dup-907') FROM agent WHERE id = 907"),
            db.value("SELECT name FROM agent WHERE id = 907"),
            "the rename truncates to 80 characters before appending the suffix",
        )
        assertEquals("7", db.value("SELECT COUNT(*) FROM agent WHERE id BETWEEN 901 AND 907"), "nothing is deleted here")
        assertEquals("1", db.value("SELECT update_time > '2020-01-02' FROM agent WHERE id = 902"), "renamed rows record the rewrite")
        assertEquals("0", db.value("SELECT update_time > '2020-01-02' FROM agent WHERE id = 901"), "untouched rows keep their timestamp")

        assertTrue(
            db.hasKey("agent", "uk_agent_tenant_active_name"),
            "V43 must leave the unique key behind, or the repair has no teeth",
        )
        assertTrue(
            !db.hasKey("agent", "idx_tenant_id"),
            "tenant_id is the leftmost prefix of the new key, so the old index should be gone",
        )
        // The rule the whole round exists for, now enforced by the schema rather than by the service.
        db.expectRejected("INSERT INTO agent (id, tenant_id, name, active) VALUES (950, 5, 'alpha', 1)")
        // ... and the soft-delete escape hatch: 901 was the live holder of that name in tenant 5.
        db.exec("UPDATE agent SET active = 0 WHERE id = 901")
        db.exec("INSERT INTO agent (id, tenant_id, name, active) VALUES (951, 5, 'alpha', 1)")
        assertEquals(1, db.value("SELECT COUNT(*) FROM agent WHERE id = 951")!!.toInt())
    }

    // ── V44: model and model_provider move to the tenant that created them ──

    @Test
    fun `V44 attributes rows to their creator tenant and makes a private model follow its provider`() = case(
        schema = "mig_v44",
        steps = listOf(
            "43" to { db ->
                db.exec(
                    "INSERT INTO sys_user (id, tenant_id, username, password, nickname, email, phone, active) " +
                        "VALUES (701, 7, 'it_owner7', 'x', 'owner7', 'o7@it.test', '', 1)",
                    providers(
                        prow(801, 1, "it_p801", creator = "it_owner7"),
                        prow(802, 1, "it_p802", creator = "it_nobody"),
                    ),
                    "INSERT INTO model (id, tenant_id, name, model_name, provider_id, model_type, is_public, creator, update_time) " +
                        "VALUES (901, 1, 'it_m901', 'm901', 801, 'chat', 1, 'it_owner7', '2020-01-01 00:00:00'), " +
                        "       (902, 5, 'it_m902', 'm902', 802, 'chat', 0, 'it_owner7', '2020-01-01 00:00:00'), " +
                        "       (903, 5, 'it_m903', 'm903', 802, 'chat', 1, 'it_owner7', '2020-01-01 00:00:00'), " +
                        "       (904, 5, 'it_m904', 'm904', 802, 'chat', 1, 'it_nobody', '2020-01-01 00:00:00')",
                )
            },
            "44" to null,
        ),
    ) { db ->
        assertEquals("7", db.value("SELECT tenant_id FROM model_provider WHERE id = 801"), "creator's primary tenant wins")
        assertEquals("1", db.value("SELECT tenant_id FROM model_provider WHERE id = 802"), "a creator with no account matches no join")
        assertEquals("7", db.value("SELECT tenant_id FROM model WHERE id = 901"))
        assertEquals(
            "1",
            db.value("SELECT tenant_id FROM model WHERE id = 902"),
            "a private model ends up on its provider's tenant even after the creator rule moved it",
        )
        assertEquals(
            "7",
            db.value("SELECT tenant_id FROM model WHERE id = 903"),
            "the provider rule is limited to private models, so a public one keeps its creator's tenant",
        )
        assertEquals("5", db.value("SELECT tenant_id FROM model WHERE id = 904"), "no creator, no move")
        assertEquals(
            "0",
            db.value("SELECT update_time > '2020-01-02' FROM model WHERE id = 904"),
            "a row no rule matches keeps the timestamp it was seeded with",
        )
        // model.update_time is `ON UPDATE CURRENT_TIMESTAMP`, so a value-changing attribution UPDATE cannot
        // help but stamp the row. V49's header claims the opposite for the same UPDATE it re-runs here, and a
        // migration already applied cannot be edited to correct the wording — so this asserts the schema.
        assertEquals(
            "1",
            db.value("SELECT update_time > '2020-01-02' FROM model WHERE id = 902"),
            "attribution rewrites the row, and the column's ON UPDATE clause stamps that rewrite",
        )
    }

    // ── V47: defaulted channel rows move to their agent's tenant ──

    @Test
    fun `V47 moves only the channels that fell back to the default tenant`() = case(
        schema = "mig_v47",
        steps = listOf(
            "46" to { db ->
                db.exec(
                    agents(
                        row(1201, 9, "it_a1201", active = 1),
                        row(1202, 1, "it_a1202", active = 1),
                    ),
                    "INSERT INTO channel (id, tenant_id, name, type, agent_id, callback_key, session_id) " +
                        "VALUES (1301, 1, 'it_c1301', 'feishu', 1201, 'ck-1301', 's-1301'), " +
                        "       (1302, 1, 'it_c1302', 'feishu', 1202, 'ck-1302', 's-1302'), " +
                        "       (1303, 4, 'it_c1303', 'feishu', 1201, 'ck-1303', 's-1303'), " +
                        "       (1304, 1, 'it_c1304', 'feishu', 999999, 'ck-1304', 's-1304')",
                )
            },
            "47" to null,
        ),
    ) { db ->
        assertEquals(
            "9",
            db.value("SELECT tenant_id FROM channel WHERE id = 1301"),
            "a channel left at the DDL default belongs to the tenant of the agent it exposes",
        )
        assertEquals("1", db.value("SELECT tenant_id FROM channel WHERE id = 1302"), "already right, so already a no-op")
        assertEquals(
            "4",
            db.value("SELECT tenant_id FROM channel WHERE id = 1303"),
            "a row naming another tenant was written by a request that carried a workspace header; that call stands",
        )
        assertEquals("1", db.value("SELECT tenant_id FROM channel WHERE id = 1304"), "an agent that no longer exists attributes nothing")
    }

    // ── V48: the team lead's agent id 0 sentinel becomes a real absence ──

    @Test
    fun `V48 washes the lead sentinel to NULL in all three tables and leaves numbered rows alone`() = case(
        schema = "mig_v48",
        steps = listOf(
            "47" to { db ->
                db.exec(
                    "INSERT INTO token_stats (id, agent_id, session_id, chat_model_id, total_token, ts) " +
                        "VALUES (1401, 0, 'lead-session', 1, 10, '2020-01-01 00:00:00'), " +
                        "       (1402, 5, 'agent-session', 1, 20, '2020-01-01 00:00:00')",
                    "INSERT INTO tool_call_log (id, agent_id, session_id, tool_name, ts) " +
                        "VALUES (1501, 0, 'lead-session', 'team::members', '2020-01-01 00:00:00'), " +
                        "       (1502, 5, 'agent-session', 'shell', '2020-01-01 00:00:00')",
                    "INSERT INTO process_log (id, agent_id, agent_name, session_id, message, ts) " +
                        "VALUES (1601, 0, 'it-lead', 'lead-session', 'planned', '2020-01-01 00:00:00'), " +
                        "       (1602, 5, 'it-bot', 'agent-session', 'planned', '2020-01-01 00:00:00')",
                )
            },
            "48" to null,
        ),
    ) { db ->
        for (table in listOf("token_stats", "tool_call_log", "process_log")) {
            assertNull(db.value("SELECT agent_id FROM $table WHERE id IN (1401, 1501, 1601) AND agent_id IS NOT NULL"))
            assertEquals("2", db.value("SELECT COUNT(*) FROM $table"), "the wash must not drop or duplicate rows")
        }
        assertTrue(
            db.rows("SELECT agent_id FROM token_stats WHERE id = 1401").first().first() == null,
            "a lead's token row must stop counting as an extra agent",
        )
        assertEquals("5", db.value("SELECT agent_id FROM token_stats WHERE id = 1402"), "only 0 moves; a real agent keeps its attribution")
        assertEquals("5", db.value("SELECT agent_id FROM tool_call_log WHERE id = 1502"))
        assertEquals("5", db.value("SELECT agent_id FROM process_log WHERE id = 1602"))
        assertEquals(
            "it-lead",
            db.value("SELECT agent_name FROM process_log WHERE id = 1601"),
            "agent_name is denormalised and stays as written even once the id is gone",
        )
    }

    // ── V49: one live account per name, then the V44 attribution re-read on top of it ──

    @Test
    fun `V49 re-reads attribution through the live account and refuses to guess a deleted one`() = case(
        schema = "mig_v49",
        steps = listOf(
            // Seeded before V44 so V44 really runs against them: it joined sys_user unfiltered, which is
            // the behaviour this migration corrects.
            "43" to { db ->
                db.exec(
                    "INSERT INTO sys_user (id, tenant_id, username, password, nickname, email, phone, active) " +
                        "VALUES (711, 9, 'it_both', 'x', 'gone', 'both-gone@it.test', '', 0), " +
                        "       (712, 8, 'it_ghost', 'x', 'ghost', 'ghost@it.test', '', 0)",
                    providers(
                        prow(811, 1, "it_p811", creator = "it_both"),
                        prow(812, 1, "it_p812", creator = "it_ghost"),
                    ),
                    "INSERT INTO model (id, tenant_id, name, model_name, provider_id, model_type, is_public, creator, update_time) " +
                        "VALUES (911, 1, 'it_m911', 'm911', 811, 'chat', 1, 'it_both', '2020-01-01 00:00:00')",
                )
            },
            "44" to { db ->
                // V44 had only the deleted 'it_both' to join, and it did move the rows to that account.
                assertEquals("9", db.value("SELECT tenant_id FROM model_provider WHERE id = 811"))
                assertEquals("9", db.value("SELECT tenant_id FROM model WHERE id = 911"))
                assertEquals("8", db.value("SELECT tenant_id FROM model_provider WHERE id = 812"))
            },
            // A live account with the same name appears after V44; the new key has to allow that because the
            // other row is deleted.
            "48" to { db ->
                db.exec(
                    "INSERT INTO sys_user (id, tenant_id, username, password, nickname, email, phone, active) " +
                        "VALUES (713, 3, 'it_both', 'x', 'live', 'both-live@it.test', '', 1)",
                )
            },
            "49" to null,
        ),
    ) { db ->
        assertEquals(
            "3",
            db.value("SELECT tenant_id FROM model_provider WHERE id = 811"),
            "the re-read is what moves data off a deleted account's tenant onto the live one",
        )
        assertEquals("3", db.value("SELECT tenant_id FROM model WHERE id = 911"))
        assertEquals(
            "8",
            db.value("SELECT tenant_id FROM model_provider WHERE id = 812"),
            "a creator with no live account keeps whatever V44 gave it rather than being moved on a guess",
        )
        assertTrue(db.hasKey("sys_user", "uk_active_username"))
        db.expectRejected(
            "INSERT INTO sys_user (id, tenant_id, username, password, nickname, email, phone, active) " +
                "VALUES (714, 3, 'IT_BOTH', 'x', 'case', 'both-case@it.test', '', 1)",
        )
        db.exec("UPDATE sys_user SET active = 0 WHERE id = 713")
        db.exec(
            "INSERT INTO sys_user (id, tenant_id, username, password, nickname, email, phone, active) " +
                "VALUES (715, 3, 'it_both', 'x', 'again', 'both-again@it.test', '', 1)",
        )
        assertEquals(
            "1",
            db.value("SELECT update_time > '2020-01-02' FROM model WHERE id = 911"),
            "V49 re-runs the same UPDATE, so the row's ON UPDATE CURRENT_TIMESTAMP clause stamps it again",
        )
    }

    @Test
    fun `V49 stops rather than choose between two live accounts sharing a name`() {
        val schema = "mig_v49_dup"
        createSchema(schema)
        migrate(schema, "48")
        Db(schema).exec(
            "INSERT INTO sys_user (id, tenant_id, username, password, nickname, email, phone, active) " +
                "VALUES (721, 1, 'it_twin', 'x', 'one', 'twin-one@it.test', '', 1), " +
                "       (722, 2, 'it_twin', 'x', 'two', 'twin-two@it.test', '', 1)",
        )
        val failure = assertThrows(FlywayException::class.java) { migrate(schema, "49") }
        assertTrue(
            failure.message.orEmpty().contains("uk_active_username", ignoreCase = true) ||
                failure.message.orEmpty().contains("Duplicate", ignoreCase = true),
            "the ALTER is meant to fail on live collisions, got: ${failure.message}",
        )
        // Nothing half-applied: the key is what the statement adds, and it must not be there.
        assertTrue(!Db(schema).hasKey("sys_user", "uk_active_username"))
        assertEquals("2", Db(schema).value("SELECT COUNT(*) FROM sys_user WHERE username = 'it_twin'"))
    }

    // ── the harness ──

    /**
     * Replays [steps] in order — each entry stops Flyway at that version and then runs its seeding or
     * checking block against the schema in that state — and finishes with [verify].
     */
    private fun case(
        schema: String,
        steps: List<Pair<String, ((Db) -> Unit)?>>,
        verify: (Db) -> Unit,
    ) {
        createSchema(schema)
        for ((target, action) in steps) {
            migrate(schema, target)
            action?.invoke(Db(schema))
        }
        verify(Db(schema))
    }

    private fun createSchema(schema: String) {
        DriverManager.getConnection(mysql.jdbcUrl, mysql.username, mysql.password).use { connection ->
            connection.createStatement().use {
                it.execute("CREATE DATABASE IF NOT EXISTS `$schema` DEFAULT CHARACTER SET utf8mb4")
            }
        }
    }

    private fun migrate(schema: String, target: String) {
        val result = Flyway.configure()
            .dataSource(url(schema), mysql.username, mysql.password)
            .locations("classpath:db/migration")
            .target(target)
            .load()
            .migrate()
        assertTrue(result.success, "migrating $schema to V$target reported no failure but applied nothing new")
    }

    /**
     * Testcontainers hands out a real JDBC url naming its own schema, with the driver options after the
     * `?`; only the database segment changes per case, so the options the container chose carry over.
     */
    private fun url(schema: String): String {
        val base = mysql.jdbcUrl
        val head = base.substringBefore('?')
        val options = base.substringAfter('?', missingDelimiterValue = "")
        return "${head.substringBeforeLast('/')}/$schema" + if (options.isEmpty()) "" else "?$options"
    }

    private fun agents(vararg rows: String): String = "INSERT INTO agent (id, tenant_id, name, active, update_time) VALUES " + rows.joinToString(", ")

    private fun row(id: Long, tenantId: Long, name: String, active: Int): String = "($id, $tenantId, '$name', $active, '2020-01-01 00:00:00')"

    private fun providers(vararg rows: String): String = "INSERT INTO model_provider (id, tenant_id, type, name, creator) VALUES " + rows.joinToString(", ")

    private fun prow(id: Long, tenantId: Long, name: String, creator: String): String = "($id, $tenantId, '$name', '$name', '$creator')"

    /** One schema at one point in the migration chain. Values come back as MySQL renders them. */
    private inner class Db(private val schema: String) {

        fun exec(vararg statements: String) {
            connect().use { connection ->
                statements.forEach { statement ->
                    connection.createStatement().use { it.execute(statement) }
                }
            }
        }

        /** The first column of the first row, or null when the query matched no row. */
        fun value(sql: String): String? = rows(sql).firstOrNull()?.first()

        fun rows(sql: String): List<List<String?>> = connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).let { result ->
                    val width = result.metaData.columnCount
                    buildList {
                        while (result.next()) {
                            add((1..width).map { result.getObject(it)?.toString() })
                        }
                    }
                }
            }
        }

        fun hasKey(table: String, key: String): Boolean = rows(
            "SELECT COUNT(*) FROM information_schema.statistics " +
                "WHERE table_schema = DATABASE() AND table_name = '$table' AND index_name = '$key'",
        ).first().first()!!.toInt() > 0

        /** A write the schema is supposed to refuse; returning quietly is the failure this guards against. */
        fun expectRejected(sql: String) {
            val thrown = runCatching { exec(sql) }.exceptionOrNull()
            assertTrue(
                thrown != null && thrown.message.orEmpty().contains("Duplicate", ignoreCase = true),
                "expected the unique key to reject this write, got: ${thrown?.message ?: "no error at all"}",
            )
        }

        private fun connect() = DriverManager.getConnection(url(schema), mysql.username, mysql.password)
    }

    companion object {
        // Its own container rather than the shared application one: these cases need seven schemas on the
        // same server, and none of them may see the schema the Spring context migrated.
        @JvmStatic
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.0")
            .withDatabaseName("harnax_migrations")
            .withUsername("root")
            .withPassword("it_test")

        init {
            mysql.start()
        }
    }
}

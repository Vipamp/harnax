package com.agnetix.harnax.agent.session

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Tests [MysqlSessionMessageStore] against a real MySQL 8, which is the engine the session database runs on.
 *
 * H2 in MySQL mode is not enough here: MySQL evaluates the assignments of an `ON DUPLICATE KEY UPDATE` left to
 * right, so a clause that rewrites `json_value` first makes every later clause compare against the NEW body. That
 * is the difference between a row whose columns all describe the fullest version of a message and a row whose body
 * is fullest while `role` and `msg_name` still describe the first one.
 */
@Testcontainers(disabledWithoutDocker = true)
class MysqlSessionMessageStoreMySQL8Test {

    companion object {
        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.0")
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var store: MysqlSessionMessageStore

    @BeforeEach
    fun setUp() {
        dataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = mysql.jdbcUrl
                username = mysql.username
                password = mysql.password
                driverClassName = "com.mysql.cj.jdbc.Driver"
                maximumPoolSize = 2
            },
        )
        store = MysqlSessionMessageStore(dataSource, "session_message", createIfNotExist = true)
    }

    @AfterEach
    fun tearDown() {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DROP TABLE IF EXISTS session_message").use { it.executeUpdate() }
        }
        dataSource.close()
    }

    private fun msg(
        id: String,
        role: MsgRole,
        name: String,
        text: String,
    ): Msg = Msg.builder()
        .id(id)
        .role(role)
        .name(name)
        .textContent(text)
        .timestamp("2026-10-05T00:00:00Z")
        .build()

    /** The stored body length with the role and name that were written together with it. */
    private fun row(id: String): Triple<Int, String, String> = dataSource.connection.use { conn ->
        conn.prepareStatement(
            "SELECT CHAR_LENGTH(json_value), role, msg_name FROM session_message WHERE msg_id = ?",
        ).use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs ->
                rs.next()
                Triple(rs.getInt(1), rs.getString(2), rs.getString(3))
            }
        }
    }

    @Test
    fun `a rewritten message keeps every column of its fullest version`() {
        store.archive("", "s-1", listOf(msg("m-1", MsgRole.TOOL, "v1", "t".repeat(100))))
        store.archive("", "s-1", listOf(msg("m-1", MsgRole.ASSISTANT, "v2", "t".repeat(500))))

        val afterFuller = row("m-1")
        assertEquals("ASSISTANT", afterFuller.second, "role must follow the fullest version")
        assertEquals("v2", afterFuller.third, "msg_name must follow the fullest version")
        val fullerLength = afterFuller.first

        store.archive("", "s-1", listOf(msg("m-1", MsgRole.USER, "v3", "t".repeat(10))))

        val afterShorter = row("m-1")
        assertEquals(fullerLength, afterShorter.first, "a shorter version must not shrink the stored body")
        assertEquals("ASSISTANT", afterShorter.second, "a shorter version must not rewrite the role")
        assertEquals("v2", afterShorter.third, "a shorter version must not rewrite the name")
    }

    @Test
    fun `another owner bucket holds its own row for the same message id`() {
        store.archive("", "s-1", listOf(msg("m-1", MsgRole.USER, "anon", "t".repeat(50))))
        store.archive("42", "s-1", listOf(msg("m-1", MsgRole.USER, "other", "t".repeat(50))))

        assertEquals(2, store.load("", "s-1").size + store.load("42", "s-1").size)
        assertEquals("anon", store.load("", "s-1").single().name)
        assertEquals("other", store.load("42", "s-1").single().name)
    }

    @Test
    fun `the key and the read order are the ones the store documents`() {
        store.archive("", "s-1", listOf(msg("m-2", MsgRole.USER, "b", "x")))
        store.archive("", "s-1", listOf(msg("m-1", MsgRole.USER, "a", "x")))
        store.archive("", "s-1", listOf(msg("m-3", MsgRole.USER, "c", "x")))

        assertEquals(listOf("m-2", "m-1", "m-3"), store.load("", "s-1").map { it.id })

        val indexes = dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) cols " +
                    "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() " +
                    "AND TABLE_NAME = 'session_message' GROUP BY INDEX_NAME",
            ).use { ps ->
                ps.executeQuery().let { rs ->
                    buildMap {
                        while (rs.next()) put(rs.getString(1), rs.getString(2))
                    }
                }
            }
        }
        assertEquals("user_id,session_id,msg_id", indexes["uk_session_msg"])
        assertEquals("session_id,id", indexes["idx_session_order"])
    }
}

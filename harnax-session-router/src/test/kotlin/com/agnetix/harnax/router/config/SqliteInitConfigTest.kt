package com.agnetix.harnax.router.config

import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import javax.sql.DataSource

/**
 * SQLite 初始化配置测试
 */
class SqliteInitConfigTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var dataSource: HikariDataSource
    private lateinit var config: SqliteInitConfig

    @BeforeEach
    fun setUp() {
        val dbPath = tempDir.resolve("test.db").toString()
        dataSource = HikariDataSource().apply {
            jdbcUrl = "jdbc:sqlite:$dbPath"
            driverClassName = "org.sqlite.JDBC"
            maximumPoolSize = 1
        }
        config = SqliteInitConfig(dataSource)
    }

    @AfterEach
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun `initDatabase creates api_call_log table`() {
        // 执行初始化
        config.initDatabase()

        // 验证表存在
        dataSource.connection.use { conn ->
            val rs = conn.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='api_call_log'"
            ).executeQuery()
            assertTrue(rs.next(), "api_call_log table should exist")
        }
    }

    @Test
    fun `initDatabase creates indexes`() {
        config.initDatabase()

        dataSource.connection.use { conn ->
            val rs = conn.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type='index' AND name LIKE 'idx_%'"
            ).executeQuery()
            
            val indexes = mutableListOf<String>()
            while (rs.next()) {
                indexes.add(rs.getString("name"))
            }
            
            assertTrue(indexes.contains("idx_caller_id"), "idx_caller_id should exist")
            assertTrue(indexes.contains("idx_session_id"), "idx_session_id should exist")
            assertTrue(indexes.contains("idx_start_time"), "idx_start_time should exist")
        }
    }

    @Test
    fun `initDatabase is idempotent`() {
        // 多次执行不应抛异常
        config.initDatabase()
        config.initDatabase()
        config.initDatabase()

        // 表仍然存在
        dataSource.connection.use { conn ->
            val rs = conn.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='api_call_log'"
            ).executeQuery()
            assertTrue(rs.next())
        }
    }

    @Test
    fun `initDatabase creates table with correct schema`() {
        config.initDatabase()

        dataSource.connection.use { conn ->
            val rs = conn.prepareStatement("PRAGMA table_info(api_call_log)").executeQuery()
            
            val columns = mutableMapOf<String, String>()
            while (rs.next()) {
                columns[rs.getString("name")] = rs.getString("type")
            }

            // 验证关键列
            assertEquals("INTEGER", columns["id"])
            assertEquals("TEXT", columns["caller_id"])
            assertEquals("TEXT", columns["caller_type"])
            assertEquals("INTEGER", columns["tenant_id"])
            assertEquals("TEXT", columns["session_id"])
            assertEquals("INTEGER", columns["agent_id"])
            assertEquals("TEXT", columns["endpoint"])
            assertEquals("TEXT", columns["method"])
            assertEquals("INTEGER", columns["status_code"])
            assertEquals("INTEGER", columns["success"])
            assertEquals("TEXT", columns["start_time"])
            assertEquals("TEXT", columns["end_time"])
            assertEquals("INTEGER", columns["duration_ms"])
            assertEquals("TEXT", columns["instance_id"])
        }
    }

    @Test
    fun `can insert and query data after init`() {
        config.initDatabase()

        dataSource.connection.use { conn ->
            // 插入测试数据
            conn.prepareStatement(
                """
                INSERT INTO api_call_log 
                (caller_id, caller_type, endpoint, method, status_code, success, start_time, end_time, duration_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).apply {
                setString(1, "test-caller")
                setString(2, "EXTERNAL_API")
                setString(3, "/api/router/agent/chat")
                setString(4, "POST")
                setInt(5, 200)
                setInt(6, 1)
                setString(7, "2026-01-01 10:00:00")
                setString(8, "2026-01-01 10:00:01")
                setLong(9, 1000)
                executeUpdate()
            }

            // 查询验证
            val rs = conn.prepareStatement("SELECT COUNT(*) FROM api_call_log").executeQuery()
            rs.next()
            assertEquals(1, rs.getInt(1))
        }
    }
}

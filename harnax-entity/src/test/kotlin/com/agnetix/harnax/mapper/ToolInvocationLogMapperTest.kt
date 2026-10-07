package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.ToolInvocationLog
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

/**
 * `tool_invocation_log` holds one row per invocation, at millisecond resolution.
 *
 * The reason a fixture here stamps explicit times a second apart rather than three calls of the same
 * instant: `start_time` is `datetime(3)`, and the only way a second-resolution column shows up is a
 * window that collapses two calls onto one instant and then reports one row for them.
 *
 * `@MybatisTest` runs each case in a transaction it rolls back, and the read-back below goes over a second,
 * independent connection straight to MySQL — a connection that cannot see another session's uncommitted
 * rows, and would read three rows as zero. `NOT_SUPPORTED` lets the batch commit as it is written, so what
 * the case asserts is what the database stored. Each case names a session id nothing else uses, so the rows
 * a case leaves behind cannot answer for another case.
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
open class ToolInvocationLogMapperTest {

    companion object {
        @Container
        val mysqlContainer = MySQLContainer("mysql:8.0")
            .withDatabaseName("harnax_test")
            .withUsername("root")
            .withPassword("test")
            .withInitScript("schema-test.sql")

        /** Seeded rows belong to tenant 1, so this class works in its own tenant. */
        private const val TENANT_ID = 21L

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysqlContainer::getJdbcUrl)
            registry.add("spring.datasource.username", mysqlContainer::getUsername)
            registry.add("spring.datasource.password", mysqlContainer::getPassword)
        }
    }

    @Autowired
    private lateinit var mapper: ToolInvocationLogMapper

    private fun row(
        sessionId: String,
        toolName: String,
        at: LocalDateTime,
    ) = ToolInvocationLog().apply {
        tenantId = TENANT_ID
        agentId = 7L
        this.sessionId = sessionId
        kind = ToolInvocationLog.KIND_BUILTIN
        this.toolName = toolName
        outcome = ToolInvocationLog.OUTCOME_SUCCESS
        durationMs = 42L
        startTime = at
        endTime = at
        ts = at
    }

    @Nested
    inner class Writes {
        @Test
        fun `one batch of three calls writes three rows at three distinct instants`() {
            val sessionId = "web-tool-invocation-${System.nanoTime()}"
            val base = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val rows = listOf(
                row(sessionId, "read_file", base),
                row(sessionId, "read_file", base.plusSeconds(1)),
                row(sessionId, "write_file", base.plusSeconds(2)),
            )

            assertEquals(3, mapper.batchInsert(rows))

            val written = mysqlRows(sessionId)
            assertEquals(3, written.size)
            assertEquals(
                setOf(base, base.plusSeconds(1), base.plusSeconds(2)),
                written.map { it.startTime }.toSet(),
                "two calls inside one second must still be two instants",
            )
        }
    }

    private fun mysqlRows(sessionId: String): List<ToolInvocationLog> {
        val sql = "SELECT tool_name, start_time, ts FROM tool_invocation_log WHERE session_id = ?"
        val out = mutableListOf<ToolInvocationLog>()
        mysqlContainer.createConnection("").use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, sessionId)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        out += ToolInvocationLog().apply {
                            toolName = rs.getString("tool_name")
                            startTime = rs.getObject("start_time", LocalDateTime::class.java)
                            ts = rs.getObject("ts", LocalDateTime::class.java)
                        }
                    }
                }
            }
        }
        return out
    }
}

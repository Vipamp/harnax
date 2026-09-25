package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.ToolCallLogEntity
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
open class ToolCallLogMapperTest {

    companion object {
        @Container
        val mysqlContainer = MySQLContainer("mysql:8.0")
            .withDatabaseName("harnax_test")
            .withUsername("root")
            .withPassword("test")
            .withInitScript("schema-test.sql")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysqlContainer::getJdbcUrl)
            registry.add("spring.datasource.username", mysqlContainer::getUsername)
            registry.add("spring.datasource.password", mysqlContainer::getPassword)
        }
    }

    @Autowired
    private lateinit var toolCallLogMapper: ToolCallLogMapper

    @Nested
    @DisplayName("基础 CRUD 测试")
    inner class BasicCrudTests {

        @Test
        @DisplayName("insert - 插入新工具调用日志")
        fun `insert should create new tool call log`() {
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val newLog = ToolCallLogEntity().apply {
                agentId = 1L
                sessionId = "session-001"
                toolName = "code-review"
                args = "{\"code\":\"print(1)\"}"
                result = "{\"issues\":[]}"
                success = 1
                startTime = now
                endTime = now.plusSeconds(2)
                duration = 2000L
                ts = now
            }

            val result = toolCallLogMapper.insert(newLog)
            assertEquals(1, result)
            assertTrue(newLog.id > 0)
        }

        @Test
        @DisplayName("insert - a team lead's tool call carries no agent attribution")
        fun `insert should store a tool call with no agent`() {
            // A lead's tool calls are the loudest part of `tool_call_log` for a team, and `agent_id` is
            // nullable in the table: what has to hold is that MyBatis writes the absent id as SQL NULL
            // rather than failing on an unknown JDBC type — hence a real insert against a real database.
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val newLog = ToolCallLogEntity().apply {
                agentId = null
                sessionId = "team-lead-session"
                toolName = "team::team_members"
                args = "{}"
                result = "[]"
                success = 1
                startTime = now
                endTime = now
                duration = 0L
                ts = now
            }

            assertEquals(1, toolCallLogMapper.insert(newLog))
            assertTrue(newLog.id > 0)
        }
    }
}

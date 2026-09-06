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
import kotlin.test.assertNotNull
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
        @DisplayName("selectById - 根据 ID 查询工具调用日志")
        fun `selectById should return tool call log by id`() {
            val log = toolCallLogMapper.selectById(1L)
            assertNotNull(log)
            assertEquals(1L, log.id)
            assertEquals(1L, log.agentId)
            assertEquals("web-search", log.toolName)
            assertEquals(1, log.success)
            assertEquals(2000L, log.duration)
        }

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
    }

    @Nested
    @DisplayName("自定义查询测试")
    inner class CustomQueryTests {

        @Test
        @DisplayName("selectBySessionId - 根据会话 ID 查询日志")
        fun `selectBySessionId should return logs by session id`() {
            val logs = toolCallLogMapper.selectBySessionId("session-001")
            assertTrue(logs.isNotEmpty())
            logs.forEach {
                assertEquals("session-001", it.sessionId)
            }
        }

        @Test
        @DisplayName("selectByToolName - 根据工具名称查询日志")
        fun `selectByToolName should return logs by tool name`() {
            val logs = toolCallLogMapper.selectByToolName("web-search")
            assertTrue(logs.isNotEmpty())
            logs.forEach {
                assertEquals("web-search", it.toolName)
            }
        }
    }
}

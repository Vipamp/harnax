package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.McpCallLog
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

/**
 * McpCallLogMapper Integration Tests
 *
 * 审计表只有 INSERT：接口上没有 update / delete，应用改不了自己写过的审计行。这条约束是编译期就能
 * 保证的（少一个方法就少一个入口），所以这里只需要验插入本身以及「哪些列允许缺省」。
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
open class McpCallLogMapperTest {

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
    private lateinit var mcpCallLogMapper: McpCallLogMapper

    @Nested
    @DisplayName("追加写入测试")
    inner class AppendTests {

        @Test
        @DisplayName("insert - 完整一条调用审计")
        fun `insert should persist a call entry`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val entry = McpCallLog().apply {
                tenantId = 1L
                userId = 1L
                mcpId = 5L
                sessionId = "session-001"
                toolName = "weather_query"
                action = McpCallLog.ACTION_CALL
                outcome = McpCallLog.OUTCOME_OK
                latencyMs = 120L
                createTime = now
            }

            // When
            assertEquals(1, mcpCallLogMapper.insert(entry))

            // Then
            assertTrue(entry.id > 0)
        }

        @Test
        @DisplayName("insert - 令牌换发时 user_id / session_id / tool_name 可为空")
        fun `insert should accept a token issuance entry without session or tool`() {
            // Given - 解析不到会话归属的运行侧调用要留得下这条审计，不能因为 user_id 为 NULL 就整条丢掉
            val entry = McpCallLog().apply {
                tenantId = 2L
                mcpId = 6L
                action = McpCallLog.ACTION_ISSUE
                outcome = McpCallLog.OUTCOME_NEEDS_CONSENT
            }

            // When
            assertEquals(1, mcpCallLogMapper.insert(entry))

            // Then
            assertTrue(entry.id > 0)
        }

        @Test
        @DisplayName("insert - 失败结果同样落库")
        fun `insert should persist a failed entry`() {
            // Given - 换发失败恰恰是最需要审计的一类，不能只写成功的那半本账
            val entry = McpCallLog().apply {
                tenantId = 1L
                userId = 3L
                mcpId = 7L
                sessionId = "session-003"
                action = McpCallLog.ACTION_REFRESH
                outcome = McpCallLog.OUTCOME_AUTH_FAILED
                latencyMs = 800L
            }

            assertEquals(1, mcpCallLogMapper.insert(entry))
            assertTrue(entry.id > 0)
        }
    }
}

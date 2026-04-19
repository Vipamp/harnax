package com.vipamp.vipclaw.admin.service.impl

import com.vipamp.vipclaw.admin.mapper.TokenStatsMapper
import org.junit.jupiter.api.*
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * TokenStatsServiceImpl 集成测试
 * 使用 Testcontainers 启动真实的 MySQL 容器进行测试
 *
 * @author vipamp
 * @since 2026-04-20
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class TokenStatsServiceImplIntegrationTest {

    companion object {
        @Container
        val mysqlContainer = MySQLContainer("mysql:8.0")
            .withDatabaseName("vipclaw_test")
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
    private lateinit var tokenStatsService: TokenStatsServiceImpl

    @Autowired
    private lateinit var tokenStatsMapper: TokenStatsMapper

    @Nested
    @DisplayName("分页查询测试")
    inner class PaginationTests {

        @Test
        @DisplayName("getTokenStatsPage - 正常分页查询")
        fun `getTokenStatsPage should return paginated results`() {
            // When
            val page = tokenStatsService.getTokenStatsPage(null, null, null, null, null, 1, 2)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 4) // schema-test.sql 中有4条记录
            assertEquals(2, page.size)
            assertEquals(1, page.current)
            assertEquals(2, page.records.size)
        }

        @Test
        @DisplayName("getTokenStatsPage - 会话ID过滤")
        fun `getTokenStatsPage should filter by sessionId`() {
            // When
            val page = tokenStatsService.getTokenStatsPage("session-001", null, null, null, null, 1, 10)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 2)
            assertTrue(page.records.all { it.sessionId == "session-001" })
        }

        @Test
        @DisplayName("getTokenStatsPage - 智能体ID过滤")
        fun `getTokenStatsPage should filter by agentId`() {
            // When
            val page = tokenStatsService.getTokenStatsPage(null, 2L, null, null, null, 1, 10)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 1)
            assertTrue(page.records.all { it.agentId == 2L })
        }

        @Test
        @DisplayName("getTokenStatsPage - 用户ID过滤")
        fun `getTokenStatsPage should filter by userId`() {
            // When
            val page = tokenStatsService.getTokenStatsPage(null, null, 2L, null, null, 1, 10)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 1)
            assertTrue(page.records.all { it.userId == 2L })
        }
    }

    @Nested
    @DisplayName("统计总Token消耗测试")
    inner class GetTotalTokenStatsTests {

        @Test
        @DisplayName("getTotalTokenStats - 统计所有记录")
        fun `getTotalTokenStats should return total stats for all records`() {
            // When
            val stats = tokenStatsService.getTotalTokenStats(null, null, null, null)

            // Then
            assertNotNull(stats)
            assertTrue(stats.totalTokens > 0)
            assertTrue(stats.totalCost.compareTo(BigDecimal.ZERO) > 0)
        }

        @Test
        @DisplayName("getTotalTokenStats - 按智能体ID统计")
        fun `getTotalTokenStats should return stats for specific agent`() {
            // When
            val stats = tokenStatsService.getTotalTokenStats(1L, null, null, null)

            // Then
            assertNotNull(stats)
            assertTrue(stats.totalTokens > 0)
        }

        @Test
        @DisplayName("getTotalTokenStats - 按用户ID统计")
        fun `getTotalTokenStats should return stats for specific user`() {
            // When
            val stats = tokenStatsService.getTotalTokenStats(null, 1L, null, null)

            // Then
            assertNotNull(stats)
            assertTrue(stats.totalTokens > 0)
        }
    }

    @Nested
    @DisplayName("按日期范围统计测试")
    inner class GetStatsByDateRangeTests {

        @Test
        @DisplayName("getStatsByDateRange - 统计所有日期范围")
        fun `getStatsByDateRange should return stats for date range`() {
            // When - 查询最近30天
            val endDate = LocalDate.now()
            val startDate = endDate.minusDays(30)
            val stats = tokenStatsService.getStatsByDateRange(startDate, endDate, null, null)

            // Then
            assertNotNull(stats)
            assertTrue(stats.isNotEmpty())
        }

        @Test
        @DisplayName("getStatsByDateRange - 按智能体ID统计")
        fun `getStatsByDateRange should return stats for specific agent`() {
            // When
            val endDate = LocalDate.now()
            val startDate = endDate.minusDays(30)
            val stats = tokenStatsService.getStatsByDateRange(startDate, endDate, 1L, null)

            // Then
            assertNotNull(stats)
        }
    }

    @Nested
    @DisplayName("按模型统计测试")
    inner class GetStatsByModelTests {

        @Test
        @DisplayName("getStatsByModel - 统计所有模型")
        fun `getStatsByModel should return stats for all models`() {
            // When
            val stats = tokenStatsService.getStatsByModel(null, null)

            // Then
            assertNotNull(stats)
            assertTrue(stats.isNotEmpty())
        }

        @Test
        @DisplayName("getStatsByModel - 按智能体ID统计模型")
        fun `getStatsByModel should return stats for specific agent`() {
            // When
            val stats = tokenStatsService.getStatsByModel(1L, null)

            // Then
            assertNotNull(stats)
            assertTrue(stats.isNotEmpty())
        }
    }

    @Nested
    @DisplayName("保存Token统计测试")
    inner class SaveTokenStatsTests {

        @Test
        @DisplayName("saveTokenStats - 保存成功")
        fun `saveTokenStats should save successfully`() {
            // Given
            val sessionId = "test-session-${System.currentTimeMillis()}"
            val agentId = 1L
            val modelId = 1L
            val userId = 1L
            val promptTokens = 500
            val completionTokens = 250
            val totalTokens = 750
            val cost = BigDecimal("0.0225")

            // When
            tokenStatsService.saveTokenStats(
                sessionId,
                agentId,
                modelId,
                userId,
                promptTokens,
                completionTokens,
                totalTokens,
                cost
            )

            // Then
            val page = tokenStatsService.getTokenStatsPage(sessionId, null, null, null, null, 1, 10)
            assertTrue(page.total >= 1)
            assertEquals(promptTokens, page.records[0].promptTokens)
            assertEquals(completionTokens, page.records[0].completionTokens)
            assertEquals(totalTokens, page.records[0].totalTokens)
            assertEquals(0, cost.compareTo(page.records[0].cost))
        }
    }

    @Nested
    @DisplayName("查询Token统计详情测试")
    inner class GetTokenStatsByIdTests {

        @Test
        @DisplayName("getTokenStatsById - 查询存在的统计记录")
        fun `getTokenStatsById should return stats when exists`() {
            // When
            val stats = tokenStatsService.getTokenStatsById(1L)

            // Then
            assertNotNull(stats)
            assertEquals(1L, stats?.id)
        }

        @Test
        @DisplayName("getTokenStatsById - 查询不存在的统计记录返回null")
        fun `getTokenStatsById should return null when stats not found`() {
            // When
            val stats = tokenStatsService.getTokenStatsById(999L)

            // Then
            assertNull(stats)
        }
    }

    @Nested
    @DisplayName("删除Token统计测试")
    inner class DeleteTokenStatsTests {

        @Test
        @DisplayName("deleteTokenStats - 删除成功")
        fun `deleteTokenStats should delete successfully`() {
            // When
            val result = tokenStatsService.deleteTokenStats(1L)

            // Then
            assertTrue(result)

            val stats = tokenStatsMapper.selectById(1L)
            assertNull(stats) // 物理删除
        }

        @Test
        @DisplayName("deleteTokenStats - 删除不存在的记录返回false")
        fun `deleteTokenStats should return false when stats not found`() {
            // When
            val result = tokenStatsService.deleteTokenStats(999L)

            // Then
            assertFalse(result)
        }
    }

    @Nested
    @DisplayName("完整业务流程测试")
    inner class BusinessFlowTests {

        @Test
        @DisplayName("完整流程：保存 - 查询 - 统计 - 删除")
        fun `complete flow save query stats delete`() {
            // 1. 保存Token统计
            val sessionId = "flowtest-session-${System.currentTimeMillis()}"
            tokenStatsService.saveTokenStats(
                sessionId,
                1L,
                1L,
                1L,
                1000,
                500,
                1500,
                BigDecimal("0.0450")
            )

            // 2. 查询统计记录
            val page = tokenStatsService.getTokenStatsPage(sessionId, null, null, null, null, 1, 10)
            assertTrue(page.total >= 1)
            val statsId = page.records[0].id

            // 3. 查询详情
            val stats = tokenStatsService.getTokenStatsById(statsId)
            assertNotNull(stats)
            assertEquals(1500, stats?.totalTokens)
            assertEquals(0, BigDecimal("0.0450").compareTo(stats?.cost))

            // 4. 统计总消耗
            val totalStats = tokenStatsService.getTotalTokenStats(1L, 1L, null, null)
            assertNotNull(totalStats)
            assertTrue(totalStats.totalTokens > 0)

            // 5. 删除统计记录
            assertTrue(tokenStatsService.deleteTokenStats(statsId))
            val deletedStats = tokenStatsService.getTokenStatsById(statsId)
            assertNull(deletedStats)
        }
    }
}

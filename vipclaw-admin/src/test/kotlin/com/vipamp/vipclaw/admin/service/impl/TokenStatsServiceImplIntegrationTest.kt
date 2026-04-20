package com.vipamp.vipclaw.admin.service.impl

import com.vipamp.vipclaw.admin.entity.TokenStats
import com.vipamp.vipclaw.admin.mapper.TokenStatsMapper
import org.junit.jupiter.api.*
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
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
    @DisplayName("聚合统计测试")
    inner class AggregationStatsTests {

        @Test
        @DisplayName("getAggregationStats - 获取聚合统计")
        fun `getAggregationStats should return aggregation data`() {
            // Given - 查询最近30天
            val endTime = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val startTime = LocalDateTime.now().minusDays(30)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

            // When
            val stats = tokenStatsService.getAggregationStats(startTime, endTime)

            // Then
            assertNotNull(stats)
            assertNotNull(stats.overall)
            assertNotNull(stats.modelStats)
            assertNotNull(stats.sessionStats)
            assertNotNull(stats.agentStats)
        }
    }

    @Nested
    @DisplayName("时序数据测试")
    inner class TimeSeriesDataTests {

        @Test
        @DisplayName("getTimeSeriesData - 获取时序数据（按天）")
        fun `getTimeSeriesData should return time series data by day`() {
            // Given
            val endTime = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val startTime = LocalDateTime.now().minusDays(7)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

            // When
            val stats = tokenStatsService.getTimeSeriesData(startTime, endTime, "day")

            // Then
            assertNotNull(stats)
            assertNotNull(stats.timeSeriesData)
        }

        @Test
        @DisplayName("getModelTimeSeriesData - 获取模型时序数据")
        fun `getModelTimeSeriesData should return model time series data`() {
            // Given
            val endTime = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val startTime = LocalDateTime.now().minusDays(7)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

            // When
            val stats = tokenStatsService.getModelTimeSeriesData(startTime, endTime, "day")

            // Then
            assertNotNull(stats)
            assertNotNull(stats.timeSeriesData)
        }

        @Test
        @DisplayName("getAgentTimeSeriesData - 获取智能体时序数据")
        fun `getAgentTimeSeriesData should return agent time series data`() {
            // Given
            val endTime = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val startTime = LocalDateTime.now().minusDays(7)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

            // When
            val stats = tokenStatsService.getAgentTimeSeriesData(startTime, endTime, "day")

            // Then
            assertNotNull(stats)
            assertNotNull(stats.timeSeriesData)
        }

        @Test
        @DisplayName("getSessionTimeSeriesData - 获取会话时序数据")
        fun `getSessionTimeSeriesData should return session time series data`() {
            // Given
            val endTime = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val startTime = LocalDateTime.now().minusDays(7)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

            // When
            val stats = tokenStatsService.getSessionTimeSeriesData(startTime, endTime, "day")

            // Then
            assertNotNull(stats)
            assertNotNull(stats.timeSeriesData)
        }
    }
}

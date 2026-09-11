package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.OverallStats
import com.agnetix.harnax.admin.dto.TokenStatsAggregationResponse
import com.agnetix.harnax.admin.service.TokenStatsService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.quality.Strictness
import java.time.LocalDateTime

/**
 * TokenStatsController 单元测试
 * 直接实例化 Controller + Mock Service,直调方法断言 ResultVo
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenStatsControllerTest {

    @Mock
    private lateinit var tokenStatsService: TokenStatsService

    @InjectMocks
    private lateinit var controller: TokenStatsController

    private val startTime: LocalDateTime = LocalDateTime.of(2026, 7, 1, 0, 0, 0)
    private val endTime: LocalDateTime = LocalDateTime.of(2026, 7, 31, 23, 59, 59)

    private fun stubResponse() = TokenStatsAggregationResponse(
        overall = OverallStats(
            totalInputToken = 100L,
            totalOutputToken = 200L,
            grandTotalToken = 300L,
        ),
    )

    @Nested
    @DisplayName("GET /api/admin/token-stats/aggregation")
    inner class AggregationEndpoint {

        @Test
        @DisplayName("getAggregationStats - 指定时间范围时返回统计")
        fun `getAggregationStats should return stats with explicit time range`() {
            `when`(tokenStatsService.getAggregationStats("2026-07-01 00:00:00", "2026-07-31 23:59:59"))
                .thenReturn(stubResponse())

            val result = controller.getAggregationStats(startTime, endTime)

            assertTrue(result.isSuccess())
            assertEquals(300L, result.data?.overall?.grandTotalToken)
            verify(tokenStatsService).getAggregationStats("2026-07-01 00:00:00", "2026-07-31 23:59:59")
        }

        @Test
        @DisplayName("getAggregationStats - 时间为 null 时默认查询最近 7 天")
        fun `getAggregationStats should default to last 7 days when time is null`() {
            `when`(tokenStatsService.getAggregationStats(any(), any())).thenReturn(stubResponse())

            val result = controller.getAggregationStats(null, null)

            assertTrue(result.isSuccess())
            val startCaptor = argumentCaptor<String>()
            val endCaptor = argumentCaptor<String>()
            verify(tokenStatsService).getAggregationStats(startCaptor.capture(), endCaptor.capture())
            assertNotNull(startCaptor.firstValue)
            assertNotNull(endCaptor.firstValue)
            assertTrue(startCaptor.firstValue < endCaptor.firstValue)
        }

        @Test
        @DisplayName("getAggregationStats - service 抛异常时返回错误")
        fun `getAggregationStats should return error on service exception`() {
            `when`(tokenStatsService.getAggregationStats(any(), any()))
                .thenThrow(RuntimeException("DB error"))

            val result = controller.getAggregationStats(startTime, endTime)

            assertFalse(result.isSuccess())
            assertEquals("Failed to get statistics data: DB error", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/token-stats/time-series")
    inner class TimeSeriesEndpoint {

        @Test
        @DisplayName("getTimeSeriesData - 返回时间序列数据")
        fun `getTimeSeriesData should return time series data`() {
            `when`(tokenStatsService.getTimeSeriesData("2026-07-01 00:00:00", "2026-07-31 23:59:59", "day"))
                .thenReturn(stubResponse())

            val result = controller.getTimeSeriesData(startTime, endTime, "day")

            assertTrue(result.isSuccess())
            assertEquals(300L, result.data?.overall?.grandTotalToken)
        }

        @Test
        @DisplayName("getTimeSeriesData - 透传 granularity 参数")
        fun `getTimeSeriesData should pass granularity`() {
            `when`(tokenStatsService.getTimeSeriesData(any(), any(), eq("hour"))).thenReturn(stubResponse())

            val result = controller.getTimeSeriesData(startTime, endTime, "hour")

            assertTrue(result.isSuccess())
            verify(tokenStatsService).getTimeSeriesData("2026-07-01 00:00:00", "2026-07-31 23:59:59", "hour")
        }

        @Test
        @DisplayName("getTimeSeriesData - 时间为 null 时默认查询最近 7 天")
        fun `getTimeSeriesData should default time range when null`() {
            `when`(tokenStatsService.getTimeSeriesData(any(), any(), any())).thenReturn(stubResponse())

            val result = controller.getTimeSeriesData(null, null, "day")

            assertTrue(result.isSuccess())
            verify(tokenStatsService).getTimeSeriesData(any(), any(), eq("day"))
        }

        @Test
        @DisplayName("getTimeSeriesData - service 抛异常时返回错误")
        fun `getTimeSeriesData should return error on service exception`() {
            `when`(tokenStatsService.getTimeSeriesData(any(), any(), any()))
                .thenThrow(RuntimeException("DB error"))

            val result = controller.getTimeSeriesData(startTime, endTime, "day")

            assertFalse(result.isSuccess())
            assertEquals("Failed to get time series data: DB error", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/token-stats/time-series/model")
    inner class ModelTimeSeriesEndpoint {

        @Test
        @DisplayName("getModelTimeSeriesData - 返回模型维度时间序列")
        fun `getModelTimeSeriesData should return model time series data`() {
            `when`(tokenStatsService.getModelTimeSeriesData("2026-07-01 00:00:00", "2026-07-31 23:59:59", "day"))
                .thenReturn(stubResponse())

            val result = controller.getModelTimeSeriesData(startTime, endTime, "day")

            assertTrue(result.isSuccess())
            assertEquals(100L, result.data?.overall?.totalInputToken)
        }

        @Test
        @DisplayName("getModelTimeSeriesData - 时间为 null 时默认查询最近 7 天")
        fun `getModelTimeSeriesData should default time range when null`() {
            `when`(tokenStatsService.getModelTimeSeriesData(any(), any(), any())).thenReturn(stubResponse())

            val result = controller.getModelTimeSeriesData(null, null, "month")

            assertTrue(result.isSuccess())
            verify(tokenStatsService).getModelTimeSeriesData(any(), any(), eq("month"))
        }

        @Test
        @DisplayName("getModelTimeSeriesData - service 抛异常时返回错误")
        fun `getModelTimeSeriesData should return error on service exception`() {
            `when`(tokenStatsService.getModelTimeSeriesData(any(), any(), any()))
                .thenThrow(RuntimeException("DB error"))

            val result = controller.getModelTimeSeriesData(startTime, endTime, "day")

            assertFalse(result.isSuccess())
            assertEquals("Failed to get model time series data: DB error", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/token-stats/time-series/agent")
    inner class AgentTimeSeriesEndpoint {

        @Test
        @DisplayName("getAgentTimeSeriesData - 返回 Agent 维度时间序列")
        fun `getAgentTimeSeriesData should return agent time series data`() {
            `when`(tokenStatsService.getAgentTimeSeriesData("2026-07-01 00:00:00", "2026-07-31 23:59:59", "day"))
                .thenReturn(stubResponse())

            val result = controller.getAgentTimeSeriesData(startTime, endTime, "day")

            assertTrue(result.isSuccess())
            assertEquals(200L, result.data?.overall?.totalOutputToken)
        }

        @Test
        @DisplayName("getAgentTimeSeriesData - 时间为 null 时默认查询最近 7 天")
        fun `getAgentTimeSeriesData should default time range when null`() {
            `when`(tokenStatsService.getAgentTimeSeriesData(any(), any(), any())).thenReturn(stubResponse())

            val result = controller.getAgentTimeSeriesData(null, null, "hour")

            assertTrue(result.isSuccess())
            verify(tokenStatsService).getAgentTimeSeriesData(any(), any(), eq("hour"))
        }

        @Test
        @DisplayName("getAgentTimeSeriesData - service 抛异常时返回错误")
        fun `getAgentTimeSeriesData should return error on service exception`() {
            `when`(tokenStatsService.getAgentTimeSeriesData(any(), any(), any()))
                .thenThrow(RuntimeException("DB error"))

            val result = controller.getAgentTimeSeriesData(startTime, endTime, "day")

            assertFalse(result.isSuccess())
            assertEquals("Failed to get agent time series data: DB error", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/token-stats/time-series/session")
    inner class SessionTimeSeriesEndpoint {

        @Test
        @DisplayName("getSessionTimeSeriesData - 返回会话维度时间序列")
        fun `getSessionTimeSeriesData should return session time series data`() {
            `when`(tokenStatsService.getSessionTimeSeriesData("2026-07-01 00:00:00", "2026-07-31 23:59:59", "day"))
                .thenReturn(stubResponse())

            val result = controller.getSessionTimeSeriesData(startTime, endTime, "day")

            assertTrue(result.isSuccess())
            assertEquals(300L, result.data?.overall?.grandTotalToken)
        }

        @Test
        @DisplayName("getSessionTimeSeriesData - 时间为 null 时默认查询最近 7 天")
        fun `getSessionTimeSeriesData should default time range when null`() {
            `when`(tokenStatsService.getSessionTimeSeriesData(any(), any(), any())).thenReturn(stubResponse())

            val result = controller.getSessionTimeSeriesData(null, null, "day")

            assertTrue(result.isSuccess())
            verify(tokenStatsService).getSessionTimeSeriesData(any(), any(), eq("day"))
        }

        @Test
        @DisplayName("getSessionTimeSeriesData - service 抛异常时返回错误")
        fun `getSessionTimeSeriesData should return error on service exception`() {
            `when`(tokenStatsService.getSessionTimeSeriesData(any(), any(), any()))
                .thenThrow(RuntimeException("DB error"))

            val result = controller.getSessionTimeSeriesData(startTime, endTime, "day")

            assertFalse(result.isSuccess())
            assertEquals("Failed to get session time series data: DB error", result.message)
        }
    }
}

package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.OverallStats
import com.agnetix.harnax.admin.dto.TokenStatsAggregationResponse
import com.agnetix.harnax.admin.service.TokenStatsService
import com.agnetix.harnax.admin.util.JwtUtil
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyLong
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
 *
 * Every stub names a tenant, because every service read now requires one: a controller that answered with
 * the default workspace instead of the request's would miss all of these stubs at once.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenStatsControllerTest {

    @Mock
    private lateinit var tokenStatsService: TokenStatsService

    @Mock
    private lateinit var jwtUtil: JwtUtil

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

    @BeforeEach
    fun bindRequestTenant() {
        TenantContext.setTenantId(TENANT_ID)
    }

    @AfterEach
    fun clearRequestTenant() {
        TenantContext.clear()
    }

    @Nested
    @DisplayName("GET /api/admin/token-stats/aggregation")
    inner class AggregationEndpoint {

        @Test
        @DisplayName("getAggregationStats - 指定时间范围时返回统计")
        fun `getAggregationStats should return stats with explicit time range`() {
            `when`(
                tokenStatsService.getAggregationStats(
                    "2026-07-01 00:00:00",
                    "2026-07-31 23:59:59",
                    TENANT_ID,
                ),
            ).thenReturn(stubResponse())

            val result = controller.getAggregationStats(startTime, endTime)

            assertTrue(result.isSuccess())
            assertEquals(300L, result.data?.overall?.grandTotalToken)
        }

        @Test
        @DisplayName("getAggregationStats - 时间为 null 时默认查询最近 7 天")
        fun `getAggregationStats should default to last 7 days when time is null`() {
            `when`(tokenStatsService.getAggregationStats(any(), any(), anyLong())).thenReturn(stubResponse())

            val result = controller.getAggregationStats(null, null)

            assertTrue(result.isSuccess())
            val startCaptor = argumentCaptor<String>()
            val endCaptor = argumentCaptor<String>()
            verify(tokenStatsService).getAggregationStats(startCaptor.capture(), endCaptor.capture(), anyLong())
            assertNotNull(startCaptor.firstValue)
            assertNotNull(endCaptor.firstValue)
            assertTrue(startCaptor.firstValue < endCaptor.firstValue)
        }

        @Test
        @DisplayName("getAggregationStats - service 抛异常时返回错误")
        fun `getAggregationStats should return error on service exception`() {
            `when`(tokenStatsService.getAggregationStats(any(), any(), anyLong()))
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
            `when`(
                tokenStatsService.getTimeSeriesData(
                    "2026-07-01 00:00:00",
                    "2026-07-31 23:59:59",
                    "day",
                    TENANT_ID,
                ),
            ).thenReturn(stubResponse())

            val result = controller.getTimeSeriesData(startTime, endTime, "day")

            assertTrue(result.isSuccess())
            assertEquals(300L, result.data?.overall?.grandTotalToken)
        }

        @Test
        @DisplayName("getTimeSeriesData - 透传 granularity 参数")
        fun `getTimeSeriesData should pass granularity`() {
            `when`(tokenStatsService.getTimeSeriesData(any(), any(), eq("hour"), anyLong())).thenReturn(stubResponse())

            val result = controller.getTimeSeriesData(startTime, endTime, "hour")

            assertTrue(result.isSuccess())
            verify(tokenStatsService).getTimeSeriesData(
                "2026-07-01 00:00:00",
                "2026-07-31 23:59:59",
                "hour",
                TENANT_ID,
            )
        }

        @Test
        @DisplayName("getTimeSeriesData - 时间为 null 时默认查询最近 7 天")
        fun `getTimeSeriesData should default time range when null`() {
            `when`(tokenStatsService.getTimeSeriesData(any(), any(), any(), anyLong())).thenReturn(stubResponse())

            val result = controller.getTimeSeriesData(null, null, "day")

            assertTrue(result.isSuccess())
            verify(tokenStatsService).getTimeSeriesData(any(), any(), eq("day"), anyLong())
        }

        @Test
        @DisplayName("getTimeSeriesData - service 抛异常时返回错误")
        fun `getTimeSeriesData should return error on service exception`() {
            `when`(tokenStatsService.getTimeSeriesData(any(), any(), any(), anyLong()))
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
            `when`(
                tokenStatsService.getModelTimeSeriesData(
                    "2026-07-01 00:00:00",
                    "2026-07-31 23:59:59",
                    "day",
                    TENANT_ID,
                ),
            ).thenReturn(stubResponse())

            val result = controller.getModelTimeSeriesData(startTime, endTime, "day")

            assertTrue(result.isSuccess())
            assertEquals(100L, result.data?.overall?.totalInputToken)
        }

        @Test
        @DisplayName("getModelTimeSeriesData - 时间为 null 时默认查询最近 7 天")
        fun `getModelTimeSeriesData should default time range when null`() {
            `when`(tokenStatsService.getModelTimeSeriesData(any(), any(), any(), anyLong())).thenReturn(stubResponse())

            val result = controller.getModelTimeSeriesData(null, null, "month")

            assertTrue(result.isSuccess())
            verify(tokenStatsService).getModelTimeSeriesData(any(), any(), eq("month"), anyLong())
        }

        @Test
        @DisplayName("getModelTimeSeriesData - service 抛异常时返回错误")
        fun `getModelTimeSeriesData should return error on service exception`() {
            `when`(tokenStatsService.getModelTimeSeriesData(any(), any(), any(), anyLong()))
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
            `when`(
                tokenStatsService.getAgentTimeSeriesData(
                    "2026-07-01 00:00:00",
                    "2026-07-31 23:59:59",
                    "day",
                    TENANT_ID,
                ),
            ).thenReturn(stubResponse())

            val result = controller.getAgentTimeSeriesData(startTime, endTime, "day")

            assertTrue(result.isSuccess())
            assertEquals(200L, result.data?.overall?.totalOutputToken)
        }

        @Test
        @DisplayName("getAgentTimeSeriesData - 时间为 null 时默认查询最近 7 天")
        fun `getAgentTimeSeriesData should default time range when null`() {
            `when`(tokenStatsService.getAgentTimeSeriesData(any(), any(), any(), anyLong())).thenReturn(stubResponse())

            val result = controller.getAgentTimeSeriesData(null, null, "hour")

            assertTrue(result.isSuccess())
            verify(tokenStatsService).getAgentTimeSeriesData(any(), any(), eq("hour"), anyLong())
        }

        @Test
        @DisplayName("getAgentTimeSeriesData - service 抛异常时返回错误")
        fun `getAgentTimeSeriesData should return error on service exception`() {
            `when`(tokenStatsService.getAgentTimeSeriesData(any(), any(), any(), anyLong()))
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
            `when`(
                tokenStatsService.getSessionTimeSeriesData(
                    "2026-07-01 00:00:00",
                    "2026-07-31 23:59:59",
                    "day",
                    TENANT_ID,
                ),
            ).thenReturn(stubResponse())

            val result = controller.getSessionTimeSeriesData(startTime, endTime, "day")

            assertTrue(result.isSuccess())
            assertEquals(300L, result.data?.overall?.grandTotalToken)
        }

        @Test
        @DisplayName("getSessionTimeSeriesData - 时间为 null 时默认查询最近 7 天")
        fun `getSessionTimeSeriesData should default time range when null`() {
            `when`(tokenStatsService.getSessionTimeSeriesData(any(), any(), any(), anyLong()))
                .thenReturn(stubResponse())

            val result = controller.getSessionTimeSeriesData(null, null, "day")

            assertTrue(result.isSuccess())
            verify(tokenStatsService).getSessionTimeSeriesData(any(), any(), eq("day"), anyLong())
        }

        @Test
        @DisplayName("getSessionTimeSeriesData - service 抛异常时返回错误")
        fun `getSessionTimeSeriesData should return error on service exception`() {
            `when`(tokenStatsService.getSessionTimeSeriesData(any(), any(), any(), anyLong()))
                .thenThrow(RuntimeException("DB error"))

            val result = controller.getSessionTimeSeriesData(startTime, endTime, "day")

            assertFalse(result.isSuccess())
            assertEquals("Failed to get session time series data: DB error", result.message)
        }
    }

    @Nested
    @DisplayName("Tenant scoping")
    inner class TenantScopeEndpointTests {

        /**
         * The tenant each endpoint hands down is the request's own, and no endpoint takes one as a
         * parameter — a client that could name a workspace in the URL could name any other one.
         */
        @Test
        @DisplayName("every endpoint reads within the resolved tenant")
        fun `each endpoint should read within the resolved tenant`() {
            stubAll()

            val tenantCaptor = argumentCaptor<Long>()
            controller.getAggregationStats(startTime, endTime)
            verify(tokenStatsService).getAggregationStats(any(), any(), tenantCaptor.capture())
            assertEquals(TENANT_ID, tenantCaptor.lastValue)

            controller.getTimeSeriesData(startTime, endTime, "day")
            verify(tokenStatsService).getTimeSeriesData(any(), any(), any(), tenantCaptor.capture())
            assertEquals(TENANT_ID, tenantCaptor.lastValue)

            controller.getModelTimeSeriesData(startTime, endTime, "day")
            verify(tokenStatsService).getModelTimeSeriesData(any(), any(), any(), tenantCaptor.capture())
            assertEquals(TENANT_ID, tenantCaptor.lastValue)

            controller.getAgentTimeSeriesData(startTime, endTime, "day")
            verify(tokenStatsService).getAgentTimeSeriesData(any(), any(), any(), tenantCaptor.capture())
            assertEquals(TENANT_ID, tenantCaptor.lastValue)

            controller.getSessionTimeSeriesData(startTime, endTime, "day")
            verify(tokenStatsService).getSessionTimeSeriesData(any(), any(), any(), tenantCaptor.capture())
            assertEquals(TENANT_ID, tenantCaptor.lastValue)
        }

        private fun stubAll() {
            `when`(tokenStatsService.getAggregationStats(any(), any(), anyLong())).thenReturn(stubResponse())
            `when`(tokenStatsService.getTimeSeriesData(any(), any(), any(), anyLong())).thenReturn(stubResponse())
            `when`(tokenStatsService.getModelTimeSeriesData(any(), any(), any(), anyLong())).thenReturn(stubResponse())
            `when`(tokenStatsService.getAgentTimeSeriesData(any(), any(), any(), anyLong())).thenReturn(stubResponse())
            `when`(tokenStatsService.getSessionTimeSeriesData(any(), any(), any(), anyLong())).thenReturn(stubResponse())
        }
    }

    private companion object {
        /** Not tenant 1: the default workspace would pass even if the resolver were bypassed. */
        const val TENANT_ID = 3L
    }
}

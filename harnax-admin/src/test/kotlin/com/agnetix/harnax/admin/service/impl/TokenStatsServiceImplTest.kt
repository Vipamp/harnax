package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.entity.TokenStats
import com.agnetix.harnax.mapper.TokenStatsMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.anyOrNull
import org.mockito.quality.Strictness
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * TokenStatsServiceImpl Unit Tests
 * Uses Mockito to simulate Mapper layer dependencies
 * Covers normal flows, exception flows, and boundary conditions
 *
 * @author agnetix
 * @since 2026-05-17
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenStatsServiceImplTest {

    @Mock
    private lateinit var tokenStatsMapper: TokenStatsMapper

    private lateinit var testTokenStats: TokenStats

    @BeforeEach
    fun setUp() {
        testTokenStats = TokenStats().apply {
            id = 1L
            agentId = 100L
            sessionId = "web-session-1"
            chatModelId = 1L
            inputToken = 100L
            outputToken = 200L
            totalToken = 300L
            fee = BigDecimal("0.15")
            ts = LocalDateTime.now()
        }
    }

    private fun createService(): TokenStatsServiceImpl = TokenStatsServiceImpl(
        tokenStatsMapper = tokenStatsMapper,
    )

    /**
     * Build a time series row as returned by the mapper
     */
    private fun timeSeriesRow(
        timePoint: LocalDateTime,
        input: Long = 10L,
        output: Long = 20L,
        total: Long = 30L,
        fee: BigDecimal = BigDecimal("0.01"),
        extra: Map<String?, Any?> = emptyMap(),
    ): MutableMap<String?, Any?> {
        val row = mutableMapOf<String?, Any?>(
            "timePoint" to timePoint,
            "totalInputToken" to input,
            "totalOutputToken" to output,
            "grandTotalToken" to total,
            "totalFee" to fee,
        )
        row.putAll(extra)
        return row
    }

    @Nested
    @DisplayName("Save Token Stats Tests")
    inner class SaveTokenStatsTests {

        @Test
        @DisplayName("saveTokenStats - Save token consumption record successfully")
        fun `saveTokenStats should save token stats successfully`() {
            // Given
            `when`(tokenStatsMapper.insert(testTokenStats)).thenReturn(1)

            // When
            val result = createService().saveTokenStats(testTokenStats)

            // Then
            assertTrue(result)
            verify(tokenStatsMapper).insert(testTokenStats)
        }

        @Test
        @DisplayName("saveTokenStats - Return false when insert fails")
        fun `saveTokenStats should return false when insert fails`() {
            // Given
            `when`(tokenStatsMapper.insert(testTokenStats)).thenReturn(0)

            // When
            val result = createService().saveTokenStats(testTokenStats)

            // Then
            assertFalse(result)
            verify(tokenStatsMapper).insert(testTokenStats)
        }
    }

    @Nested
    @DisplayName("Get Aggregation Stats Tests")
    inner class GetAggregationStatsTests {

        @Test
        @DisplayName("getAggregationStats - Return full aggregation results")
        fun `getAggregationStats should return full aggregation results`() {
            // Given
            val startTime = "2026-01-01 00:00:00"
            val endTime = "2026-01-31 23:59:59"

            val overallMap = mutableMapOf<String?, Any?>(
                "totalInputToken" to 1000L,
                "totalOutputToken" to 2000L,
                "grandTotalToken" to 3000L,
                "totalFee" to 1.5,
                "agentCount" to 2L,
                "sessionCount" to 3L,
                "modelCount" to 1L,
            )
            val modelRow = mutableMapOf<String?, Any?>(
                "modelId" to 1L,
                "modelName" to "gpt-4",
                "providerName" to "OpenAI",
                "totalInputToken" to 1000L,
                "totalOutputToken" to 2000L,
                "grandTotalToken" to 3000L,
                "totalFee" to 1.5,
            )
            val sessionRow = mutableMapOf<String?, Any?>(
                "sessionId" to "web-session-1",
                "sessionTitle" to "Test Session",
                "totalInputToken" to 500L,
                "totalOutputToken" to 700L,
                "grandTotalToken" to 1200L,
                "totalFee" to 0.6,
            )
            val agentRow = mutableMapOf<String?, Any?>(
                "agentId" to 100L,
                "agentName" to "Test Agent",
                "totalInputToken" to 800L,
                "totalOutputToken" to 900L,
                "grandTotalToken" to 1700L,
                "totalFee" to 0.85,
            )

            `when`(tokenStatsMapper.getOverallStats(startTime, endTime)).thenReturn(overallMap)
            `when`(tokenStatsMapper.aggregateByModel(startTime, endTime)).thenReturn(mutableListOf(modelRow))
            `when`(tokenStatsMapper.aggregateBySession(startTime, endTime)).thenReturn(mutableListOf(sessionRow))
            `when`(tokenStatsMapper.aggregateByAgent(startTime, endTime)).thenReturn(mutableListOf(agentRow))

            // When
            val result = createService().getAggregationStats(startTime, endTime)

            // Then
            assertNotNull(result)
            assertNotNull(result.overall)
            assertEquals(1000L, result.overall?.totalInputToken)
            assertEquals(2000L, result.overall?.totalOutputToken)
            assertEquals(3000L, result.overall?.grandTotalToken)
            assertEquals(2L, result.overall?.agentCount)

            assertEquals(1, result.modelStats?.size)
            assertEquals("gpt-4", result.modelStats?.get(0)?.modelName)
            assertEquals("OpenAI", result.modelStats?.get(0)?.providerName)

            assertEquals(1, result.sessionStats?.size)
            assertEquals("web-session-1", result.sessionStats?.get(0)?.sessionId)
            assertEquals("Test Session", result.sessionStats?.get(0)?.sessionTitle)

            assertEquals(1, result.agentStats?.size)
            assertEquals(100L, result.agentStats?.get(0)?.agentId)
            assertEquals("Test Agent", result.agentStats?.get(0)?.agentName)

            verify(tokenStatsMapper).getOverallStats(startTime, endTime)
            verify(tokenStatsMapper).aggregateByModel(startTime, endTime)
            verify(tokenStatsMapper).aggregateBySession(startTime, endTime)
            verify(tokenStatsMapper).aggregateByAgent(startTime, endTime)
        }

        @Test
        @DisplayName("getAggregationStats - Return default overall stats when no data")
        fun `getAggregationStats should return default overall stats when no data`() {
            // Given
            val startTime = "2026-01-01 00:00:00"
            val endTime = "2026-01-31 23:59:59"

            `when`(tokenStatsMapper.getOverallStats(startTime, endTime)).thenReturn(null)
            `when`(tokenStatsMapper.aggregateByModel(startTime, endTime)).thenReturn(mutableListOf())
            `when`(tokenStatsMapper.aggregateBySession(startTime, endTime)).thenReturn(mutableListOf())
            `when`(tokenStatsMapper.aggregateByAgent(startTime, endTime)).thenReturn(mutableListOf())

            // When
            val result = createService().getAggregationStats(startTime, endTime)

            // Then
            assertNotNull(result)
            assertNotNull(result.overall)
            assertEquals(0L, result.overall?.totalInputToken)
            assertEquals(0L, result.overall?.grandTotalToken)
            assertTrue(result.modelStats?.isEmpty() == true)
            assertTrue(result.sessionStats?.isEmpty() == true)
            assertTrue(result.agentStats?.isEmpty() == true)
        }
    }

    @Nested
    @DisplayName("Get Time Series Data Tests")
    inner class GetTimeSeriesDataTests {

        @Test
        @DisplayName("getTimeSeriesData - Query by hour granularity and fill missing points")
        fun `getTimeSeriesData should query by hour granularity and fill missing points`() {
            // Given - 3 hour range with data only at the first hour
            val startTime = "2026-01-01 00:00:00"
            val endTime = "2026-01-01 02:00:00"
            val dataRow = timeSeriesRow(LocalDateTime.of(2026, 1, 1, 0, 0, 0))

            `when`(tokenStatsMapper.getTimeSeriesByHour(startTime, endTime)).thenReturn(mutableListOf(dataRow))

            // When
            val result = createService().getTimeSeriesData(startTime, endTime, "hour")

            // Then
            assertNotNull(result.timeSeriesData)
            // 00:00, 01:00, 02:00 -> 3 points
            assertEquals(3, result.timeSeriesData?.size)
            // First point has data
            assertEquals(10L, result.timeSeriesData?.get(0)?.totalInputToken)
            // Filled points have zero values
            assertEquals(0L, result.timeSeriesData?.get(1)?.totalInputToken)
            assertEquals(0L, result.timeSeriesData?.get(2)?.grandTotalToken)
            verify(tokenStatsMapper).getTimeSeriesByHour(startTime, endTime)
        }

        @Test
        @DisplayName("getTimeSeriesData - Query by day granularity")
        fun `getTimeSeriesData should query by day granularity`() {
            // Given - 3 day range without any data
            val startTime = "2026-01-01 00:00:00"
            val endTime = "2026-01-03 00:00:00"

            `when`(tokenStatsMapper.getTimeSeriesByDay(startTime, endTime)).thenReturn(mutableListOf())

            // When
            val result = createService().getTimeSeriesData(startTime, endTime, "day")

            // Then
            assertNotNull(result.timeSeriesData)
            assertEquals(3, result.timeSeriesData?.size)
            // All points filled with zeros
            result.timeSeriesData?.forEach {
                assertEquals(0L, it.totalInputToken)
                assertEquals(0L, it.totalOutputToken)
                assertEquals(0L, it.grandTotalToken)
            }
            verify(tokenStatsMapper).getTimeSeriesByDay(startTime, endTime)
        }

        @Test
        @DisplayName("getTimeSeriesData - Query by month granularity")
        fun `getTimeSeriesData should query by month granularity`() {
            // Given - 3 month range
            val startTime = "2026-01-01 00:00:00"
            val endTime = "2026-03-01 00:00:00"

            `when`(tokenStatsMapper.getTimeSeriesByMonth(startTime, endTime)).thenReturn(mutableListOf())

            // When
            val result = createService().getTimeSeriesData(startTime, endTime, "month")

            // Then
            assertNotNull(result.timeSeriesData)
            assertEquals(3, result.timeSeriesData?.size)
            verify(tokenStatsMapper).getTimeSeriesByMonth(startTime, endTime)
        }

        @Test
        @DisplayName("getTimeSeriesData - Downgrade week granularity to day")
        fun `getTimeSeriesData should downgrade week granularity to day`() {
            // Given
            val startTime = "2026-01-01 00:00:00"
            val endTime = "2026-01-02 00:00:00"

            `when`(tokenStatsMapper.getTimeSeriesByDay(startTime, endTime)).thenReturn(mutableListOf())

            // When
            val result = createService().getTimeSeriesData(startTime, endTime, "week")

            // Then
            assertNotNull(result.timeSeriesData)
            assertEquals(2, result.timeSeriesData?.size)
            // Weekly statistics not supported: falls back to day query
            verify(tokenStatsMapper).getTimeSeriesByDay(startTime, endTime)
            verify(tokenStatsMapper, never()).getTimeSeriesByWeek(anyOrNull(), anyOrNull())
        }

        @Test
        @DisplayName("getTimeSeriesData - Unknown granularity falls back to day")
        fun `getTimeSeriesData should fall back to day for unknown granularity`() {
            // Given
            val startTime = "2026-01-01 00:00:00"
            val endTime = "2026-01-01 00:00:00"

            `when`(tokenStatsMapper.getTimeSeriesByDay(startTime, endTime)).thenReturn(mutableListOf())

            // When
            val result = createService().getTimeSeriesData(startTime, endTime, "quarter")

            // Then
            assertNotNull(result.timeSeriesData)
            assertEquals(1, result.timeSeriesData?.size)
            verify(tokenStatsMapper).getTimeSeriesByDay(startTime, endTime)
        }
    }

    @Nested
    @DisplayName("Get Model Time Series Data Tests")
    inner class GetModelTimeSeriesDataTests {

        @Test
        @DisplayName("getModelTimeSeriesData - Query by hour and map model dimension")
        fun `getModelTimeSeriesData should query by hour and map model dimension`() {
            // Given
            val startTime = "2026-01-01 00:00:00"
            val endTime = "2026-01-01 01:00:00"
            val dataRow = timeSeriesRow(
                LocalDateTime.of(2026, 1, 1, 0, 0, 0),
                extra = mapOf("modelId" to 1L, "modelName" to "gpt-4"),
            )

            `when`(tokenStatsMapper.getModelTimeSeriesByHour(startTime, endTime)).thenReturn(mutableListOf(dataRow))

            // When
            val result = createService().getModelTimeSeriesData(startTime, endTime, "hour")

            // Then
            assertNotNull(result.timeSeriesData)
            // 2 hour points for model dimension 1
            assertEquals(2, result.timeSeriesData?.size)
            assertEquals("1", result.timeSeriesData?.get(0)?.dimensionId)
            assertEquals("gpt-4", result.timeSeriesData?.get(0)?.dimensionName)
            // Point with data
            assertEquals(10L, result.timeSeriesData?.get(0)?.totalInputToken)
            // Filled point keeps dimension info
            assertEquals("gpt-4", result.timeSeriesData?.get(1)?.dimensionName)
            assertEquals(0L, result.timeSeriesData?.get(1)?.totalInputToken)
            verify(tokenStatsMapper).getModelTimeSeriesByHour(startTime, endTime)
        }

        @Test
        @DisplayName("getModelTimeSeriesData - Query by day granularity")
        fun `getModelTimeSeriesData should query by day granularity`() {
            // Given
            val startTime = "2026-01-01 00:00:00"
            val endTime = "2026-01-01 00:00:00"

            `when`(tokenStatsMapper.getModelTimeSeriesByDay(startTime, endTime)).thenReturn(mutableListOf())

            // When
            val result = createService().getModelTimeSeriesData(startTime, endTime, "day")

            // Then
            assertNotNull(result.timeSeriesData)
            // No data means no dimension groups, result is empty
            assertTrue(result.timeSeriesData?.isEmpty() == true)
            verify(tokenStatsMapper).getModelTimeSeriesByDay(startTime, endTime)
        }

        @Test
        @DisplayName("getModelTimeSeriesData - Query by month granularity")
        fun `getModelTimeSeriesData should query by month granularity`() {
            // Given
            val startTime = "2026-01-01 00:00:00"
            val endTime = "2026-02-01 00:00:00"
            val dataRow = timeSeriesRow(
                LocalDateTime.of(2026, 1, 1, 0, 0, 0),
                extra = mapOf("modelId" to 1L, "modelName" to "gpt-4"),
            )

            `when`(tokenStatsMapper.getModelTimeSeriesByMonth(startTime, endTime)).thenReturn(mutableListOf(dataRow))

            // When
            val result = createService().getModelTimeSeriesData(startTime, endTime, "month")

            // Then
            assertNotNull(result.timeSeriesData)
            assertEquals(2, result.timeSeriesData?.size)
            verify(tokenStatsMapper).getModelTimeSeriesByMonth(startTime, endTime)
        }

        @Test
        @DisplayName("getModelTimeSeriesData - Downgrade week granularity to day")
        fun `getModelTimeSeriesData should downgrade week granularity to day`() {
            // Given
            val startTime = "2026-01-01 00:00:00"
            val endTime = "2026-01-01 00:00:00"

            `when`(tokenStatsMapper.getModelTimeSeriesByDay(startTime, endTime)).thenReturn(mutableListOf())

            // When
            createService().getModelTimeSeriesData(startTime, endTime, "week")

            // Then
            verify(tokenStatsMapper).getModelTimeSeriesByDay(startTime, endTime)
        }
    }

    @Nested
    @DisplayName("Get Agent Time Series Data Tests")
    inner class GetAgentTimeSeriesDataTests {

        @Test
        @DisplayName("getAgentTimeSeriesData - Query by day and map agent dimension")
        fun `getAgentTimeSeriesData should query by day and map agent dimension`() {
            // Given
            val startTime = "2026-01-01 00:00:00"
            val endTime = "2026-01-02 00:00:00"
            val dataRow = timeSeriesRow(
                LocalDateTime.of(2026, 1, 1, 0, 0, 0),
                extra = mapOf("agentId" to 100L, "agentName" to "Test Agent"),
            )

            `when`(tokenStatsMapper.getAgentTimeSeriesByDay(startTime, endTime)).thenReturn(mutableListOf(dataRow))

            // When
            val result = createService().getAgentTimeSeriesData(startTime, endTime, "day")

            // Then
            assertNotNull(result.timeSeriesData)
            assertEquals(2, result.timeSeriesData?.size)
            assertEquals("100", result.timeSeriesData?.get(0)?.dimensionId)
            assertEquals("Test Agent", result.timeSeriesData?.get(0)?.dimensionName)
            verify(tokenStatsMapper).getAgentTimeSeriesByDay(startTime, endTime)
        }

        @Test
        @DisplayName("getAgentTimeSeriesData - Query by hour granularity")
        fun `getAgentTimeSeriesData should query by hour granularity`() {
            // Given
            val startTime = "2026-01-01 00:00:00"
            val endTime = "2026-01-01 00:00:00"

            `when`(tokenStatsMapper.getAgentTimeSeriesByHour(startTime, endTime)).thenReturn(mutableListOf())

            // When
            val result = createService().getAgentTimeSeriesData(startTime, endTime, "hour")

            // Then
            assertNotNull(result.timeSeriesData)
            assertTrue(result.timeSeriesData?.isEmpty() == true)
            verify(tokenStatsMapper).getAgentTimeSeriesByHour(startTime, endTime)
        }

        @Test
        @DisplayName("getAgentTimeSeriesData - Query by month granularity")
        fun `getAgentTimeSeriesData should query by month granularity`() {
            // Given
            val startTime = "2026-01-01 00:00:00"
            val endTime = "2026-01-01 00:00:00"

            `when`(tokenStatsMapper.getAgentTimeSeriesByMonth(startTime, endTime)).thenReturn(mutableListOf())

            // When
            createService().getAgentTimeSeriesData(startTime, endTime, "month")

            // Then
            verify(tokenStatsMapper).getAgentTimeSeriesByMonth(startTime, endTime)
        }

        @Test
        @DisplayName("getAgentTimeSeriesData - Group multiple agents into separate series")
        fun `getAgentTimeSeriesData should group multiple agents into separate series`() {
            // Given
            val startTime = "2026-01-01 00:00:00"
            val endTime = "2026-01-01 00:00:00"
            val agent1Row = timeSeriesRow(
                LocalDateTime.of(2026, 1, 1, 0, 0, 0),
                extra = mapOf("agentId" to 100L, "agentName" to "Agent A"),
            )
            val agent2Row = timeSeriesRow(
                LocalDateTime.of(2026, 1, 1, 0, 0, 0),
                extra = mapOf("agentId" to 200L, "agentName" to "Agent B"),
            )

            `when`(tokenStatsMapper.getAgentTimeSeriesByDay(startTime, endTime))
                .thenReturn(mutableListOf(agent1Row, agent2Row))

            // When
            val result = createService().getAgentTimeSeriesData(startTime, endTime, "day")

            // Then
            assertNotNull(result.timeSeriesData)
            // 1 day point per agent, 2 agents -> 2 records
            assertEquals(2, result.timeSeriesData?.size)
            val names = result.timeSeriesData?.map { it.dimensionName }?.toSet()
            assertEquals(setOf("Agent A", "Agent B"), names)
        }
    }

    @Nested
    @DisplayName("Get Session Time Series Data Tests")
    inner class GetSessionTimeSeriesDataTests {

        @Test
        @DisplayName("getSessionTimeSeriesData - Query by day and map session dimension")
        fun `getSessionTimeSeriesData should query by day and map session dimension`() {
            // Given
            val startTime = "2026-01-01 00:00:00"
            val endTime = "2026-01-01 00:00:00"
            val dataRow = timeSeriesRow(
                LocalDateTime.of(2026, 1, 1, 0, 0, 0),
                extra = mapOf("sessionId" to "web-session-1", "sessionTitle" to "Test Session"),
            )

            `when`(tokenStatsMapper.getSessionTimeSeriesByDay(startTime, endTime)).thenReturn(mutableListOf(dataRow))

            // When
            val result = createService().getSessionTimeSeriesData(startTime, endTime, "day")

            // Then
            assertNotNull(result.timeSeriesData)
            assertEquals(1, result.timeSeriesData?.size)
            assertEquals("web-session-1", result.timeSeriesData?.get(0)?.dimensionId)
            assertEquals("Test Session", result.timeSeriesData?.get(0)?.dimensionName)
            assertEquals(10L, result.timeSeriesData?.get(0)?.totalInputToken)
            verify(tokenStatsMapper).getSessionTimeSeriesByDay(startTime, endTime)
        }

        @Test
        @DisplayName("getSessionTimeSeriesData - Query by hour granularity")
        fun `getSessionTimeSeriesData should query by hour granularity`() {
            // Given
            val startTime = "2026-01-01 00:00:00"
            val endTime = "2026-01-01 00:00:00"

            `when`(tokenStatsMapper.getSessionTimeSeriesByHour(startTime, endTime)).thenReturn(mutableListOf())

            // When
            createService().getSessionTimeSeriesData(startTime, endTime, "hour")

            // Then
            verify(tokenStatsMapper).getSessionTimeSeriesByHour(startTime, endTime)
        }

        @Test
        @DisplayName("getSessionTimeSeriesData - Query by month granularity")
        fun `getSessionTimeSeriesData should query by month granularity`() {
            // Given
            val startTime = "2026-01-01 00:00:00"
            val endTime = "2026-01-01 00:00:00"

            `when`(tokenStatsMapper.getSessionTimeSeriesByMonth(startTime, endTime)).thenReturn(mutableListOf())

            // When
            createService().getSessionTimeSeriesData(startTime, endTime, "month")

            // Then
            verify(tokenStatsMapper).getSessionTimeSeriesByMonth(startTime, endTime)
        }

        @Test
        @DisplayName("getSessionTimeSeriesData - Return empty data when no records")
        fun `getSessionTimeSeriesData should return empty data when no records`() {
            // Given
            val startTime = "2026-01-01 00:00:00"
            val endTime = "2026-01-03 00:00:00"

            `when`(tokenStatsMapper.getSessionTimeSeriesByDay(startTime, endTime)).thenReturn(mutableListOf())

            // When
            val result = createService().getSessionTimeSeriesData(startTime, endTime, "day")

            // Then
            assertNotNull(result.timeSeriesData)
            // Dimension time series with no data has no dimension groups
            assertTrue(result.timeSeriesData?.isEmpty() == true)
        }
    }
}

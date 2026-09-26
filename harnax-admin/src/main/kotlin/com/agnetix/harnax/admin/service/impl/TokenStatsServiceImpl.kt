package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.OverallStats
import com.agnetix.harnax.admin.dto.TokenStatsAggregationResponse
import com.agnetix.harnax.admin.service.TokenStatsService
import com.agnetix.harnax.mapper.TokenStatsMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Token consumption statistics service implementation
 */
@Service
class TokenStatsServiceImpl(
    private val tokenStatsMapper: TokenStatsMapper,
) : TokenStatsService {

    companion object {
        /**
         * The one format the window bounds, the generated bucket keys and the response `timePoint` use
         */
        private val TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }

    private val log = LoggerFactory.getLogger(TokenStatsServiceImpl::class.java)

    override fun getAggregationStats(startTime: String, endTime: String, tenantId: Long): TokenStatsAggregationResponse {
        log.info(
            "Fetching Token aggregation statistics, startTime: {}, endTime: {}, tenantId: {}",
            startTime,
            endTime,
            tenantId,
        )

        val response = TokenStatsAggregationResponse()

        // Get overall statistics
        val overallMap = tokenStatsMapper.getOverallStats(startTime, endTime, tenantId)
        if (overallMap != null) {
            response.overall = TokenStatsAggregationResponse.mapToOverallStats(overallMap as Map<String, Any?>)
        } else {
            response.overall = OverallStats()
        }

        // Aggregate by model
        val modelData = tokenStatsMapper.aggregateByModel(startTime, endTime, tenantId)
        response.modelStats = modelData!!.map { TokenStatsAggregationResponse.mapToModelStats(it as Map<String, Any?>) }

        // Aggregate by session
        val sessionData = tokenStatsMapper.aggregateBySession(startTime, endTime, tenantId)
        response.sessionStats =
            sessionData!!.map { TokenStatsAggregationResponse.mapToSessionStats(it as Map<String, Any?>) }

        // Aggregate by agent
        val agentData = tokenStatsMapper.aggregateByAgent(startTime, endTime, tenantId)
        response.agentStats = agentData!!.map { TokenStatsAggregationResponse.mapToAgentStats(it as Map<String, Any?>) }

        log.info("Token aggregation statistics retrieval completed")
        return response
    }

    override fun getTimeSeriesData(
        startTime: String,
        endTime: String,
        granularity: String,
        tenantId: Long,
    ): TokenStatsAggregationResponse {
        log.info(
            "Fetching Token time series data, startTime: {}, endTime: {}, granularity: {}, tenantId: {}",
            startTime,
            endTime,
            granularity,
            tenantId,
        )

        val response = TokenStatsAggregationResponse()

        val timeSeriesData = when (granularity) {
            "hour" -> tokenStatsMapper.getTimeSeriesByHour(startTime, endTime, tenantId)
            "week" -> tokenStatsMapper.getTimeSeriesByWeek(startTime, endTime, tenantId)
            "month" -> tokenStatsMapper.getTimeSeriesByMonth(startTime, endTime, tenantId)
            else -> tokenStatsMapper.getTimeSeriesByDay(startTime, endTime, tenantId)
        }

        // Every bucket in the window gets a point; the ones without traffic report 0
        val filledTimeSeriesData = fillTimePoints(timeSeriesData, startTime, endTime, granularity)

        response.timeSeriesData = filledTimeSeriesData.map { TokenStatsAggregationResponse.mapToTimeSeriesData(it) }

        log.info("Token time series data retrieval completed, total {} records", response.timeSeriesData!!.size)
        return response
    }

    override fun getModelTimeSeriesData(
        startTime: String,
        endTime: String,
        granularity: String,
        tenantId: Long,
    ): TokenStatsAggregationResponse {
        log.info(
            "Fetching model time series data, startTime: {}, endTime: {}, granularity: {}, tenantId: {}",
            startTime,
            endTime,
            granularity,
            tenantId,
        )
        return getDimensionTimeSeriesData(startTime, endTime, granularity, "model", tenantId)
    }

    override fun getAgentTimeSeriesData(
        startTime: String,
        endTime: String,
        granularity: String,
        tenantId: Long,
    ): TokenStatsAggregationResponse {
        log.info(
            "Fetching agent time series data, startTime: {}, endTime: {}, granularity: {}, tenantId: {}",
            startTime,
            endTime,
            granularity,
            tenantId,
        )
        return getDimensionTimeSeriesData(startTime, endTime, granularity, "agent", tenantId)
    }

    override fun getSessionTimeSeriesData(
        startTime: String,
        endTime: String,
        granularity: String,
        tenantId: Long,
    ): TokenStatsAggregationResponse {
        log.info(
            "Fetching session time series data, startTime: {}, endTime: {}, granularity: {}, tenantId: {}",
            startTime,
            endTime,
            granularity,
            tenantId,
        )
        return getDimensionTimeSeriesData(startTime, endTime, granularity, "session", tenantId)
    }

    /**
     * Generic dimension time series data query
     */
    private fun getDimensionTimeSeriesData(
        startTime: String,
        endTime: String,
        granularity: String,
        dimensionType: String,
        tenantId: Long,
    ): TokenStatsAggregationResponse {
        val response = TokenStatsAggregationResponse()

        val timeSeriesData = when (dimensionType) {
            "model" -> when (granularity) {
                "hour" -> tokenStatsMapper.getModelTimeSeriesByHour(startTime, endTime, tenantId)
                "week" -> tokenStatsMapper.getModelTimeSeriesByWeek(startTime, endTime, tenantId)
                "month" -> tokenStatsMapper.getModelTimeSeriesByMonth(startTime, endTime, tenantId)
                else -> tokenStatsMapper.getModelTimeSeriesByDay(startTime, endTime, tenantId)
            }

            "agent" -> when (granularity) {
                "hour" -> tokenStatsMapper.getAgentTimeSeriesByHour(startTime, endTime, tenantId)
                "week" -> tokenStatsMapper.getAgentTimeSeriesByWeek(startTime, endTime, tenantId)
                "month" -> tokenStatsMapper.getAgentTimeSeriesByMonth(startTime, endTime, tenantId)
                else -> tokenStatsMapper.getAgentTimeSeriesByDay(startTime, endTime, tenantId)
            }

            "session" -> when (granularity) {
                "hour" -> tokenStatsMapper.getSessionTimeSeriesByHour(startTime, endTime, tenantId)
                "week" -> tokenStatsMapper.getSessionTimeSeriesByWeek(startTime, endTime, tenantId)
                "month" -> tokenStatsMapper.getSessionTimeSeriesByMonth(startTime, endTime, tenantId)
                else -> tokenStatsMapper.getSessionTimeSeriesByDay(startTime, endTime, tenantId)
            }

            else -> emptyList()
        }

        // Fill time points by dimension
        val filledTimeSeriesData = fillDimensionTimePoints(
            timeSeriesData,
            startTime,
            endTime,
            granularity,
            dimensionType,
        )

        response.timeSeriesData = filledTimeSeriesData.map {
            TokenStatsAggregationResponse.mapToDimensionTimeSeriesData(it, dimensionType)
        }

        log.info("{} time series data retrieval completed, total {} records", dimensionType, response.timeSeriesData!!.size)
        return response
    }

    /**
     * Fill time points so every bucket in the window has a point, the ones without data being 0
     */
    private fun fillTimePoints(
        queryData: MutableList<MutableMap<String?, Any?>?>?,
        startTimeStr: String,
        endTimeStr: String,
        granularity: String,
    ): List<Map<String?, Any?>> {
        val rowsByBucket = rowsByBucket(queryData)
        return timePoints(startTimeStr, endTimeStr, granularity).map { timePoint ->
            val row = rowsByBucket[timePoint]
            if (row == null) emptyBucket(timePoint) else row.withTimePoint(timePoint)
        }
    }

    /**
     * Fill time points per dimension, so each dimension reports a point for every bucket in the window
     */
    private fun fillDimensionTimePoints(
        queryData: List<MutableMap<String?, Any?>?>?,
        startTimeStr: String,
        endTimeStr: String,
        granularity: String,
        dimensionType: String,
    ): List<Map<String?, Any?>> {
        val idField = when (dimensionType) {
            "session" -> "sessionId"
            "agent" -> "agentId"
            else -> "modelId"
        }
        val nameField = when (dimensionType) {
            "session" -> "sessionTitle"
            "agent" -> "agentName"
            else -> "modelName"
        }

        val dimensionGroups = mutableMapOf<String, MutableList<MutableMap<String?, Any?>?>>()
        for (data in queryData ?: emptyList()) {
            val dimensionId = data?.get(idField)?.toString() ?: "unknown"
            dimensionGroups.computeIfAbsent(dimensionId) { mutableListOf() }.add(data)
        }

        val points = timePoints(startTimeStr, endTimeStr, granularity)
        val result = mutableListOf<Map<String?, Any?>>()
        for (rows in dimensionGroups.values) {
            val rowsByBucket = rowsByBucket(rows)
            // A zero-filled bucket still has to carry its dimension, else the series loses its legend.
            // Names stay nullable: the LEFT JOIN yields NULL once the model/agent/session row is gone.
            val sample = rows.firstOrNull { it != null } ?: emptyMap()
            for (timePoint in points) {
                val row = rowsByBucket[timePoint]
                result.add(
                    if (row == null) {
                        emptyBucket(timePoint).apply {
                            put(idField, sample[idField])
                            put(nameField, sample[nameField])
                        }
                    } else {
                        row.withTimePoint(timePoint)
                    },
                )
            }
        }
        return result
    }

    /**
     * Index series rows by the bucket they belong to
     *
     * Every statement CASTs its bucket to DATETIME at the bucket start, so a row keys with the same full
     * timestamp the generated points use. The month branch used to build the lookup key as `yyyy-MM`, which
     * matched nothing and left an otherwise correct query rendering as an all-zero series.
     */
    private fun rowsByBucket(
        queryData: List<MutableMap<String?, Any?>?>?,
    ): Map<String, MutableMap<String?, Any?>> {
        val rows = mutableMapOf<String, MutableMap<String?, Any?>>()
        for (data in queryData ?: emptyList()) {
            val timePoint = data?.get("timePoint") as? LocalDateTime ?: continue
            rows[timePoint.format(TIMESTAMP_FORMATTER)] = data
        }
        return rows
    }

    /**
     * Every bucket key from the window start to its end, inclusive
     */
    private fun timePoints(
        startTimeStr: String,
        endTimeStr: String,
        granularity: String,
    ): List<String> {
        val endTime = LocalDateTime.parse(endTimeStr, TIMESTAMP_FORMATTER)
        val points = mutableListOf<String>()
        var currentTime = alignToBucketStart(LocalDateTime.parse(startTimeStr, TIMESTAMP_FORMATTER), granularity)
        while (!currentTime.isAfter(endTime)) {
            points.add(currentTime.format(TIMESTAMP_FORMATTER))
            currentTime = nextBucket(currentTime, granularity)
        }
        return points
    }

    /**
     * Roll a timestamp down to the start of its bucket. Week buckets start on Monday to match the
     * `weekBucket` fragment in TokenStatsMapper.xml — any other alignment keys every generated point
     * away from its row and zeroes the series.
     */
    private fun alignToBucketStart(
        time: LocalDateTime,
        granularity: String,
    ): LocalDateTime = when (granularity) {
        "hour" -> time.withMinute(0).withSecond(0).withNano(0)

        "week" -> time.minusDays((time.dayOfWeek.value - 1).toLong())
            .withHour(0).withMinute(0).withSecond(0).withNano(0)

        "month" -> time.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0)
        else -> time.withHour(0).withMinute(0).withSecond(0).withNano(0)
    }

    private fun nextBucket(
        time: LocalDateTime,
        granularity: String,
    ): LocalDateTime = when (granularity) {
        "hour" -> time.plusHours(1)
        "week" -> time.plusWeeks(1)
        "month" -> time.plusMonths(1)
        else -> time.plusDays(1)
    }

    private fun emptyBucket(timePoint: String): MutableMap<String?, Any?> = mutableMapOf(
        "timePoint" to timePoint,
        "totalInputToken" to 0L,
        "totalOutputToken" to 0L,
        "grandTotalToken" to 0L,
        "totalFee" to BigDecimal.ZERO,
    )

    /**
     * Rows come back with a DATETIME bucket, the response contract wants it as the display string
     */
    private fun MutableMap<String?, Any?>.withTimePoint(timePoint: String): MutableMap<String?, Any?> = toMutableMap().apply { put("timePoint", timePoint) }
}

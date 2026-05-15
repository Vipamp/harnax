package com.vipamp.vipclaw.admin.service.impl

import com.vipamp.vipclaw.admin.dto.OverallStats
import com.vipamp.vipclaw.admin.dto.TokenStatsAggregationResponse
import com.vipamp.vipclaw.admin.entity.TokenStats
import com.vipamp.vipclaw.admin.mapper.TokenStatsMapper
import com.vipamp.vipclaw.admin.service.TokenStatsService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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

    private val log = LoggerFactory.getLogger(TokenStatsServiceImpl::class.java)

    @Transactional(rollbackFor = [Exception::class])
    override fun saveTokenStats(tokenStats: TokenStats): Boolean {
        log.info(
            "Saving Token consumption record, agentId: {}, sessionId: {}, chatModelId: {}, totalToken: {}",
            tokenStats.agentId,
            tokenStats.sessionId,
            tokenStats.chatModelId,
            tokenStats.totalToken,
        )

        val success = this.tokenStatsMapper.insert(tokenStats) > 0
        log.info("Token consumption record saved {}", if (success) "successfully" else "failed")
        return success
    }

    override fun getAggregationStats(startTime: String, endTime: String): TokenStatsAggregationResponse {
        log.info("Fetching Token aggregation statistics, startTime: {}, endTime: {}", startTime, endTime)

        val response = TokenStatsAggregationResponse()

        // Get overall statistics
        val overallMap = tokenStatsMapper.getOverallStats(startTime, endTime)
        if (overallMap != null) {
            response.overall = TokenStatsAggregationResponse.mapToOverallStats(overallMap as Map<String, Any?>)
        } else {
            response.overall = OverallStats()
        }

        // Aggregate by model
        val modelData = tokenStatsMapper.aggregateByModel(startTime, endTime)
        response.modelStats = modelData!!.map { TokenStatsAggregationResponse.mapToModelStats(it as Map<String, Any?>) }

        // Aggregate by session
        val sessionData = tokenStatsMapper.aggregateBySession(startTime, endTime)
        response.sessionStats =
            sessionData!!.map { TokenStatsAggregationResponse.mapToSessionStats(it as Map<String, Any?>) }

        // Aggregate by agent
        val agentData = tokenStatsMapper.aggregateByAgent(startTime, endTime)
        response.agentStats = agentData!!.map { TokenStatsAggregationResponse.mapToAgentStats(it as Map<String, Any?>) }

        log.info("Token aggregation statistics retrieval completed")
        return response
    }

    override fun getTimeSeriesData(
        startTime: String,
        endTime: String,
        granularity: String,
    ): TokenStatsAggregationResponse {
        log.info(
            "Fetching Token time series data, startTime: {}, endTime: {}, granularity: {}",
            startTime,
            endTime,
            granularity,
        )

        val response = TokenStatsAggregationResponse()

        // Weekly statistics not supported
        var actualGranularity = if ("week" == granularity) "day" else granularity

        // Query time series data based on time granularity
        val timeSeriesData = when (actualGranularity) {
            "hour" -> tokenStatsMapper.getTimeSeriesByHour(startTime, endTime)
            "month" -> tokenStatsMapper.getTimeSeriesByMonth(startTime, endTime)
            else -> tokenStatsMapper.getTimeSeriesByDay(startTime, endTime)
        }

        // Fill all time points (even if no data, should display as 0)
        val filledTimeSeriesData = fillTimePoints(timeSeriesData, startTime, endTime, actualGranularity)

        response.timeSeriesData = filledTimeSeriesData.map { TokenStatsAggregationResponse.mapToTimeSeriesData(it) }

        log.info("Token time series data retrieval completed, total {} records", response.timeSeriesData!!.size)
        return response
    }

    override fun getModelTimeSeriesData(
        startTime: String,
        endTime: String,
        granularity: String,
    ): TokenStatsAggregationResponse {
        log.info("Fetching model time series data, startTime: {}, endTime: {}, granularity: {}", startTime, endTime, granularity)
        return getDimensionTimeSeriesData(startTime, endTime, granularity, "model")
    }

    override fun getAgentTimeSeriesData(
        startTime: String,
        endTime: String,
        granularity: String,
    ): TokenStatsAggregationResponse {
        log.info("Fetching agent time series data, startTime: {}, endTime: {}, granularity: {}", startTime, endTime, granularity)
        return getDimensionTimeSeriesData(startTime, endTime, granularity, "agent")
    }

    override fun getSessionTimeSeriesData(
        startTime: String,
        endTime: String,
        granularity: String,
    ): TokenStatsAggregationResponse {
        log.info("Fetching session time series data, startTime: {}, endTime: {}, granularity: {}", startTime, endTime, granularity)
        return getDimensionTimeSeriesData(startTime, endTime, granularity, "session")
    }

    /**
     * Generic dimension time series data query
     */
    private fun getDimensionTimeSeriesData(
        startTime: String,
        endTime: String,
        granularity: String,
        dimensionType: String,
    ): TokenStatsAggregationResponse {
        val response = TokenStatsAggregationResponse()

        // Weekly statistics not supported
        var actualGranularity = if ("week" == granularity) "day" else granularity

        // Query time series data based on dimension type and time granularity
        val timeSeriesData = when (dimensionType) {
            "model" -> when (actualGranularity) {
                "hour" -> tokenStatsMapper.getModelTimeSeriesByHour(startTime, endTime)
                "month" -> tokenStatsMapper.getModelTimeSeriesByMonth(startTime, endTime)
                else -> tokenStatsMapper.getModelTimeSeriesByDay(startTime, endTime)
            }

            "agent" -> when (actualGranularity) {
                "hour" -> tokenStatsMapper.getAgentTimeSeriesByHour(startTime, endTime)
                "month" -> tokenStatsMapper.getAgentTimeSeriesByMonth(startTime, endTime)
                else -> tokenStatsMapper.getAgentTimeSeriesByDay(startTime, endTime)
            }

            "session" -> when (actualGranularity) {
                "hour" -> tokenStatsMapper.getSessionTimeSeriesByHour(startTime, endTime)
                "month" -> tokenStatsMapper.getSessionTimeSeriesByMonth(startTime, endTime)
                else -> tokenStatsMapper.getSessionTimeSeriesByDay(startTime, endTime)
            }

            else -> emptyList()
        }

        // Fill time points by dimension
        val filledTimeSeriesData = fillDimensionTimePoints(
            timeSeriesData,
            startTime,
            endTime,
            actualGranularity,
            dimensionType,
        )

        response.timeSeriesData = filledTimeSeriesData.map {
            TokenStatsAggregationResponse.mapToDimensionTimeSeriesData(it as MutableMap<String?, Any?>, dimensionType)
        }

        log.info("{} time series data retrieval completed, total {} records", dimensionType, response.timeSeriesData!!.size)
        return response
    }

    /**
     * Fill time points to ensure all time points from start to end have data (points without data are 0)
     */
    private fun fillTimePoints(
        queryData: MutableList<MutableMap<String?, Any?>?>?,
        startTimeStr: String,
        endTimeStr: String,
        granularity: String,
    ): List<Map<String, Any>> {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val startTime = LocalDateTime.parse(startTimeStr, formatter)
        val endTime = LocalDateTime.parse(endTimeStr, formatter)

        // Convert query results to Map for easy lookup
        val dataMap = mutableMapOf<String, Map<String?, Any?>?>()
        for (data in queryData ?: emptyList()) {
            val timePointObj = data?.get("timePoint") as? LocalDateTime?
            if (timePointObj != null) {
                val timeKey = if (true) {
                    timePointObj.format(formatter)
                } else {
                    timePointObj.toString()
                }
                dataMap[timeKey] = data
            }
        }

        val result = mutableListOf<Map<String, Any?>?>()
        var currentTime = startTime

        // Normalize start time based on granularity
        currentTime = when (granularity) {
            "hour" -> currentTime.withMinute(0).withSecond(0)
            "month" -> currentTime.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0)
            else -> currentTime.withHour(0).withMinute(0).withSecond(0)
        }

        while (!currentTime.isAfter(endTime)) {
            val (timeKey, displayTime) = when (granularity) {
                "hour" -> Pair(
                    currentTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00:00")),
                    currentTime.format(formatter),
                ).also { currentTime = currentTime.plusHours(1) }

                "month" -> Pair(
                    currentTime.format(DateTimeFormatter.ofPattern("yyyy-MM")),
                    currentTime.format(formatter),
                ).also { currentTime = currentTime.plusMonths(1).withDayOfMonth(1) }

                else -> Pair(
                    currentTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd 00:00:00")),
                    currentTime.format(formatter),
                ).also { currentTime = currentTime.plusDays(1) }
            }

            // Find data for this time point, if not found create with 0
            val data = dataMap[timeKey]
            if (data == null) {
                // Create empty data
                val emptyData = mutableMapOf<String, Any>(
                    "timePoint" to displayTime,
                    "totalInputToken" to 0L,
                    "totalOutputToken" to 0L,
                    "grandTotalToken" to 0L,
                    "totalFee" to BigDecimal.ZERO,
                )
                result.add(emptyData)
            } else {
                // Use queried data, but update timePoint to display time
                val mutableData = data.toMutableMap()
                mutableData["timePoint"] = displayTime
                result.add(mutableData as Map<String, Any?>?)
            }
        }

        return result as List<Map<String, Any>>
    }

    /**
     * Fill dimension time points to ensure all time points from start to end have data for each dimension (points without data are 0)
     */
    private fun fillDimensionTimePoints(
        queryData: List<MutableMap<String?, Any?>?>?,
        startTimeStr: String,
        endTimeStr: String,
        granularity: String,
        dimensionType: String,
    ): List<Map<String, Any>> {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val startTime = LocalDateTime.parse(startTimeStr, formatter)
        val endTime = LocalDateTime.parse(endTimeStr, formatter)

        // Group by dimension ID
        val dimensionIdField = when (dimensionType) {
            "session" -> "sessionId"
            "agent" -> "agentId"
            else -> "modelId"
        }

        val dimensionGroups = mutableMapOf<String, MutableList<Map<String, Any>>>()
        if (queryData != null) {
            for (data in queryData) {
                val dimIdObj = data?.get(dimensionIdField)
                val dimId = dimIdObj?.toString() ?: "unknown"
                dimensionGroups.computeIfAbsent(dimId) { mutableListOf() }.add(data as Map<String, Any>)
            }
        }

        val result = mutableListOf<Map<String, Any>>()

        // Fill time points for each dimension
        for ((dimensionId, dimData) in dimensionGroups) {
            // Convert this dimension's data to Map
            val dataMap = mutableMapOf<String, Map<String, Any>>()
            for (data in dimData) {
                val timePointObj = data["timePoint"]
                if (timePointObj != null) {
                    val timeKey = if (timePointObj is LocalDateTime) {
                        timePointObj.format(formatter)
                    } else {
                        timePointObj.toString()
                    }
                    dataMap[timeKey] = data
                }
            }

            // Fill time points for this dimension
            var currentTime = startTime

            // Normalize start time based on granularity
            currentTime = when (granularity) {
                "hour" -> currentTime.withMinute(0).withSecond(0)
                "month" -> currentTime.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0)
                else -> currentTime.withHour(0).withMinute(0).withSecond(0)
            }

            while (!currentTime.isAfter(endTime)) {
                val (timeKey, displayTime) = when (granularity) {
                    "hour" -> Pair(
                        currentTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00:00")),
                        currentTime.format(formatter),
                    ).also { currentTime = currentTime.plusHours(1) }

                    "month" -> Pair(
                        currentTime.format(DateTimeFormatter.ofPattern("yyyy-MM")),
                        currentTime.format(formatter),
                    ).also { currentTime = currentTime.plusMonths(1).withDayOfMonth(1) }

                    else -> Pair(
                        currentTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd 00:00:00")),
                        currentTime.format(formatter),
                    ).also { currentTime = currentTime.plusDays(1) }
                }

                // Find data for this time point, if not found create with 0
                val data = dataMap[timeKey]
                if (data == null) {
                    // Create empty data, preserve dimension information
                    val emptyData = mutableMapOf<String, Any>(
                        "timePoint" to displayTime,
                        "totalInputToken" to 0L,
                        "totalOutputToken" to 0L,
                        "grandTotalToken" to 0L,
                        "totalFee" to BigDecimal.ZERO,
                    )

                    // Preserve dimension fields
                    val sampleData = if (dimData.isEmpty()) emptyMap() else dimData[0]
                    when (dimensionType) {
                        "model" -> {
                            emptyData["modelId"] = sampleData["modelId"]!!
                            emptyData["modelName"] = sampleData["modelName"]!!
                        }

                        "agent" -> {
                            emptyData["agentId"] = sampleData["agentId"]!!
                            emptyData["agentName"] = sampleData["agentName"]!!
                        }

                        "session" -> {
                            emptyData["sessionId"] = sampleData["sessionId"]!!
                            emptyData["sessionTitle"] = sampleData["sessionTitle"]!!
                        }
                    }

                    result.add(emptyData)
                } else {
                    // Use queried data, but update timePoint to display time
                    val mutableData = data.toMutableMap()
                    mutableData["timePoint"] = displayTime
                    result.add(mutableData)
                }
            }
        }

        return result
    }
}

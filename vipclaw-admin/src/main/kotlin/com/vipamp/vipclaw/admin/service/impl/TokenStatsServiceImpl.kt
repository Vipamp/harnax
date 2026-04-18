package com.vipamp.vipclaw.admin.service.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import com.vipamp.vipclaw.admin.dto.TokenStatsAggregationResponse
import com.vipamp.vipclaw.admin.service.TokenStatsService
import com.vipamp.vipclaw.common.entity.TokenStats
import com.vipamp.vipclaw.common.mapper.TokenStatsMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Token 消耗统计服务实现类
 *
 * @author vipamp
 * @since 2026-04-11
 */
@Service
class TokenStatsServiceImpl(
    private val tokenStatsMapper: TokenStatsMapper
) : ServiceImpl<TokenStatsMapper, TokenStats>(), TokenStatsService {

    private val log = LoggerFactory.getLogger(TokenStatsServiceImpl::class.java)

    @Transactional(rollbackFor = [Exception::class])
    override fun saveTokenStats(tokenStats: TokenStats): Boolean {
        log.info(
            "保存 Token 消耗记录, agentId: {}, sessionId: {}, chatModelId: {}, totalToken: {}",
            tokenStats.agentId,
            tokenStats.sessionId,
            tokenStats.chatModelId,
            tokenStats.totalToken
        )

        val success = this.save(tokenStats)
        log.info("Token 消耗记录保存{}", if (success) "成功" else "失败")
        return success
    }

    override fun getAggregationStats(startTime: String, endTime: String): TokenStatsAggregationResponse {
        log.info("获取 Token 聚合统计数据, startTime: {}, endTime: {}", startTime, endTime)

        val response = TokenStatsAggregationResponse()

        // 获取总体统计
        val overallMap = tokenStatsMapper.getOverallStats(startTime, endTime)
        if (overallMap != null) {
            response.overall = TokenStatsAggregationResponse.mapToOverallStats(overallMap)
        } else {
            response.overall = TokenStatsAggregationResponse.OverallStats()
        }

        // 按模型聚合
        val modelData = tokenStatsMapper.aggregateByModel(startTime, endTime)
        response.modelStats = modelData.map { TokenStatsAggregationResponse.mapToModelStats(it) }

        // 按会话聚合
        val sessionData = tokenStatsMapper.aggregateBySession(startTime, endTime)
        response.sessionStats = sessionData.map { TokenStatsAggregationResponse.mapToSessionStats(it) }

        // 按智能体聚合
        val agentData = tokenStatsMapper.aggregateByAgent(startTime, endTime)
        response.agentStats = agentData.map { TokenStatsAggregationResponse.mapToAgentStats(it) }

        log.info("Token 聚合统计数据获取完成")
        return response
    }

    override fun getTimeSeriesData(
        startTime: String,
        endTime: String,
        granularity: String
    ): TokenStatsAggregationResponse {
        log.info("获取 Token 时序统计数据, startTime: {}, endTime: {}, granularity: {}", startTime, endTime, granularity)

        val response = TokenStatsAggregationResponse()

        // 不支持按周统计
        var actualGranularity = if ("week" == granularity) "day" else granularity

        // 根据时间粒度查询时序数据
        val timeSeriesData = when (actualGranularity) {
            "hour" -> tokenStatsMapper.getTimeSeriesByHour(startTime, endTime)
            "month" -> tokenStatsMapper.getTimeSeriesByMonth(startTime, endTime)
            else -> tokenStatsMapper.getTimeSeriesByDay(startTime, endTime)
        }

        // 补全所有时间点（即使没有数据也要显示为0）
        val filledTimeSeriesData = fillTimePoints(timeSeriesData, startTime, endTime, actualGranularity)

        response.timeSeriesData = filledTimeSeriesData.map { TokenStatsAggregationResponse.mapToTimeSeriesData(it) }

        log.info("Token 时序统计数据获取完成，共 {} 条记录", response.timeSeriesData.size)
        return response
    }

    override fun getModelTimeSeriesData(
        startTime: String,
        endTime: String,
        granularity: String
    ): TokenStatsAggregationResponse {
        log.info("获取模型时序统计数据, startTime: {}, endTime: {}, granularity: {}", startTime, endTime, granularity)
        return getDimensionTimeSeriesData(startTime, endTime, granularity, "model")
    }

    override fun getAgentTimeSeriesData(
        startTime: String,
        endTime: String,
        granularity: String
    ): TokenStatsAggregationResponse {
        log.info("获取智能体时序统计数据, startTime: {}, endTime: {}, granularity: {}", startTime, endTime, granularity)
        return getDimensionTimeSeriesData(startTime, endTime, granularity, "agent")
    }

    override fun getSessionTimeSeriesData(
        startTime: String,
        endTime: String,
        granularity: String
    ): TokenStatsAggregationResponse {
        log.info("获取会话时序统计数据, startTime: {}, endTime: {}, granularity: {}", startTime, endTime, granularity)
        return getDimensionTimeSeriesData(startTime, endTime, granularity, "session")
    }

    /**
     * 通用维度时序数据查询
     */
    private fun getDimensionTimeSeriesData(
        startTime: String,
        endTime: String,
        granularity: String,
        dimensionType: String
    ): TokenStatsAggregationResponse {
        val response = TokenStatsAggregationResponse()

        // 不支持按周统计
        var actualGranularity = if ("week" == granularity) "day" else granularity

        // 根据维度类型和时间粒度查询时序数据
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

        // 补全所有时间点(按维度分组补全)
        val filledTimeSeriesData = fillDimensionTimePoints(
            timeSeriesData, startTime, endTime, actualGranularity, dimensionType
        )

        response.timeSeriesData = filledTimeSeriesData.map {
            TokenStatsAggregationResponse.mapToDimensionTimeSeriesData(it, dimensionType)
        }

        log.info("{} 时序统计数据获取完成，共 {} 条记录", dimensionType, response.timeSeriesData.size)
        return response
    }

    /**
     * 补全时间点，确保从开始到结束的所有时间点都有数据（没有数据的点为0）
     */
    private fun fillTimePoints(
        queryData: List<Map<String, Any>>,
        startTimeStr: String,
        endTimeStr: String,
        granularity: String
    ): List<Map<String, Any>> {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val startTime = LocalDateTime.parse(startTimeStr, formatter)
        val endTime = LocalDateTime.parse(endTimeStr, formatter)

        // 将查询结果转为 Map，方便查找
        val dataMap = mutableMapOf<String, Map<String, Any>>()
        for (data in queryData) {
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

        val result = mutableListOf<Map<String, Any>>()
        var currentTime = startTime

        // 根据粒度规范化起始时间
        currentTime = when (granularity) {
            "hour" -> currentTime.withMinute(0).withSecond(0)
            "month" -> currentTime.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0)
            else -> currentTime.withHour(0).withMinute(0).withSecond(0)
        }

        while (!currentTime.isAfter(endTime)) {
            val (timeKey, displayTime) = when (granularity) {
                "hour" -> Pair(
                    currentTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00:00")),
                    currentTime.format(formatter)
                ).also { currentTime = currentTime.plusHours(1) }
                "month" -> Pair(
                    currentTime.format(DateTimeFormatter.ofPattern("yyyy-MM")),
                    currentTime.format(formatter)
                ).also { currentTime = currentTime.plusMonths(1).withDayOfMonth(1) }
                else -> Pair(
                    currentTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd 00:00:00")),
                    currentTime.format(formatter)
                ).also { currentTime = currentTime.plusDays(1) }
            }

            // 查找该时间点的数据，如果没有则创建为0
            val data = dataMap[timeKey]
            if (data == null) {
                // 创建空数据
                val emptyData = mutableMapOf<String, Any>(
                    "timePoint" to displayTime,
                    "totalInputToken" to 0L,
                    "totalOutputToken" to 0L,
                    "grandTotalToken" to 0L,
                    "totalFee" to BigDecimal.ZERO
                )
                result.add(emptyData)
            } else {
                // 使用查询到的数据，但更新 timePoint 为显示时间
                val mutableData = data.toMutableMap()
                mutableData["timePoint"] = displayTime
                result.add(mutableData)
            }
        }

        return result
    }

    /**
     * 补全维度时间点，确保每个维度从开始到结束的所有时间点都有数据（没有数据的点为0）
     */
    private fun fillDimensionTimePoints(
        queryData: List<Map<String, Any>>,
        startTimeStr: String,
        endTimeStr: String,
        granularity: String,
        dimensionType: String
    ): List<Map<String, Any>> {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val startTime = LocalDateTime.parse(startTimeStr, formatter)
        val endTime = LocalDateTime.parse(endTimeStr, formatter)

        // 按维度ID分组
        val dimensionIdField = when (dimensionType) {
            "session" -> "sessionId"
            "agent" -> "agentId"
            else -> "modelId"
        }

        val dimensionGroups = mutableMapOf<String, MutableList<Map<String, Any>>>()
        for (data in queryData) {
            val dimIdObj = data[dimensionIdField]
            val dimId = dimIdObj?.toString() ?: "unknown"
            dimensionGroups.computeIfAbsent(dimId) { mutableListOf() }.add(data)
        }

        val result = mutableListOf<Map<String, Any>>()

        // 对每个维度补全时间点
        for ((dimensionId, dimData) in dimensionGroups) {
            // 将该维度的数据转为 Map
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

            // 补全该维度的时间点
            var currentTime = startTime

            // 根据粒度规范化起始时间
            currentTime = when (granularity) {
                "hour" -> currentTime.withMinute(0).withSecond(0)
                "month" -> currentTime.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0)
                else -> currentTime.withHour(0).withMinute(0).withSecond(0)
            }

            while (!currentTime.isAfter(endTime)) {
                val (timeKey, displayTime) = when (granularity) {
                    "hour" -> Pair(
                        currentTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00:00")),
                        currentTime.format(formatter)
                    ).also { currentTime = currentTime.plusHours(1) }
                    "month" -> Pair(
                        currentTime.format(DateTimeFormatter.ofPattern("yyyy-MM")),
                        currentTime.format(formatter)
                    ).also { currentTime = currentTime.plusMonths(1).withDayOfMonth(1) }
                    else -> Pair(
                        currentTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd 00:00:00")),
                        currentTime.format(formatter)
                    ).also { currentTime = currentTime.plusDays(1) }
                }

                // 查找该时间点的数据，如果没有则创建为0
                val data = dataMap[timeKey]
                if (data == null) {
                    // 创建空数据，保留维度信息
                    val emptyData = mutableMapOf<String, Any>(
                        "timePoint" to displayTime,
                        "totalInputToken" to 0L,
                        "totalOutputToken" to 0L,
                        "grandTotalToken" to 0L,
                        "totalFee" to BigDecimal.ZERO
                    )

                    // 保留维度字段
                    val sampleData = if (dimData.isEmpty()) emptyMap() else dimData[0]
                    when (dimensionType) {
                        "model" -> {
                            emptyData["modelId"] = sampleData["modelId"]
                            emptyData["modelName"] = sampleData["modelName"]
                        }
                        "agent" -> {
                            emptyData["agentId"] = sampleData["agentId"]
                            emptyData["agentName"] = sampleData["agentName"]
                        }
                        "session" -> {
                            emptyData["sessionId"] = sampleData["sessionId"]
                            emptyData["sessionTitle"] = sampleData["sessionTitle"]
                        }
                    }

                    result.add(emptyData)
                } else {
                    // 使用查询到的数据，但更新 timePoint 为显示时间
                    val mutableData = data.toMutableMap()
                    mutableData["timePoint"] = displayTime
                    result.add(mutableData)
                }
            }
        }

        return result
    }
}

package com.vipamp.vipclaw.admin.controller

import com.vipamp.vipclaw.admin.config.RequiresEdition
import com.vipamp.vipclaw.admin.dto.ResultVo
import com.vipamp.vipclaw.admin.dto.TokenStatsAggregationResponse
import com.vipamp.vipclaw.admin.service.TokenStatsService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Token consumption statistics controller
 * Available only for enterprise and public editions
 */
@RestController
@RequestMapping("/api/token-stats")
@Tag(name = "Token Statistics", description = "Token consumption statistics APIs")
@RequiresEdition("enterprise", "public")
class TokenStatsController(
    private val tokenStatsService: TokenStatsService,
) {

    private val log = LoggerFactory.getLogger(TokenStatsController::class.java)
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    @GetMapping("/aggregation")
    @Operation(summary = "Get token aggregation stats", description = "Aggregate token consumption by model, session, and agent")
    fun getAggregationStats(
        @Parameter(description = "Start time")
        @RequestParam(name = "startTime", required = false)
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        startTime: LocalDateTime?,
        @Parameter(description = "End time")
        @RequestParam(name = "endTime", required = false)
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        endTime: LocalDateTime?,
    ): ResultVo<TokenStatsAggregationResponse> = try {
        // Default query last 7 days
        val actualStartTime = startTime ?: LocalDateTime.now().minusDays(7)
        val actualEndTime = endTime ?: LocalDateTime.now()

        val startTimeStr = actualStartTime.format(dateFormatter)
        val endTimeStr = actualEndTime.format(dateFormatter)

        val response = tokenStatsService.getAggregationStats(startTimeStr, endTimeStr)
        ResultVo.success(response)
    } catch (e: Exception) {
        log.error("Failed to get token aggregation stats", e)
        ResultVo.error("Failed to get statistics data: ${e.message}")
    }

    @GetMapping("/time-series")
    @Operation(summary = "Get token time series data", description = "Query token consumption trend by time granularity")
    fun getTimeSeriesData(
        @Parameter(description = "Start time")
        @RequestParam(name = "startTime", required = false)
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        startTime: LocalDateTime?,
        @Parameter(description = "End time")
        @RequestParam(name = "endTime", required = false)
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        endTime: LocalDateTime?,
        @Parameter(description = "Time granularity: hour/day/week/month")
        @RequestParam(name = "granularity", defaultValue = "day")
        granularity: String,
    ): ResultVo<TokenStatsAggregationResponse> = try {
        // Default query last 7 days
        val actualStartTime = startTime ?: LocalDateTime.now().minusDays(7)
        val actualEndTime = endTime ?: LocalDateTime.now()

        val startTimeStr = actualStartTime.format(dateFormatter)
        val endTimeStr = actualEndTime.format(dateFormatter)

        val response = tokenStatsService.getTimeSeriesData(startTimeStr, endTimeStr, granularity)
        ResultVo.success(response)
    } catch (e: Exception) {
        log.error("Failed to get token time series data", e)
        ResultVo.error("Failed to get time series data: ${e.message}")
    }

    @GetMapping("/time-series/model")
    @Operation(summary = "Get model time series data", description = "Query token consumption trend by time granularity and model")
    fun getModelTimeSeriesData(
        @Parameter(description = "Start time")
        @RequestParam(name = "startTime", required = false)
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        startTime: LocalDateTime?,
        @Parameter(description = "End time")
        @RequestParam(name = "endTime", required = false)
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        endTime: LocalDateTime?,
        @Parameter(description = "Time granularity: hour/day/month")
        @RequestParam(name = "granularity", defaultValue = "day")
        granularity: String,
    ): ResultVo<TokenStatsAggregationResponse> = try {
        val actualStartTime = startTime ?: LocalDateTime.now().minusDays(7)
        val actualEndTime = endTime ?: LocalDateTime.now()

        val startTimeStr = actualStartTime.format(dateFormatter)
        val endTimeStr = actualEndTime.format(dateFormatter)

        val response = tokenStatsService.getModelTimeSeriesData(startTimeStr, endTimeStr, granularity)
        ResultVo.success(response)
    } catch (e: Exception) {
        log.error("Failed to get model time series data", e)
        ResultVo.error("Failed to get model time series data: ${e.message}")
    }

    @GetMapping("/time-series/agent")
    @Operation(summary = "Get agent time series data", description = "Query token consumption trend by time granularity and agent")
    fun getAgentTimeSeriesData(
        @Parameter(description = "Start time")
        @RequestParam(name = "startTime", required = false)
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        startTime: LocalDateTime?,
        @Parameter(description = "End time")
        @RequestParam(name = "endTime", required = false)
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        endTime: LocalDateTime?,
        @Parameter(description = "Time granularity: hour/day/month")
        @RequestParam(name = "granularity", defaultValue = "day")
        granularity: String,
    ): ResultVo<TokenStatsAggregationResponse> = try {
        val actualStartTime = startTime ?: LocalDateTime.now().minusDays(7)
        val actualEndTime = endTime ?: LocalDateTime.now()

        val startTimeStr = actualStartTime.format(dateFormatter)
        val endTimeStr = actualEndTime.format(dateFormatter)

        val response = tokenStatsService.getAgentTimeSeriesData(startTimeStr, endTimeStr, granularity)
        ResultVo.success(response)
    } catch (e: Exception) {
        log.error("Failed to get agent time series data", e)
        ResultVo.error("Failed to get agent time series data: ${e.message}")
    }

    @GetMapping("/time-series/session")
    @Operation(summary = "Get session time series data", description = "Query token consumption trend by time granularity and session")
    fun getSessionTimeSeriesData(
        @Parameter(description = "Start time")
        @RequestParam(name = "startTime", required = false)
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        startTime: LocalDateTime?,
        @Parameter(description = "End time")
        @RequestParam(name = "endTime", required = false)
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        endTime: LocalDateTime?,
        @Parameter(description = "Time granularity: hour/day/month")
        @RequestParam(name = "granularity", defaultValue = "day")
        granularity: String,
    ): ResultVo<TokenStatsAggregationResponse> = try {
        val actualStartTime = startTime ?: LocalDateTime.now().minusDays(7)
        val actualEndTime = endTime ?: LocalDateTime.now()

        val startTimeStr = actualStartTime.format(dateFormatter)
        val endTimeStr = actualEndTime.format(dateFormatter)

        val response = tokenStatsService.getSessionTimeSeriesData(startTimeStr, endTimeStr, granularity)
        ResultVo.success(response)
    } catch (e: Exception) {
        log.error("Failed to get session time series data", e)
        ResultVo.error("Failed to get session time series data: ${e.message}")
    }
}

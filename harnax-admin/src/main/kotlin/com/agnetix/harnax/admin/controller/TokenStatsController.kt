package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.TokenStatsAggregationResponse
import com.agnetix.harnax.admin.service.TokenStatsService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.TenantResolver
import com.agnetix.harnax.common.dto.ResultVo
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
 *
 * Every endpoint reads within one tenant, and which tenant is never a query parameter: a client that could
 * name one in the URL could name any other one. [TenantResolver] answers from the request's own
 * credentials — the same chain the writes and the rest of the admin reads go through — so a statistics page
 * can only ever show the workspace the caller is standing in.
 */
@RestController
@RequestMapping("/api/admin/token-stats")
@Tag(name = "Token Statistics", description = "Token consumption statistics APIs")
class TokenStatsController(
    private val tokenStatsService: TokenStatsService,
    private val jwtUtil: JwtUtil,
) {

    private val log = LoggerFactory.getLogger(TokenStatsController::class.java)
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private fun currentTenantId(): Long = TenantResolver.resolve(jwtUtil)

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

        val response = tokenStatsService.getAggregationStats(startTimeStr, endTimeStr, currentTenantId())
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

        val response = tokenStatsService.getTimeSeriesData(startTimeStr, endTimeStr, granularity, currentTenantId())
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
        @Parameter(description = "Time granularity: hour/day/week/month")
        @RequestParam(name = "granularity", defaultValue = "day")
        granularity: String,
    ): ResultVo<TokenStatsAggregationResponse> = try {
        val actualStartTime = startTime ?: LocalDateTime.now().minusDays(7)
        val actualEndTime = endTime ?: LocalDateTime.now()

        val startTimeStr = actualStartTime.format(dateFormatter)
        val endTimeStr = actualEndTime.format(dateFormatter)

        val response = tokenStatsService.getModelTimeSeriesData(startTimeStr, endTimeStr, granularity, currentTenantId())
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
        @Parameter(description = "Time granularity: hour/day/week/month")
        @RequestParam(name = "granularity", defaultValue = "day")
        granularity: String,
    ): ResultVo<TokenStatsAggregationResponse> = try {
        val actualStartTime = startTime ?: LocalDateTime.now().minusDays(7)
        val actualEndTime = endTime ?: LocalDateTime.now()

        val startTimeStr = actualStartTime.format(dateFormatter)
        val endTimeStr = actualEndTime.format(dateFormatter)

        val response = tokenStatsService.getAgentTimeSeriesData(startTimeStr, endTimeStr, granularity, currentTenantId())
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
        @Parameter(description = "Time granularity: hour/day/week/month")
        @RequestParam(name = "granularity", defaultValue = "day")
        granularity: String,
    ): ResultVo<TokenStatsAggregationResponse> = try {
        val actualStartTime = startTime ?: LocalDateTime.now().minusDays(7)
        val actualEndTime = endTime ?: LocalDateTime.now()

        val startTimeStr = actualStartTime.format(dateFormatter)
        val endTimeStr = actualEndTime.format(dateFormatter)

        val response = tokenStatsService.getSessionTimeSeriesData(startTimeStr, endTimeStr, granularity, currentTenantId())
        ResultVo.success(response)
    } catch (e: Exception) {
        log.error("Failed to get session time series data", e)
        ResultVo.error("Failed to get session time series data: ${e.message}")
    }
}

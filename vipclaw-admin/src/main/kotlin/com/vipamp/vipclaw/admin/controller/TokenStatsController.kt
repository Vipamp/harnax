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
 * Token 消耗统计控制器
 * 仅企业版和公有云版可用
 *
 * @author vipamp
 * @since 2026-04-11
 */
@RestController
@RequestMapping("/api/token-stats")
@Tag(name = "Token 消耗统计", description = "Token 消耗统计相关接口")
@RequiresEdition("enterprise", "public")
class TokenStatsController(
    private val tokenStatsService: TokenStatsService,
) {

    private val log = LoggerFactory.getLogger(TokenStatsController::class.java)
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    @GetMapping("/aggregation")
    @Operation(summary = "获取 Token 聚合统计", description = "按模型、会话、智能体分类汇总展示 Token 消耗")
    fun getAggregationStats(
        @Parameter(description = "开始时间")
        @RequestParam(name = "startTime", required = false)
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        startTime: LocalDateTime?,
        @Parameter(description = "结束时间")
        @RequestParam(name = "endTime", required = false)
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        endTime: LocalDateTime?,
    ): ResultVo<TokenStatsAggregationResponse> = try {
        // 默认查询最近 7 天
        val actualStartTime = startTime ?: LocalDateTime.now().minusDays(7)
        val actualEndTime = endTime ?: LocalDateTime.now()

        val startTimeStr = actualStartTime.format(dateFormatter)
        val endTimeStr = actualEndTime.format(dateFormatter)

        val response = tokenStatsService.getAggregationStats(startTimeStr, endTimeStr)
        ResultVo.success(response)
    } catch (e: Exception) {
        log.error("获取 Token 聚合统计失败", e)
        ResultVo.error("获取统计数据失败：${e.message}")
    }

    @GetMapping("/time-series")
    @Operation(summary = "获取 Token 时序数据", description = "按时间粒度查询 Token 消耗趋势")
    fun getTimeSeriesData(
        @Parameter(description = "开始时间")
        @RequestParam(name = "startTime", required = false)
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        startTime: LocalDateTime?,
        @Parameter(description = "结束时间")
        @RequestParam(name = "endTime", required = false)
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        endTime: LocalDateTime?,
        @Parameter(description = "时间粒度: hour/day/week/month")
        @RequestParam(name = "granularity", defaultValue = "day")
        granularity: String,
    ): ResultVo<TokenStatsAggregationResponse> = try {
        // 默认查询最近 7 天
        val actualStartTime = startTime ?: LocalDateTime.now().minusDays(7)
        val actualEndTime = endTime ?: LocalDateTime.now()

        val startTimeStr = actualStartTime.format(dateFormatter)
        val endTimeStr = actualEndTime.format(dateFormatter)

        val response = tokenStatsService.getTimeSeriesData(startTimeStr, endTimeStr, granularity)
        ResultVo.success(response)
    } catch (e: Exception) {
        log.error("获取 Token 时序数据失败", e)
        ResultVo.error("获取时序数据失败：${e.message}")
    }

    @GetMapping("/time-series/model")
    @Operation(summary = "获取模型时序数据", description = "按时间粒度和模型查询 Token 消耗趋势")
    fun getModelTimeSeriesData(
        @Parameter(description = "开始时间")
        @RequestParam(name = "startTime", required = false)
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        startTime: LocalDateTime?,
        @Parameter(description = "结束时间")
        @RequestParam(name = "endTime", required = false)
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        endTime: LocalDateTime?,
        @Parameter(description = "时间粒度: hour/day/month")
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
        log.error("获取模型时序数据失败", e)
        ResultVo.error("获取模型时序数据失败：${e.message}")
    }

    @GetMapping("/time-series/agent")
    @Operation(summary = "获取智能体时序数据", description = "按时间粒度和智能体查询 Token 消耗趋势")
    fun getAgentTimeSeriesData(
        @Parameter(description = "开始时间")
        @RequestParam(name = "startTime", required = false)
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        startTime: LocalDateTime?,
        @Parameter(description = "结束时间")
        @RequestParam(name = "endTime", required = false)
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        endTime: LocalDateTime?,
        @Parameter(description = "时间粒度: hour/day/month")
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
        log.error("获取智能体时序数据失败", e)
        ResultVo.error("获取智能体时序数据失败：${e.message}")
    }

    @GetMapping("/time-series/session")
    @Operation(summary = "获取会话时序数据", description = "按时间粒度和会话查询 Token 消耗趋势")
    fun getSessionTimeSeriesData(
        @Parameter(description = "开始时间")
        @RequestParam(name = "startTime", required = false)
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        startTime: LocalDateTime?,
        @Parameter(description = "结束时间")
        @RequestParam(name = "endTime", required = false)
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        endTime: LocalDateTime?,
        @Parameter(description = "时间粒度: hour/day/month")
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
        log.error("获取会话时序数据失败", e)
        ResultVo.error("获取会话时序数据失败：${e.message}")
    }
}

package com.vipamp.vipclaw.admin.controller;

import com.vipamp.vipclaw.admin.dto.TokenStatsAggregationResponse;
import com.vipamp.vipclaw.admin.service.TokenStatsService;
import com.vipamp.vipclaw.admin.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * Token 消耗统计控制器
 *
 * @author vipamp
 * @since 2026-04-11
 */
@Slf4j
@RestController
@RequestMapping("/token-stats")
@RequiredArgsConstructor
@Tag(name = "Token 消耗统计", description = "Token 消耗统计相关接口")
public class TokenStatsController {

    private final TokenStatsService tokenStatsService;

    @GetMapping("/aggregation")
    @Operation(summary = "获取 Token 聚合统计", description = "按模型、会话、智能体分类汇总展示 Token 消耗")
    public Result<TokenStatsAggregationResponse> getAggregationStats(
            @Parameter(description = "开始时间")
            @RequestParam(name = "startTime", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
            LocalDateTime startTime,
            @Parameter(description = "结束时间")
            @RequestParam(name = "endTime", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
            LocalDateTime endTime) {
        try {
            // 默认查询最近 7 天
            if (startTime == null) {
                startTime = LocalDateTime.now().minusDays(7);
            }
            if (endTime == null) {
                endTime = LocalDateTime.now();
            }

            String startTimeStr = startTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String endTimeStr = endTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

            TokenStatsAggregationResponse response = tokenStatsService.getAggregationStats(startTimeStr, endTimeStr);
            return Result.success(response);
        } catch (Exception e) {
            log.error("获取 Token 聚合统计失败", e);
            return Result.error("获取统计数据失败：" + e.getMessage());
        }
    }

    @GetMapping("/time-series")
    @Operation(summary = "获取 Token 时序数据", description = "按时间粒度查询 Token 消耗趋势")
    public Result<TokenStatsAggregationResponse> getTimeSeriesData(
            @Parameter(description = "开始时间")
            @RequestParam(name = "startTime", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
            LocalDateTime startTime,
            @Parameter(description = "结束时间")
            @RequestParam(name = "endTime", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
            LocalDateTime endTime,
            @Parameter(description = "时间粒度: hour/day/week/month")
            @RequestParam(name = "granularity", defaultValue = "day")
            String granularity) {
        try {
            // 默认查询最近 7 天
            if (startTime == null) {
                startTime = LocalDateTime.now().minusDays(7);
            }
            if (endTime == null) {
                endTime = LocalDateTime.now();
            }

            String startTimeStr = startTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String endTimeStr = endTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

            TokenStatsAggregationResponse response = tokenStatsService.getTimeSeriesData(startTimeStr, endTimeStr, granularity);
            return Result.success(response);
        } catch (Exception e) {
            log.error("获取 Token 时序数据失败", e);
            return Result.error("获取时序数据失败：" + e.getMessage());
        }
    }

    @GetMapping("/time-series/model")
    @Operation(summary = "获取模型时序数据", description = "按时间粒度和模型查询 Token 消耗趋势")
    public Result<TokenStatsAggregationResponse> getModelTimeSeriesData(
            @Parameter(description = "开始时间")
            @RequestParam(name = "startTime", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
            LocalDateTime startTime,
            @Parameter(description = "结束时间")
            @RequestParam(name = "endTime", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
            LocalDateTime endTime,
            @Parameter(description = "时间粒度: hour/day/month")
            @RequestParam(name = "granularity", defaultValue = "day")
            String granularity) {
        try {
            if (startTime == null) {
                startTime = LocalDateTime.now().minusDays(7);
            }
            if (endTime == null) {
                endTime = LocalDateTime.now();
            }

            String startTimeStr = startTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String endTimeStr = endTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

            TokenStatsAggregationResponse response = tokenStatsService.getModelTimeSeriesData(startTimeStr, endTimeStr, granularity);
            return Result.success(response);
        } catch (Exception e) {
            log.error("获取模型时序数据失败", e);
            return Result.error("获取模型时序数据失败：" + e.getMessage());
        }
    }

    @GetMapping("/time-series/agent")
    @Operation(summary = "获取智能体时序数据", description = "按时间粒度和智能体查询 Token 消耗趋势")
    public Result<TokenStatsAggregationResponse> getAgentTimeSeriesData(
            @Parameter(description = "开始时间")
            @RequestParam(name = "startTime", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
            LocalDateTime startTime,
            @Parameter(description = "结束时间")
            @RequestParam(name = "endTime", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
            LocalDateTime endTime,
            @Parameter(description = "时间粒度: hour/day/month")
            @RequestParam(name = "granularity", defaultValue = "day")
            String granularity) {
        try {
            if (startTime == null) {
                startTime = LocalDateTime.now().minusDays(7);
            }
            if (endTime == null) {
                endTime = LocalDateTime.now();
            }

            String startTimeStr = startTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String endTimeStr = endTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

            TokenStatsAggregationResponse response = tokenStatsService.getAgentTimeSeriesData(startTimeStr, endTimeStr, granularity);
            return Result.success(response);
        } catch (Exception e) {
            log.error("获取智能体时序数据失败", e);
            return Result.error("获取智能体时序数据失败：" + e.getMessage());
        }
    }

    @GetMapping("/time-series/session")
    @Operation(summary = "获取会话时序数据", description = "按时间粒度和会话查询 Token 消耗趋势")
    public Result<TokenStatsAggregationResponse> getSessionTimeSeriesData(
            @Parameter(description = "开始时间")
            @RequestParam(name = "startTime", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
            LocalDateTime startTime,
            @Parameter(description = "结束时间")
            @RequestParam(name = "endTime", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
            LocalDateTime endTime,
            @Parameter(description = "时间粒度: hour/day/month")
            @RequestParam(name = "granularity", defaultValue = "day")
            String granularity) {
        try {
            if (startTime == null) {
                startTime = LocalDateTime.now().minusDays(7);
            }
            if (endTime == null) {
                endTime = LocalDateTime.now();
            }

            String startTimeStr = startTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String endTimeStr = endTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

            TokenStatsAggregationResponse response = tokenStatsService.getSessionTimeSeriesData(startTimeStr, endTimeStr, granularity);
            return Result.success(response);
        } catch (Exception e) {
            log.error("获取会话时序数据失败", e);
            return Result.error("获取会话时序数据失败：" + e.getMessage());
        }
    }
}

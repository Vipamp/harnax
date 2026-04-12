package com.vipamp.vipclaw.admin.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.vipamp.vipclaw.admin.dto.TokenStatsAggregationResponse;
import com.vipamp.vipclaw.admin.entity.TokenStats;
import com.vipamp.vipclaw.admin.mapper.TokenStatsMapper;
import com.vipamp.vipclaw.admin.service.TokenStatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Token 消耗统计服务实现类
 *
 * @author vipamp
 * @since 2026-04-11
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenStatsServiceImpl extends ServiceImpl<TokenStatsMapper, TokenStats> implements TokenStatsService {

    private final TokenStatsMapper tokenStatsMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveTokenStats(TokenStats tokenStats) {
        log.info("保存 Token 消耗记录, agentId: {}, sessionId: {}, chatModelId: {}, totalToken: {}",
                tokenStats.getAgentId(),
                tokenStats.getSessionId(),
                tokenStats.getChatModelId(),
                tokenStats.getTotalToken());
        
        boolean success = this.save(tokenStats);
        log.info("Token 消耗记录保存{}", success ? "成功" : "失败");
        return success;
    }

    @Override
    public TokenStatsAggregationResponse getAggregationStats(String startTime, String endTime) {
        log.info("获取 Token 聚合统计数据, startTime: {}, endTime: {}", startTime, endTime);

        TokenStatsAggregationResponse response = new TokenStatsAggregationResponse();

        // 获取总体统计
        Map<String, Object> overallMap = tokenStatsMapper.getOverallStats(startTime, endTime);
        if (overallMap != null) {
            response.setOverall(TokenStatsAggregationResponse.mapToOverallStats(overallMap));
        } else {
            response.setOverall(new TokenStatsAggregationResponse.OverallStats());
        }

        // 按模型聚合
        List<Map<String, Object>> modelData = tokenStatsMapper.aggregateByModel(startTime, endTime);
        response.setModelStats(modelData.stream()
                .map(TokenStatsAggregationResponse::mapToModelStats)
                .collect(Collectors.toList()));

        // 按会话聚合
        List<Map<String, Object>> sessionData = tokenStatsMapper.aggregateBySession(startTime, endTime);
        response.setSessionStats(sessionData.stream()
                .map(TokenStatsAggregationResponse::mapToSessionStats)
                .collect(Collectors.toList()));

        // 按智能体聚合
        List<Map<String, Object>> agentData = tokenStatsMapper.aggregateByAgent(startTime, endTime);
        response.setAgentStats(agentData.stream()
                .map(TokenStatsAggregationResponse::mapToAgentStats)
                .collect(Collectors.toList()));

        log.info("Token 聚合统计数据获取完成");
        return response;
    }

    @Override
    public TokenStatsAggregationResponse getTimeSeriesData(String startTime, String endTime, String granularity) {
        log.info("获取 Token 时序统计数据, startTime: {}, endTime: {}, granularity: {}", startTime, endTime, granularity);

        TokenStatsAggregationResponse response = new TokenStatsAggregationResponse();

        // 不支持按周统计
        if ("week".equals(granularity)) {
            granularity = "day";
        }

        // 根据时间粒度查询时序数据
        List<Map<String, Object>> timeSeriesData;
        switch (granularity) {
            case "hour":
                timeSeriesData = tokenStatsMapper.getTimeSeriesByHour(startTime, endTime);
                break;
            case "month":
                timeSeriesData = tokenStatsMapper.getTimeSeriesByMonth(startTime, endTime);
                break;
            case "day":
            default:
                timeSeriesData = tokenStatsMapper.getTimeSeriesByDay(startTime, endTime);
                break;
        }

        // 补全所有时间点（即使没有数据也要显示为0）
        List<Map<String, Object>> filledTimeSeriesData = fillTimePoints(timeSeriesData, startTime, endTime, granularity);

        response.setTimeSeriesData(filledTimeSeriesData.stream()
                .map(TokenStatsAggregationResponse::mapToTimeSeriesData)
                .collect(Collectors.toList()));

        log.info("Token 时序统计数据获取完成，共 {} 条记录", response.getTimeSeriesData().size());
        return response;
    }

    @Override
    public TokenStatsAggregationResponse getModelTimeSeriesData(String startTime, String endTime, String granularity) {
        log.info("获取模型时序统计数据, startTime: {}, endTime: {}, granularity: {}", startTime, endTime, granularity);
        return getDimensionTimeSeriesData(startTime, endTime, granularity, "model");
    }

    @Override
    public TokenStatsAggregationResponse getAgentTimeSeriesData(String startTime, String endTime, String granularity) {
        log.info("获取智能体时序统计数据, startTime: {}, endTime: {}, granularity: {}", startTime, endTime, granularity);
        return getDimensionTimeSeriesData(startTime, endTime, granularity, "agent");
    }

    @Override
    public TokenStatsAggregationResponse getSessionTimeSeriesData(String startTime, String endTime, String granularity) {
        log.info("获取会话时序统计数据, startTime: {}, endTime: {}, granularity: {}", startTime, endTime, granularity);
        return getDimensionTimeSeriesData(startTime, endTime, granularity, "session");
    }

    /**
     * 通用维度时序数据查询
     */
    private TokenStatsAggregationResponse getDimensionTimeSeriesData(
            String startTime, String endTime, String granularity, String dimensionType) {
        
        TokenStatsAggregationResponse response = new TokenStatsAggregationResponse();

        // 不支持按周统计
        if ("week".equals(granularity)) {
            granularity = "day";
        }

        // 根据维度类型和时间粒度查询时序数据
        List<Map<String, Object>> timeSeriesData;
        switch (dimensionType) {
            case "model":
                switch (granularity) {
                    case "hour":
                        timeSeriesData = tokenStatsMapper.getModelTimeSeriesByHour(startTime, endTime);
                        break;
                    case "month":
                        timeSeriesData = tokenStatsMapper.getModelTimeSeriesByMonth(startTime, endTime);
                        break;
                    case "day":
                    default:
                        timeSeriesData = tokenStatsMapper.getModelTimeSeriesByDay(startTime, endTime);
                        break;
                }
                break;
            case "agent":
                switch (granularity) {
                    case "hour":
                        timeSeriesData = tokenStatsMapper.getAgentTimeSeriesByHour(startTime, endTime);
                        break;
                    case "month":
                        timeSeriesData = tokenStatsMapper.getAgentTimeSeriesByMonth(startTime, endTime);
                        break;
                    case "day":
                    default:
                        timeSeriesData = tokenStatsMapper.getAgentTimeSeriesByDay(startTime, endTime);
                        break;
                }
                break;
            case "session":
                switch (granularity) {
                    case "hour":
                        timeSeriesData = tokenStatsMapper.getSessionTimeSeriesByHour(startTime, endTime);
                        break;
                    case "month":
                        timeSeriesData = tokenStatsMapper.getSessionTimeSeriesByMonth(startTime, endTime);
                        break;
                    case "day":
                    default:
                        timeSeriesData = tokenStatsMapper.getSessionTimeSeriesByDay(startTime, endTime);
                        break;
                }
                break;
            default:
                timeSeriesData = new ArrayList<>();
                break;
        }

        // 补全所有时间点(按维度分组补全)
        List<Map<String, Object>> filledTimeSeriesData = fillDimensionTimePoints(
                timeSeriesData, startTime, endTime, granularity, dimensionType);

        response.setTimeSeriesData(filledTimeSeriesData.stream()
                .map(data -> TokenStatsAggregationResponse.mapToDimensionTimeSeriesData(data, dimensionType))
                .collect(Collectors.toList()));

        log.info("{} 时序统计数据获取完成，共 {} 条记录", dimensionType, response.getTimeSeriesData().size());
        return response;
    }

    /**
     * 补全时间点，确保从开始到结束的所有时间点都有数据（没有数据的点为0）
     */
    private List<Map<String, Object>> fillTimePoints(
            List<Map<String, Object>> queryData,
            String startTimeStr,
            String endTimeStr,
            String granularity) {

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        LocalDateTime startTime = LocalDateTime.parse(startTimeStr, formatter);
        LocalDateTime endTime = LocalDateTime.parse(endTimeStr, formatter);

        // 将查询结果转为 Map，方便查找
        Map<String, Map<String, Object>> dataMap = new HashMap<>();
        for (Map<String, Object> data : queryData) {
            Object timePointObj = data.get("timePoint");
            if (timePointObj != null) {
                String timeKey;
                if (timePointObj instanceof LocalDateTime) {
                    timeKey = ((LocalDateTime) timePointObj).format(formatter);
                } else {
                    timeKey = timePointObj.toString();
                }
                dataMap.put(timeKey, data);
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        LocalDateTime currentTime = startTime;
        
        // 根据粒度规范化起始时间
        switch (granularity) {
            case "hour":
                currentTime = currentTime.withMinute(0).withSecond(0);
                break;
            case "month":
                currentTime = currentTime.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0);
                break;
            case "day":
            default:
                currentTime = currentTime.withHour(0).withMinute(0).withSecond(0);
                break;
        }

        while (!currentTime.isAfter(endTime)) {
            String timeKey;
            String displayTime;

            switch (granularity) {
                case "hour":
                    // 按小时：每个小时的整点
                    timeKey = currentTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00:00"));
                    displayTime = currentTime.format(formatter);
                    currentTime = currentTime.plusHours(1);
                    break;
                case "month":
                    // 按月：每个月的第一天
                    timeKey = currentTime.format(DateTimeFormatter.ofPattern("yyyy-MM"));
                    displayTime = currentTime.format(formatter);
                    currentTime = currentTime.plusMonths(1).withDayOfMonth(1);
                    break;
                case "day":
                default:
                    // 按天：每天的 00:00:00
                    timeKey = currentTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd 00:00:00"));
                    displayTime = currentTime.format(formatter);
                    currentTime = currentTime.plusDays(1);
                    break;
            }

            // 查找该时间点的数据，如果没有则创建为0
            Map<String, Object> data = dataMap.get(timeKey);
            if (data == null) {
                // 创建空数据
                Map<String, Object> emptyData = new HashMap<>();
                emptyData.put("timePoint", displayTime);
                emptyData.put("totalInputToken", 0L);
                emptyData.put("totalOutputToken", 0L);
                emptyData.put("grandTotalToken", 0L);
                emptyData.put("totalFee", java.math.BigDecimal.ZERO);
                result.add(emptyData);
            } else {
                // 使用查询到的数据，但更新 timePoint 为显示时间
                data.put("timePoint", displayTime);
                result.add(data);
            }
        }

        return result;
    }

    /**
     * 补全维度时间点，确保每个维度从开始到结束的所有时间点都有数据（没有数据的点为0）
     */
    private List<Map<String, Object>> fillDimensionTimePoints(
            List<Map<String, Object>> queryData,
            String startTimeStr,
            String endTimeStr,
            String granularity,
            String dimensionType) {

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        LocalDateTime startTime = LocalDateTime.parse(startTimeStr, formatter);
        LocalDateTime endTime = LocalDateTime.parse(endTimeStr, formatter);

        // 按维度ID分组
        Map<String, List<Map<String, Object>>> dimensionGroups = new HashMap<>();
        String dimensionIdField = dimensionType.equals("session") ? "sessionId" : 
                                  dimensionType.equals("agent") ? "agentId" : "modelId";
        
        for (Map<String, Object> data : queryData) {
            Object dimIdObj = data.get(dimensionIdField);
            String dimId = dimIdObj != null ? dimIdObj.toString() : "unknown";
            dimensionGroups.computeIfAbsent(dimId, k -> new ArrayList<>()).add(data);
        }

        List<Map<String, Object>> result = new ArrayList<>();

        // 对每个维度补全时间点
        for (Map.Entry<String, List<Map<String, Object>>> entry : dimensionGroups.entrySet()) {
            String dimensionId = entry.getKey();
            List<Map<String, Object>> dimData = entry.getValue();

            // 将该维度的数据转为 Map
            Map<String, Map<String, Object>> dataMap = new HashMap<>();
            for (Map<String, Object> data : dimData) {
                Object timePointObj = data.get("timePoint");
                if (timePointObj != null) {
                    String timeKey;
                    if (timePointObj instanceof LocalDateTime) {
                        timeKey = ((LocalDateTime) timePointObj).format(formatter);
                    } else {
                        timeKey = timePointObj.toString();
                    }
                    dataMap.put(timeKey, data);
                }
            }

            // 补全该维度的时间点
            LocalDateTime currentTime = startTime;
            
            // 根据粒度规范化起始时间
            switch (granularity) {
                case "hour":
                    currentTime = currentTime.withMinute(0).withSecond(0);
                    break;
                case "month":
                    currentTime = currentTime.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0);
                    break;
                case "day":
                default:
                    currentTime = currentTime.withHour(0).withMinute(0).withSecond(0);
                    break;
            }
            
            while (!currentTime.isAfter(endTime)) {
                String timeKey;
                String displayTime;

                switch (granularity) {
                    case "hour":
                        timeKey = currentTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00:00"));
                        displayTime = currentTime.format(formatter);
                        currentTime = currentTime.plusHours(1);
                        break;
                    case "month":
                        timeKey = currentTime.format(DateTimeFormatter.ofPattern("yyyy-MM"));
                        displayTime = currentTime.format(formatter);
                        currentTime = currentTime.plusMonths(1).withDayOfMonth(1);
                        break;
                    case "day":
                    default:
                        timeKey = currentTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd 00:00:00"));
                        displayTime = currentTime.format(formatter);
                        currentTime = currentTime.plusDays(1);
                        break;
                }

                // 查找该时间点的数据，如果没有则创建为0
                Map<String, Object> data = dataMap.get(timeKey);
                if (data == null) {
                    // 创建空数据，保留维度信息
                    Map<String, Object> emptyData = new HashMap<>();
                    emptyData.put("timePoint", displayTime);
                    emptyData.put("totalInputToken", 0L);
                    emptyData.put("totalOutputToken", 0L);
                    emptyData.put("grandTotalToken", 0L);
                    emptyData.put("totalFee", java.math.BigDecimal.ZERO);
                    
                    // 保留维度字段
                    if ("model".equals(dimensionType)) {
                        Map<String, Object> sampleData = dimData.isEmpty() ? new HashMap<>() : dimData.get(0);
                        emptyData.put("modelId", sampleData.get("modelId"));
                        emptyData.put("modelName", sampleData.get("modelName"));
                    } else if ("agent".equals(dimensionType)) {
                        Map<String, Object> sampleData = dimData.isEmpty() ? new HashMap<>() : dimData.get(0);
                        emptyData.put("agentId", sampleData.get("agentId"));
                        emptyData.put("agentName", sampleData.get("agentName"));
                    } else if ("session".equals(dimensionType)) {
                        Map<String, Object> sampleData = dimData.isEmpty() ? new HashMap<>() : dimData.get(0);
                        emptyData.put("sessionId", sampleData.get("sessionId"));
                        emptyData.put("sessionTitle", sampleData.get("sessionTitle"));
                    }
                    
                    result.add(emptyData);
                } else {
                    // 使用查询到的数据，但更新 timePoint 为显示时间
                    data.put("timePoint", displayTime);
                    result.add(data);
                }
            }
        }

        return result;
    }
}

package com.vipamp.vipclaw.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Token 统计聚合查询响应 DTO
 *
 * @author vipamp
 * @since 2026-04-11
 */
@Data
@Schema(description = "Token 统计聚合查询响应")
public class TokenStatsAggregationResponse implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 总体统计信息
     */
    @Schema(description = "总体统计信息")
    private OverallStats overall;

    /**
     * 按模型聚合数据
     */
    @Schema(description = "按模型聚合数据")
    private List<ModelStats> modelStats;

    /**
     * 按会话聚合数据
     */
    @Schema(description = "按会话聚合数据")
    private List<SessionStats> sessionStats;

    /**
     * 按智能体聚合数据
     */
    @Schema(description = "按智能体聚合数据")
    private List<AgentStats> agentStats;

    /**
     * 时序数据（按时间单位聚合）
     */
    @Schema(description = "时序数据")
    private List<TimeSeriesData> timeSeriesData;

    /**
     * 总体统计
     */
    @Data
    @Schema(description = "总体统计")
    public static class OverallStats implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "总输入 Token")
        private Long totalInputToken;

        @Schema(description = "总输出 Token")
        private Long totalOutputToken;

        @Schema(description = "总 Token")
        private Long grandTotalToken;

        @Schema(description = "总费用（单位：元）")
        private BigDecimal totalFee;

        @Schema(description = "智能体数量")
        private Long agentCount;

        @Schema(description = "会话数量")
        private Long sessionCount;

        @Schema(description = "模型数量")
        private Long modelCount;
    }

    /**
     * 模型统计
     */
    @Data
    @Schema(description = "模型统计")
    public static class ModelStats implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "模型 ID")
        private Long modelId;

        @Schema(description = "模型名称")
        private String modelName;

        @Schema(description = "供应商名称")
        private String providerName;

        @Schema(description = "总输入 Token")
        private Long totalInputToken;

        @Schema(description = "总输出 Token")
        private Long totalOutputToken;

        @Schema(description = "总 Token")
        private Long grandTotalToken;

        @Schema(description = "总费用（单位：元）")
        private BigDecimal totalFee;
    }

    /**
     * 会话统计
     */
    @Data
    @Schema(description = "会话统计")
    public static class SessionStats implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "会话 ID")
        private String sessionId;

        @Schema(description = "会话标题")
        private String sessionTitle;

        @Schema(description = "总输入 Token")
        private Long totalInputToken;

        @Schema(description = "总输出 Token")
        private Long totalOutputToken;

        @Schema(description = "总 Token")
        private Long grandTotalToken;

        @Schema(description = "总费用（单位：元）")
        private BigDecimal totalFee;
    }

    /**
     * 智能体统计
     */
    @Data
    @Schema(description = "智能体统计")
    public static class AgentStats implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "智能体 ID")
        private Long agentId;

        @Schema(description = "智能体名称")
        private String agentName;

        @Schema(description = "总输入 Token")
        private Long totalInputToken;

        @Schema(description = "总输出 Token")
        private Long totalOutputToken;

        @Schema(description = "总 Token")
        private Long grandTotalToken;

        @Schema(description = "总费用（单位：元）")
        private BigDecimal totalFee;
    }

    /**
     * 从 Map 转换为 OverallStats
     */
    public static OverallStats mapToOverallStats(Map<String, Object> map) {
        OverallStats stats = new OverallStats();
        stats.setTotalInputToken(map.get("totalInputToken") != null ? ((Number) map.get("totalInputToken")).longValue() : 0L);
        stats.setTotalOutputToken(map.get("totalOutputToken") != null ? ((Number) map.get("totalOutputToken")).longValue() : 0L);
        stats.setGrandTotalToken(map.get("grandTotalToken") != null ? ((Number) map.get("grandTotalToken")).longValue() : 0L);
        stats.setTotalFee(map.get("totalFee") != null ? new BigDecimal(map.get("totalFee").toString()) : BigDecimal.ZERO);
        stats.setAgentCount(map.get("agentCount") != null ? ((Number) map.get("agentCount")).longValue() : 0L);
        stats.setSessionCount(map.get("sessionCount") != null ? ((Number) map.get("sessionCount")).longValue() : 0L);
        stats.setModelCount(map.get("modelCount") != null ? ((Number) map.get("modelCount")).longValue() : 0L);
        return stats;
    }

    /**
     * 从 Map 转换为 ModelStats
     */
    public static ModelStats mapToModelStats(Map<String, Object> map) {
        ModelStats stats = new ModelStats();
        stats.setModelId(map.get("modelId") != null ? ((Number) map.get("modelId")).longValue() : null);
        stats.setModelName((String) map.get("modelName"));
        stats.setProviderName((String) map.get("providerName"));
        stats.setTotalInputToken(map.get("totalInputToken") != null ? ((Number) map.get("totalInputToken")).longValue() : 0L);
        stats.setTotalOutputToken(map.get("totalOutputToken") != null ? ((Number) map.get("totalOutputToken")).longValue() : 0L);
        stats.setGrandTotalToken(map.get("grandTotalToken") != null ? ((Number) map.get("grandTotalToken")).longValue() : 0L);
        stats.setTotalFee(map.get("totalFee") != null ? new BigDecimal(map.get("totalFee").toString()) : BigDecimal.ZERO);
        return stats;
    }

    /**
     * 从 Map 转换为 SessionStats
     */
    public static SessionStats mapToSessionStats(Map<String, Object> map) {
        SessionStats stats = new SessionStats();
        stats.setSessionId((String) map.get("sessionId"));
        stats.setSessionTitle((String) map.get("sessionTitle"));
        stats.setTotalInputToken(map.get("totalInputToken") != null ? ((Number) map.get("totalInputToken")).longValue() : 0L);
        stats.setTotalOutputToken(map.get("totalOutputToken") != null ? ((Number) map.get("totalOutputToken")).longValue() : 0L);
        stats.setGrandTotalToken(map.get("grandTotalToken") != null ? ((Number) map.get("grandTotalToken")).longValue() : 0L);
        stats.setTotalFee(map.get("totalFee") != null ? new BigDecimal(map.get("totalFee").toString()) : BigDecimal.ZERO);
        return stats;
    }

    /**
     * 从 Map 转换为 AgentStats
     */
    public static AgentStats mapToAgentStats(Map<String, Object> map) {
        AgentStats stats = new AgentStats();
        stats.setAgentId(map.get("agentId") != null ? ((Number) map.get("agentId")).longValue() : null);
        stats.setAgentName((String) map.get("agentName"));
        stats.setTotalInputToken(map.get("totalInputToken") != null ? ((Number) map.get("totalInputToken")).longValue() : 0L);
        stats.setTotalOutputToken(map.get("totalOutputToken") != null ? ((Number) map.get("totalOutputToken")).longValue() : 0L);
        stats.setGrandTotalToken(map.get("grandTotalToken") != null ? ((Number) map.get("grandTotalToken")).longValue() : 0L);
        return stats;
    }

    /**
     * 时序数据
     */
    @Data
    @Schema(description = "时序数据")
    public static class TimeSeriesData implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "时间点")
        private String timePoint;

        @Schema(description = "维度标识(modelId/agentId/sessionId)")
        private String dimensionId;

        @Schema(description = "维度名称(modelName/agentName/sessionTitle)")
        private String dimensionName;

        @Schema(description = "输入 Token")
        private Long totalInputToken;

        @Schema(description = "输出 Token")
        private Long totalOutputToken;

        @Schema(description = "总 Token")
        private Long grandTotalToken;

        @Schema(description = "总费用（单位：元）")
        private BigDecimal totalFee;
    }

    /**
     * 从 Map 转换为 TimeSeriesData(无维度)
     */
    public static TimeSeriesData mapToTimeSeriesData(Map<String, Object> map) {
        TimeSeriesData data = new TimeSeriesData();
        Object timePointObj = map.get("timePoint");
        if (timePointObj != null) {
            if (timePointObj instanceof java.time.LocalDateTime) {
                data.setTimePoint(((java.time.LocalDateTime) timePointObj)
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            } else {
                data.setTimePoint(timePointObj.toString());
            }
        }
        data.setTotalInputToken(map.get("totalInputToken") != null ? ((Number) map.get("totalInputToken")).longValue() : 0L);
        data.setTotalOutputToken(map.get("totalOutputToken") != null ? ((Number) map.get("totalOutputToken")).longValue() : 0L);
        data.setGrandTotalToken(map.get("grandTotalToken") != null ? ((Number) map.get("grandTotalToken")).longValue() : 0L);
        data.setTotalFee(map.get("totalFee") != null ? new BigDecimal(map.get("totalFee").toString()) : BigDecimal.ZERO);
        return data;
    }

    /**
     * 从 Map 转换为 TimeSeriesData(带维度)
     */
    public static TimeSeriesData mapToDimensionTimeSeriesData(Map<String, Object> map, String dimensionType) {
        TimeSeriesData data = new TimeSeriesData();
        Object timePointObj = map.get("timePoint");
        if (timePointObj != null) {
            if (timePointObj instanceof java.time.LocalDateTime) {
                data.setTimePoint(((java.time.LocalDateTime) timePointObj)
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            } else {
                data.setTimePoint(timePointObj.toString());
            }
        }
        
        // 根据维度类型设置维度信息
        if ("model".equals(dimensionType)) {
            Object modelIdObj = map.get("modelId");
            data.setDimensionId(modelIdObj != null ? modelIdObj.toString() : null);
            data.setDimensionName((String) map.get("modelName"));
        } else if ("agent".equals(dimensionType)) {
            Object agentIdObj = map.get("agentId");
            data.setDimensionId(agentIdObj != null ? agentIdObj.toString() : null);
            data.setDimensionName((String) map.get("agentName"));
        } else if ("session".equals(dimensionType)) {
            data.setDimensionId((String) map.get("sessionId"));
            data.setDimensionName((String) map.get("sessionTitle"));
        }
        
        data.setTotalInputToken(map.get("totalInputToken") != null ? ((Number) map.get("totalInputToken")).longValue() : 0L);
        data.setTotalOutputToken(map.get("totalOutputToken") != null ? ((Number) map.get("totalOutputToken")).longValue() : 0L);
        data.setGrandTotalToken(map.get("grandTotalToken") != null ? ((Number) map.get("grandTotalToken")).longValue() : 0L);
        data.setTotalFee(map.get("totalFee") != null ? new BigDecimal(map.get("totalFee").toString()) : BigDecimal.ZERO);
        return data;
    }
}

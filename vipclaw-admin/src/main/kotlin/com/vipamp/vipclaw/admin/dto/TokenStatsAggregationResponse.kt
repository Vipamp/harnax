package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.*
import java.math.BigDecimal
import java.util.List

/**
 * Token 统计聚合查询响应
 */
@Schema(description = "Token 统计聚合查询响应")
data class TokenStatsAggregationResponse(
    @Schema(description = "总体统计信息")
    val overall: OverallStats? = null,
    @Schema(description = "按模型聚合数据")
    val modelStats: List<ModelStats>? = null,
    @Schema(description = "按会话聚合数据")
    val sessionStats: List<SessionStats>? = null,
    @Schema(description = "按智能体聚合数据")
    val agentStats: List<AgentStats>? = null,
    @Schema(description = "时序数据")
    val timeSeriesData: List<TimeSeriesData>? = null,
    @Schema(description = "总输入 Token")
    val totalInputToken: Long? = null,
    @Schema(description = "总输出 Token")
    val totalOutputToken: Long? = null,
    @Schema(description = "总 Token")
    val grandTotalToken: Long? = null,
    @Schema(description = "总费用（单位：元）")
    val totalFee: BigDecimal? = null,
    @Schema(description = "智能体数量")
    val agentCount: Long? = null,
    @Schema(description = "会话数量")
    val sessionCount: Long? = null,
    @Schema(description = "模型数量")
    val modelCount: Long? = null,
    @Schema(description = "模型 ID")
    val modelId: Long? = null,
    @Schema(description = "模型名称")
    val modelName: String? = null,
    @Schema(description = "供应商名称")
    val providerName: String? = null,
    @Schema(description = "总输入 Token")
    val totalInputToken: Long? = null,
    @Schema(description = "总输出 Token")
    val totalOutputToken: Long? = null,
    @Schema(description = "总 Token")
    val grandTotalToken: Long? = null,
    @Schema(description = "总费用（单位：元）")
    val totalFee: BigDecimal? = null,
    @Schema(description = "会话 ID")
    val sessionId: String? = null,
    @Schema(description = "会话标题")
    val sessionTitle: String? = null,
    @Schema(description = "总输入 Token")
    val totalInputToken: Long? = null,
    @Schema(description = "总输出 Token")
    val totalOutputToken: Long? = null,
    @Schema(description = "总 Token")
    val grandTotalToken: Long? = null,
    @Schema(description = "总费用（单位：元）")
    val totalFee: BigDecimal? = null,
    @Schema(description = "智能体 ID")
    val agentId: Long? = null,
    @Schema(description = "智能体名称")
    val agentName: String? = null,
    @Schema(description = "总输入 Token")
    val totalInputToken: Long? = null,
    @Schema(description = "总输出 Token")
    val totalOutputToken: Long? = null,
    @Schema(description = "总 Token")
    val grandTotalToken: Long? = null,
    @Schema(description = "总费用（单位：元）")
    val totalFee: BigDecimal? = null,
    @Schema(description = "时间点")
    val timePoint: String? = null,
    @Schema(description = "维度标识(modelId/agentId/sessionId)")
    val dimensionId: String? = null,
    @Schema(description = "维度名称(modelName/agentName/sessionTitle)")
    val dimensionName: String? = null,
    @Schema(description = "输入 Token")
    val totalInputToken: Long? = null,
    @Schema(description = "输出 Token")
    val totalOutputToken: Long? = null,
    @Schema(description = "总 Token")
    val grandTotalToken: Long? = null,
    @Schema(description = "总费用（单位：元）")
    val totalFee: BigDecimal? = null
)

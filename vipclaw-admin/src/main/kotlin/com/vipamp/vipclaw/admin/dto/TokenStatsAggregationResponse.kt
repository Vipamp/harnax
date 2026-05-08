package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serial
import java.io.Serializable
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Token 统计聚合查询响应
 */
@Schema(description = "Token 统计聚合查询响应")
data class TokenStatsAggregationResponse(
    @Schema(description = "总体统计信息")
    var overall: OverallStats? = null,
    @Schema(description = "按模型聚合数据")
    var modelStats: List<ModelStats>? = null,
    @Schema(description = "按会话聚合数据")
    var sessionStats: List<SessionStats>? = null,
    @Schema(description = "按智能体聚合数据")
    var agentStats: List<AgentStats>? = null,
    @Schema(description = "时序数据")
    var timeSeriesData: List<TimeSeriesData>? = null,
) {
    companion object {
        /**
         * 从 Map 转换为 OverallStats
         */
        fun mapToOverallStats(map: Map<String, Any?>): OverallStats = OverallStats(
            totalInputToken = (map["totalInputToken"] as? Number)?.toLong() ?: 0L,
            totalOutputToken = (map["totalOutputToken"] as? Number)?.toLong() ?: 0L,
            grandTotalToken = (map["grandTotalToken"] as? Number)?.toLong() ?: 0L,
            totalFee = BigDecimal.valueOf((map["totalFee"] as? Number)?.toDouble() ?: 0.0),
            agentCount = (map["agentCount"] as? Number)?.toLong() ?: 0L,
            sessionCount = (map["sessionCount"] as? Number)?.toLong() ?: 0L,
            modelCount = (map["modelCount"] as? Number)?.toLong() ?: 0L,
        )

        /**
         * 从 Map 转换为 ModelStats
         */
        fun mapToModelStats(map: Map<String, Any?>): ModelStats = ModelStats(
            modelId = (map["modelId"] as? Number)?.toLong(),
            modelName = map["modelName"] as? String,
            providerName = map["providerName"] as? String,
            totalInputToken = (map["totalInputToken"] as? Number)?.toLong() ?: 0L,
            totalOutputToken = (map["totalOutputToken"] as? Number)?.toLong() ?: 0L,
            grandTotalToken = (map["grandTotalToken"] as? Number)?.toLong() ?: 0L,
            totalFee = BigDecimal.valueOf((map["totalFee"] as? Number)?.toDouble() ?: 0.0),
        )

        /**
         * 从 Map 转换为 SessionStats
         */
        fun mapToSessionStats(map: Map<String, Any?>): SessionStats = SessionStats(
            sessionId = map["sessionId"] as? String,
            sessionTitle = map["sessionTitle"] as? String,
            totalInputToken = (map["totalInputToken"] as? Number)?.toLong() ?: 0L,
            totalOutputToken = (map["totalOutputToken"] as? Number)?.toLong() ?: 0L,
            grandTotalToken = (map["grandTotalToken"] as? Number)?.toLong() ?: 0L,
            totalFee = BigDecimal.valueOf((map["totalFee"] as? Number)?.toDouble() ?: 0.0),
        )

        /**
         * 从 Map 转换为 AgentStats
         */
        fun mapToAgentStats(map: Map<String, Any?>): AgentStats = AgentStats(
            agentId = (map["agentId"] as? Number)?.toLong(),
            agentName = map["agentName"] as? String,
            totalInputToken = (map["totalInputToken"] as? Number)?.toLong() ?: 0L,
            totalOutputToken = (map["totalOutputToken"] as? Number)?.toLong() ?: 0L,
            grandTotalToken = (map["grandTotalToken"] as? Number)?.toLong() ?: 0L,
            totalFee = BigDecimal.valueOf((map["totalFee"] as? Number)?.toDouble() ?: 0.0),
        )

        /**
         * 从 Map 转换为 TimeSeriesData
         */
        fun mapToTimeSeriesData(map: Map<String, Any?>): TimeSeriesData = TimeSeriesData(
            timePoint = map["timePoint"] as? String,
            dimensionId = map["dimensionId"] as? String,
            dimensionName = map["dimensionName"] as? String,
            totalInputToken = (map["totalInputToken"] as? Number)?.toLong() ?: 0L,
            totalOutputToken = (map["totalOutputToken"] as? Number)?.toLong() ?: 0L,
            grandTotalToken = (map["grandTotalToken"] as? Number)?.toLong() ?: 0L,
            totalFee = BigDecimal.valueOf((map["totalFee"] as? Number)?.toDouble() ?: 0.0),
        )

        /**
         * 从 Map 转换为 TimeSeriesData(带维度)
         */
        fun mapToDimensionTimeSeriesData(map: MutableMap<String?, Any?>, dimensionType: String?): TimeSeriesData {
            val data = TimeSeriesData()
            val timePointObj = map.get("timePoint")
            if (timePointObj != null) {
                if (timePointObj is LocalDateTime) {
                    data.timePoint =
                        timePointObj
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                } else {
                    data.timePoint = timePointObj.toString()
                }
            }

            // 根据维度类型设置维度信息
            if ("model" == dimensionType) {
                val modelIdObj = map.get("modelId")
                data.dimensionId = modelIdObj?.toString()
                data.dimensionName = map.get("modelName") as String?
            } else if ("agent" == dimensionType) {
                val agentIdObj = map.get("agentId")
                data.dimensionId = if (agentIdObj != null) agentIdObj.toString() else null
                data.dimensionName = map.get("agentName") as String?
            } else if ("session" == dimensionType) {
                data.dimensionId = map.get("sessionId") as String?
                data.dimensionName = map.get("sessionTitle") as String?
            }

            data.totalInputToken = if (map.get("totalInputToken") != null) (map.get("totalInputToken") as Number).toLong() else 0L
            data.totalOutputToken = if (map.get("totalOutputToken") != null) (map.get("totalOutputToken") as Number).toLong() else 0L
            data.grandTotalToken = if (map.get("grandTotalToken") != null) (map.get("grandTotalToken") as Number).toLong() else 0L
            data.totalFee =
                if (map.get("totalFee") != null) {
                    BigDecimal(
                        map.get("totalFee").toString(),
                    )
                } else {
                    BigDecimal.ZERO
                }
            return data
        }
    }
}

/**
 * 总体统计信息
 */
@Schema(description = "总体统计信息")
data class OverallStats(
    @Schema(description = "总输入 Token")
    val totalInputToken: Long = 0L,
    @Schema(description = "总输出 Token")
    val totalOutputToken: Long = 0L,
    @Schema(description = "总 Token")
    val grandTotalToken: Long = 0L,
    @Schema(description = "总费用（单位：元）")
    val totalFee: BigDecimal = BigDecimal.ZERO,
    @Schema(description = "智能体数量")
    val agentCount: Long = 0L,
    @Schema(description = "会话数量")
    val sessionCount: Long = 0L,
    @Schema(description = "模型数量")
    val modelCount: Long = 0L,
) : Serializable {
    companion object {
        @Serial
        private const val serialVersionUID = 1L
    }
}

/**
 * 模型统计
 */
@Schema(description = "模型统计")
data class ModelStats(
    @Schema(description = "模型 ID")
    val modelId: Long? = null,
    @Schema(description = "模型名称")
    val modelName: String? = null,
    @Schema(description = "供应商名称")
    val providerName: String? = null,
    @Schema(description = "总输入 Token")
    val totalInputToken: Long = 0L,
    @Schema(description = "总输出 Token")
    val totalOutputToken: Long = 0L,
    @Schema(description = "总 Token")
    val grandTotalToken: Long = 0L,
    @Schema(description = "总费用（单位：元）")
    val totalFee: BigDecimal = BigDecimal.ZERO,
) : Serializable {
    companion object {
        @Serial
        private const val serialVersionUID = 1L
    }
}

/**
 * 会话统计
 */
@Schema(description = "会话统计")
data class SessionStats(
    @Schema(description = "会话 ID")
    val sessionId: String? = null,
    @Schema(description = "会话标题")
    val sessionTitle: String? = null,
    @Schema(description = "总输入 Token")
    val totalInputToken: Long = 0L,
    @Schema(description = "总输出 Token")
    val totalOutputToken: Long = 0L,
    @Schema(description = "总 Token")
    val grandTotalToken: Long = 0L,
    @Schema(description = "总费用（单位：元）")
    val totalFee: BigDecimal = BigDecimal.ZERO,
) : Serializable {
    companion object {
        @Serial
        private const val serialVersionUID = 1L
    }
}

/**
 * 智能体统计
 */
@Schema(description = "智能体统计")
data class AgentStats(
    @Schema(description = "智能体 ID")
    val agentId: Long? = null,
    @Schema(description = "智能体名称")
    val agentName: String? = null,
    @Schema(description = "总输入 Token")
    val totalInputToken: Long = 0L,
    @Schema(description = "总输出 Token")
    val totalOutputToken: Long = 0L,
    @Schema(description = "总 Token")
    val grandTotalToken: Long = 0L,
    @Schema(description = "总费用（单位：元）")
    val totalFee: BigDecimal = BigDecimal.ZERO,
) : Serializable {
    companion object {
        @Serial
        private const val serialVersionUID = 1L
    }
}

/**
 * 时序数据
 */
@Schema(description = "时序数据")
data class TimeSeriesData(
    @Schema(description = "时间点")
    var timePoint: String? = null,
    @Schema(description = "维度标识(modelId/agentId/sessionId)")
    var dimensionId: String? = null,
    @Schema(description = "维度名称(modelName/agentName/sessionTitle)")
    var dimensionName: String? = null,
    @Schema(description = "输入 Token")
    var totalInputToken: Long = 0L,
    @Schema(description = "输出 Token")
    var totalOutputToken: Long = 0L,
    @Schema(description = "总 Token")
    var grandTotalToken: Long = 0L,
    @Schema(description = "总费用（单位：元）")
    var totalFee: BigDecimal = BigDecimal.ZERO,
) : Serializable {
    companion object {
        @Serial
        private const val serialVersionUID = 1L
    }
}

package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serial
import java.io.Serializable
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Token statistics aggregation query response
 */
@Schema(description = "Token statistics aggregation query response")
data class TokenStatsAggregationResponse(
    @Schema(description = "Overall statistics")
    var overall: OverallStats? = null,
    @Schema(description = "Aggregated data by model")
    var modelStats: List<ModelStats>? = null,
    @Schema(description = "Aggregated data by session")
    var sessionStats: List<SessionStats>? = null,
    @Schema(description = "Aggregated data by agent")
    var agentStats: List<AgentStats>? = null,
    @Schema(description = "Time series data")
    var timeSeriesData: List<TimeSeriesData>? = null,
) {
    companion object {
        /**
         * Convert Map to OverallStats
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
         * Convert Map to ModelStats
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
         * Convert Map to SessionStats
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
         * Convert Map to AgentStats
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
         * Convert Map to TimeSeriesData
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
         * Convert Map to TimeSeriesData (with dimension)
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

            // Set dimension info based on dimension type
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
 * Overall statistics
 */
@Schema(description = "Overall statistics")
data class OverallStats(
    @Schema(description = "Total input tokens")
    val totalInputToken: Long = 0L,
    @Schema(description = "Total output tokens")
    val totalOutputToken: Long = 0L,
    @Schema(description = "Total tokens")
    val grandTotalToken: Long = 0L,
    @Schema(description = "Total fee (unit: yuan)")
    val totalFee: BigDecimal = BigDecimal.ZERO,
    @Schema(description = "Agent count")
    val agentCount: Long = 0L,
    @Schema(description = "Session count")
    val sessionCount: Long = 0L,
    @Schema(description = "Model count")
    val modelCount: Long = 0L,
) : Serializable {
    companion object {
        @Serial
        private const val serialVersionUID = 1L
    }
}

/**
 * Model statistics
 */
@Schema(description = "Model statistics")
data class ModelStats(
    @Schema(description = "Model ID")
    val modelId: Long? = null,
    @Schema(description = "Model name")
    val modelName: String? = null,
    @Schema(description = "Provider name")
    val providerName: String? = null,
    @Schema(description = "Total input tokens")
    val totalInputToken: Long = 0L,
    @Schema(description = "Total output tokens")
    val totalOutputToken: Long = 0L,
    @Schema(description = "Total tokens")
    val grandTotalToken: Long = 0L,
    @Schema(description = "Total fee (unit: yuan)")
    val totalFee: BigDecimal = BigDecimal.ZERO,
) : Serializable {
    companion object {
        @Serial
        private const val serialVersionUID = 1L
    }
}

/**
 * Session statistics
 */
@Schema(description = "Session statistics")
data class SessionStats(
    @Schema(description = "Session ID")
    val sessionId: String? = null,
    @Schema(description = "Session title")
    val sessionTitle: String? = null,
    @Schema(description = "Total input tokens")
    val totalInputToken: Long = 0L,
    @Schema(description = "Total output tokens")
    val totalOutputToken: Long = 0L,
    @Schema(description = "Total tokens")
    val grandTotalToken: Long = 0L,
    @Schema(description = "Total fee (unit: yuan)")
    val totalFee: BigDecimal = BigDecimal.ZERO,
) : Serializable {
    companion object {
        @Serial
        private const val serialVersionUID = 1L
    }
}

/**
 * Agent statistics
 */
@Schema(description = "Agent statistics")
data class AgentStats(
    @Schema(description = "Agent ID")
    val agentId: Long? = null,
    @Schema(description = "Agent name")
    val agentName: String? = null,
    @Schema(description = "Total input tokens")
    val totalInputToken: Long = 0L,
    @Schema(description = "Total output tokens")
    val totalOutputToken: Long = 0L,
    @Schema(description = "Total tokens")
    val grandTotalToken: Long = 0L,
    @Schema(description = "Total fee (unit: yuan)")
    val totalFee: BigDecimal = BigDecimal.ZERO,
) : Serializable {
    companion object {
        @Serial
        private const val serialVersionUID = 1L
    }
}

/**
 * Time series data
 */
@Schema(description = "Time series data")
data class TimeSeriesData(
    @Schema(description = "Time point")
    var timePoint: String? = null,
    @Schema(description = "Dimension ID (modelId/agentId/sessionId)")
    var dimensionId: String? = null,
    @Schema(description = "Dimension name (modelName/agentName/sessionTitle)")
    var dimensionName: String? = null,
    @Schema(description = "Input tokens")
    var totalInputToken: Long = 0L,
    @Schema(description = "Output tokens")
    var totalOutputToken: Long = 0L,
    @Schema(description = "Total tokens")
    var grandTotalToken: Long = 0L,
    @Schema(description = "Total fee (unit: yuan)")
    var totalFee: BigDecimal = BigDecimal.ZERO,
) : Serializable {
    companion object {
        @Serial
        private const val serialVersionUID = 1L
    }
}

package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable

/**
 * 模型统计信息
 */
@Schema(description = "模型统计信息")
data class ModelStatsInfo(
    @Schema(description = "总模型数")
    val totalModels: Int = 0,
    @Schema(description = "启用模型数")
    val enabledModels: Int = 0,
    @Schema(description = "禁用模型数")
    val disabledModels: Int = 0,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

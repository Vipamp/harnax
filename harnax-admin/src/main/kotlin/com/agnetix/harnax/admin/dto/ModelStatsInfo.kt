package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable

/**
 * Model statistics information
 */
@Schema(description = "Model statistics information")
data class ModelStatsInfo(
    @Schema(description = "Total models")
    val totalModels: Int = 0,
    @Schema(description = "Enabled models")
    val enabledModels: Int = 0,
    @Schema(description = "Disabled models")
    val disabledModels: Int = 0,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

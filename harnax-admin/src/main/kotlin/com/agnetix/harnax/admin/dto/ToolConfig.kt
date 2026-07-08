package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Tool configuration for Agent")
data class ToolConfig(
    @Schema(description = "Tool ID", example = "1")
    val id: Long? = null,

    @Schema(description = "Whether allow to skip if missing", example = "true")
    val enableSkip: String? = null,

    @Schema(description = "Requires human confirmation (constrained by tool entity needConfirm)", example = "false")
    val needConfirm: Boolean? = null,
)

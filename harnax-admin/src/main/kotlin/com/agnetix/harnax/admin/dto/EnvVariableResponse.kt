package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "Environment variable response")
data class EnvVariableResponse(
    @Schema(description = "ID", example = "1")
    val id: Long? = null,

    @Schema(description = "Environment variable key", example = "API_KEY")
    val envKey: String? = null,

    @Schema(description = "Environment variable value (masked if sensitive)")
    val envValue: String? = null,

    @Schema(description = "Description")
    val description: String? = null,

    @Schema(description = "Sensitive flag (0: No, 1: Yes)")
    val sensitive: Int? = null,

    @Schema(description = "Enabled status (0: Disabled, 1: Enabled)")
    val enabled: Int? = null,

    @Schema(description = "Creator")
    val creator: String? = null,

    @Schema(description = "Creation time")
    val createTime: LocalDateTime? = null,

    @Schema(description = "Update time")
    val updateTime: LocalDateTime? = null,
)

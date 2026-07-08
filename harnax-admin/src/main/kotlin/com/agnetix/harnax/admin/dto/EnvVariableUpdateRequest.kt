package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

@Schema(description = "Environment variable update request")
data class EnvVariableUpdateRequest(
    @Schema(description = "Environment variable key")
    @Size(max = 200, message = "Key length cannot exceed 200 characters")
    val envKey: String? = null,

    @Schema(description = "Environment variable value")
    val envValue: String? = null,

    @Schema(description = "Description")
    @Size(max = 500, message = "Description length cannot exceed 500 characters")
    val description: String? = null,

    @Schema(description = "Sensitive flag (0: No, 1: Yes)")
    val sensitive: Int? = null,
)

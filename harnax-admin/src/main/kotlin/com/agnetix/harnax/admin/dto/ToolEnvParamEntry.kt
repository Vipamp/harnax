package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Tool environment parameter entry DTO
 * Used for create/update/response of tool env parameter configuration
 */
@Schema(description = "Tool environment parameter entry")
data class ToolEnvParamEntry(
    @Schema(description = "Env param entry ID (only in response)", example = "1")
    val id: Long? = null,

    @Schema(description = "Environment parameter name", example = "API_KEY", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Environment parameter name cannot be empty")
    @Size(max = 200, message = "Environment parameter name cannot exceed 200 characters")
    val envParamName: String = "",

    @Schema(description = "Human-readable description of this environment parameter", example = "Your OpenAI API key")
    val description: String? = null,

    @Schema(description = "Is required (must provide default value when true)", example = "false")
    val required: Boolean = false,

    @Schema(description = "Is sensitive (value will be encrypted and masked)", example = "false")
    val secret: Boolean = false,

    @Schema(description = "Default value (required when required=true)")
    val defaultValue: String? = null,
)

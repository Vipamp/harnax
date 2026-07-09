package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

@Schema(description = "Environment variable creation request")
data class EnvVariableCreateRequest(
    @Schema(description = "Environment variable key", example = "API_KEY", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Key cannot be empty")
    @Size(max = 200, message = "Key length cannot exceed 200 characters")
    @Pattern(regexp = "^[A-Za-z_][A-Za-z0-9_]*$", message = "Key must match pattern [A-Za-z_][A-Za-z0-9_]*")
    val envKey: String? = null,

    @Schema(description = "Environment variable value", example = "sk-xxx", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Value cannot be empty")
    @Size(max = 8192, message = "Value length cannot exceed 8192 characters")
    val envValue: String? = null,

    @Schema(description = "Description")
    @Size(max = 500, message = "Description length cannot exceed 500 characters")
    val description: String? = null,

    @Schema(description = "Sensitive flag (0: No, 1: Yes)", example = "0")
    val sensitive: Int? = null,

    @Schema(description = "Enabled status (0: Disabled, 1: Enabled)", example = "1")
    val enabled: Int? = null,
)

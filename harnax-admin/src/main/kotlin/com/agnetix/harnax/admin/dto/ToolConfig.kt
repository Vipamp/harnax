package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Tool configuration for Agent")
data class ToolConfig(
    @Schema(description = "Tool ID", example = "1")
    val id: Long? = null,

    @Schema(description = "Requires human confirmation (constrained by tool entity needConfirm)", example = "false")
    val needConfirm: Boolean? = null,

    @Schema(description = "Environment variable bindings")
    val envBindings: List<EnvBinding>? = null,
)

@Schema(description = "Environment variable binding snapshot")
data class EnvBinding(
    @Schema(description = "Tool/MCP environment parameter name", example = "SMTP_HOST")
    val envKey: String,

    @Schema(description = "Environment variable value snapshot", example = "smtp.example.com")
    val envValue: String? = null,

    @Schema(description = "Referenced env_variable table ID", example = "1")
    val envVarId: Long? = null,

    @Schema(description = "Referenced env variable name snapshot", example = "my_smtp_host")
    val envVarName: String? = null,

    @Schema(description = "Custom input value (mutually exclusive with envVarId)", example = "587")
    val customValue: String? = null,
)

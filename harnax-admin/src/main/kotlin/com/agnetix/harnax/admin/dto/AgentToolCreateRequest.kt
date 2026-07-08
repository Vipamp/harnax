package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "Agent tool creation request")
data class AgentToolCreateRequest(
    @Schema(description = "Tool identifier name (snake_case)", example = "weather_tool", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Tool name cannot be empty")
    @Size(max = 100, message = "Tool name length cannot exceed 100 characters")
    val name: String? = null,

    @Schema(description = "Display name", example = "Weather Tool")
    val displayName: String? = null,

    @Schema(description = "Tool description (sent to LLM)")
    val description: String? = null,

    @Schema(description = "Tool type: BUILTIN / CUSTOM / HTTP", example = "BUILTIN", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Tool type cannot be empty")
    val type: String = "BUILTIN",

    @Schema(description = "Spring Bean name (for BUILTIN/CUSTOM type)")
    val beanName: String? = null,

    @Schema(description = "HTTP request URL (for HTTP type)")
    val httpUrl: String? = null,

    @Schema(description = "HTTP method (for HTTP type)", example = "POST")
    val httpMethod: String? = null,

    @Schema(description = "HTTP headers JSON string (for HTTP type)")
    val httpHeaders: List<McpConfigEntry>? = null,

    @Schema(description = "Input parameter JSON Schema (for HTTP type)")
    val inputSchema: String? = null,

    @Schema(description = "Output result JSON Schema (for HTTP type)")
    val outputSchema: String? = null,

    @Schema(description = "Is read-only (0: No, 1: Yes)", example = "0")
    val readOnly: Int? = null,

    @Schema(description = "Requires human confirmation (0: No, 1: Yes)", example = "0")
    val needConfirm: Int? = null,

    @Schema(description = "Timeout in seconds", example = "30")
    val timeoutSeconds: Int? = null,
)

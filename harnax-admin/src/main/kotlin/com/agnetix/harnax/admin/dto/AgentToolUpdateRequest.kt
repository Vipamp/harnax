package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

@Schema(description = "Agent tool update request")
data class AgentToolUpdateRequest(
    @Schema(description = "Tool identifier name (snake_case)")
    @Size(max = 100, message = "Tool name length cannot exceed 100 characters")
    val name: String? = null,

    @Schema(description = "Display name")
    val displayName: String? = null,

    @Schema(description = "Tool description (sent to LLM)")
    val description: String? = null,

    @Schema(description = "Tool type: BUILTIN / CUSTOM / HTTP")
    val type: String? = null,

    @Schema(description = "Spring Bean name (for BUILTIN/CUSTOM type)")
    val beanName: String? = null,

    @Schema(description = "HTTP request URL (for HTTP type)")
    val httpUrl: String? = null,

    @Schema(description = "HTTP method (for HTTP type)")
    val httpMethod: String? = null,

    @Schema(description = "HTTP headers JSON string (for HTTP type)")
    val httpHeaders: List<McpConfigEntry>? = null,

    @Schema(description = "Input parameter JSON Schema (for HTTP type)")
    val inputSchema: String? = null,

    @Schema(description = "Output result JSON Schema (for HTTP type)")
    val outputSchema: String? = null,

    @Schema(description = "Is read-only (0: No, 1: Yes)")
    val readOnly: Int? = null,

    @Schema(description = "Requires human confirmation (0: No, 1: Yes)")
    val needConfirm: Int? = null,

    @Schema(description = "Timeout in seconds")
    val timeoutSeconds: Int? = null,
)

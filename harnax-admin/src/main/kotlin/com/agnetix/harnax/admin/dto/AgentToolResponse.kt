package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "Agent tool response")
data class AgentToolResponse(
    @Schema(description = "Tool ID", example = "1")
    val id: Long? = null,

    @Schema(description = "Tool identifier name", example = "weather_tool")
    val name: String? = null,

    @Schema(description = "Display name", example = "Weather Tool")
    val displayName: String? = null,

    @Schema(description = "Tool description")
    val description: String? = null,

    @Schema(description = "Tool type: BUILTIN / CUSTOM / HTTP", example = "BUILTIN")
    val type: String? = null,

    @Schema(description = "Spring Bean name")
    val beanName: String? = null,

    @Schema(description = "HTTP request URL")
    val httpUrl: String? = null,

    @Schema(description = "HTTP method")
    val httpMethod: String? = null,

    @Schema(description = "HTTP headers configuration (masked for secret values)")
    val httpHeaders: List<McpConfigEntry>? = null,

    @Schema(description = "Input parameter JSON Schema")
    val inputSchema: String? = null,

    @Schema(description = "Output result JSON Schema")
    val outputSchema: String? = null,

    @Schema(description = "Is read-only (0: No, 1: Yes)")
    val readOnly: Int? = null,

    @Schema(description = "Requires human confirmation (0: No, 1: Yes)")
    val needConfirm: Int? = null,

    @Schema(description = "Timeout in seconds")
    val timeoutSeconds: Int? = null,

    @Schema(description = "Status (0:disabled, 1:enabled)")
    val status: Int? = null,

    @Schema(description = "Creator")
    val creator: String? = null,

    @Schema(description = "Creation time")
    val createTime: LocalDateTime? = null,

    @Schema(description = "Update time")
    val updateTime: LocalDateTime? = null,
)

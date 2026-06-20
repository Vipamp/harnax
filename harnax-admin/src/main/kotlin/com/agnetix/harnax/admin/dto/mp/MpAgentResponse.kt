package com.agnetix.harnax.admin.dto.mp

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Mobile agent list response")
data class MpAgentResponse(
    @Schema(description = "Agent ID")
    val id: Long,

    @Schema(description = "Agent name")
    val name: String,

    @Schema(description = "Agent description")
    val description: String,

    @Schema(description = "Model name")
    val modelName: String,

    @Schema(description = "Status (0:disabled, 1:enabled)")
    val status: Int,

    @Schema(description = "Session count for current user")
    val sessionCount: Int,
)

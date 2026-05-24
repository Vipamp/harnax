package com.agnetix.harnax.agent.service.chat.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Confirm Request DTO
 */
@Schema(description = "Confirm Request")
data class ConfirmRequest(
    @Schema(description = "Session ID")
    val sessionId: String,

    @Schema(description = "Whether confirmed")
    val isConfirmed: Boolean = false,

    @Schema(description = "Tool information list")
    val toolInfoList: List<ToolInfo> = listOf(),

    @Schema(description = "Enable thinking mode")
    val enableThink: Boolean = false,

    @Schema(description = "Enable search")
    val enableSearch: Boolean = false,
) {
    /**
     * Tool information
     */
    @Schema(description = "Tool information")
    data class ToolInfo(
        @Schema(description = "Tool ID")
        val toolId: String? = null,

        @Schema(description = "Tool name")
        val toolName: String? = null,
    )
}

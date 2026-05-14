package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * @Author: heqingsong
 * @Date: 2026/4/27
 * @Description: SessionChatUpdateRequest
 * @Project: vipclaw
 */
data class SessionChatUpdateRequest(
    @Schema(description = "Enable deep thinking")
    val enableThink: Boolean? = false,

    @Schema(description = "Enable web search")
    val enableSearch: Boolean? = false,

    @Schema(description = "Enable planning")
    val enablePlan: Boolean? = false,
)

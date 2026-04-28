package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * @Author: heqingsong
 * @Date: 2026/4/27
 * @Description: SessionChatUpdateRequest
 * @Project: vipclaw
 */
data class SessionChatUpdateRequest(
    @Schema(description = "是否启用深度思考")
    val enableThink: Boolean? = false,

    @Schema(description = "是否启用联网搜索")
    val enableSearch: Boolean? = false,

    @Schema(description = "是否启用计划")
    val enablePlan: Boolean? = false
)

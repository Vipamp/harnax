package com.vipamp.vipclaw.ascopagent.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * 会话配置更新请求 DTO
 *
 * @Author: heqingsong
 * @Date: 2026/4/27
 * @Project: vipclaw
 */
@Schema(description = "会话配置更新请求")
data class SessionConfigUpdateRequest(
    @Schema(description = "是否启用深度思考")
    val enableThink: Boolean = false,

    @Schema(description = "是否启用联网搜索")
    val enableSearch: Boolean = false,

    @Schema(description = "是否启用计划")
    val enablePlan: Boolean = false,
)

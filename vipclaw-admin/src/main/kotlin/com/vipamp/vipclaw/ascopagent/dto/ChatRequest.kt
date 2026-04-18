package com.vipamp.vipclaw.ascopagent.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Chat 请求 DTO
 *
 * @Author: heqingsong
 * @Date: 2026/4/12
 * @Project: vipclaw
 */
@Schema(description = "Chat 请求")
data class ChatRequest(
    @Schema(description = "会话 ID")
    val sessionId: String,

    @Schema(description = "消息内容")
    val message: String,

    @Schema(description = "图片 URL 列表")
    val imageUrl: List<String> = emptyList(),

    @Schema(description = "是否启用思考模式")
    val enableThink: Boolean = false,

    @Schema(description = "是否启用搜索")
    val enableSearch: Boolean = false,

    @Schema(description = "是否启用计划")
    val enablePlan: Boolean = false,
)

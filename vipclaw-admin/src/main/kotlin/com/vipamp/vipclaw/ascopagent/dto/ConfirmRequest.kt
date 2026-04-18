package com.vipamp.vipclaw.ascopagent.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Confirm 请求 DTO
 *
 * @Author: heqingsong
 * @Date: 2026/4/12
 * @Project: vipclaw
 */
@Schema(description = "Confirm 请求")
data class ConfirmRequest(
    @Schema(description = "会话 ID")
    val sessionId: String,

    @Schema(description = "是否确认")
    val isConfirmed: Boolean = false,

    @Schema(description = "工具信息列表")
    val toolInfoList: List<ToolInfo> = listOf(),

    @Schema(description = "是否启用思考模式")
    val enableThink: Boolean = false,

    @Schema(description = "是否启用搜索")
    val enableSearch: Boolean = false
) {
    /**
     * 工具信息
     */
    @Schema(description = "工具信息")
    data class ToolInfo(
        @Schema(description = "工具 ID")
        val toolId: String? = null,

        @Schema(description = "工具名称")
        val toolName: String? = null
    )
}

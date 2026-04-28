package com.vipamp.vipclaw.admin.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

@Schema(description = "会话实体类")
class Session : Serializable {
    companion object { private const val serialVersionUID = 1L }

    @Schema(description = "ID")
    var id: Long = 0

    @Schema(description = "会话名称")
    var title: String = ""

    @Schema(description = "会话描述")
    var sessionDescription: String = ""

    @Schema(description = "会话ID")
    var sessionId: String = ""

    @Schema(description = "关联的智能体ID")
    var agentId: Long = 0

    @Schema(description = "智能体名称")
    var name: String = ""

    @Schema(description = "智能体描述")
    var description: String = ""

    @Schema(description = "系统提示词（支持 Markdown）")
    var systemPrompt: String = ""

    @Schema(description = "对话模型 ID")
    var modelId: Long = 0

    @Schema(description = "是否启用深度思考（0:否，1:是）")
    var enableThink: Int = 0

    @Schema(description = "是否启用联网搜索（0:否，1:是）")
    var enableSearch: Int = 0

    @Schema(description = "是否启用计划（0:否，1:是）")
    var enablePlan: Int = 0

    @Schema(description = "MCP 服务列表（JSON 格式）")
    var mcpList: String= "[]"

    @Schema(description = "技能列表（JSON 格式）")
    var skillList: String = "[]"

    @Schema(description = "所有者")
    var owner: String = ""

    @Schema(description = "是否启用（0:禁用，1:启用）")
    var status: Int = 1

    @Schema(description = "是否公开（0:否，1:是）")
    var isPublic: Int = 1

    @Schema(description = "创建人")
    var creator: String = ""

    @Schema(description = "是否可用（0:被删除，1:可用）")
    var active: Int = 1

    @Schema(description = "创建时间")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "更新时间")
    var updateTime: LocalDateTime = LocalDateTime.now()
}

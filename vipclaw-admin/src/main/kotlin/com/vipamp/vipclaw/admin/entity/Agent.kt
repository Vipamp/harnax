package com.vipamp.vipclaw.admin.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * 智能体实体类
 *
 * @author vipamp
 * @since 2026-03-18
 */
@Schema(description = "智能体实体类")
class Agent : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    /**
     * ID
     */
    @Schema(description = "ID")
    var id: Long = 0

    /**
     * 所属租户ID
     */
    @Schema(description = "所属租户ID")
    var tenantId: Long = 1

    /**
     * 智能体名称
     */
    @Schema(description = "智能体名称")
    var name: String = ""

    /**
     * 智能体描述
     */
    @Schema(description = "智能体描述")
    var description: String = ""

    /**
     * 系统提示词（支持 Markdown）
     */
    @Schema(description = "系统提示词（支持 Markdown）")
    var systemPrompt: String = ""

    /**
     * 对话模型 ID
     */
    @Schema(description = "对话模型 ID")
    var modelId: Long = 0

    /**
     * MCP 服务列表（JSON 格式）
     */
    @Schema(description = "MCP 服务列表（JSON 格式）")
    var mcpList: String = ""

    /**
     * 技能列表（JSON 格式）
     */
    @Schema(description = "技能列表（JSON 格式）")
    var skillList: String = ""

    /**
     * 所有者
     */
    @Schema(description = "所有者")
    var owner: String = ""

    /**
     * 是否启用（0:禁用，1:启用）
     */
    @Schema(description = "是否启用（0:禁用，1:启用）")
    var status: Int = 1

    /**
     * 是否公开（0:否，1:是）
     */
    @Schema(description = "是否公开（0:否，1:是）")
    var isPublic: Int = 1

    /**
     * 创建人
     */
    @Schema(description = "创建人")
    var creator: String = ""

    /**
     * 是否可用（0:被删除，1:可用）
     */
    @Schema(description = "是否可用（0:被删除，1:可用）")
    var active: Int = 1

    /**
     * 创建时间
     */
    @Schema(description = "创建时间")
    var createTime: LocalDateTime = LocalDateTime.now()

    /**
     * 更新时间
     */
    @Schema(description = "更新时间")
    var updateTime: LocalDateTime = LocalDateTime.now()
}

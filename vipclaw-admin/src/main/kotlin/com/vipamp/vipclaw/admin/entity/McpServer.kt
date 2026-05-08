package com.vipamp.vipclaw.admin.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * MCP 服务实体类
 *
 * @author vipamp
 * @since 2026-03-12
 */
@Schema(description = "MCP 服务实体类")
class McpServer : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    /**
     * MCP ID
     */
    @Schema(description = "MCP ID")
    var id: Long = 0

    /**
     * MCP 名称
     */
    @Schema(description = "MCP 名称")
    var name: String = ""

    /**
     * MCP 描述
     */
    @Schema(description = "MCP 描述")
    var description: String = ""

    /**
     * MCP 类型（stdio/sse/streamablehttp）
     */
    @Schema(description = "MCP 类型（stdio/sse/streamablehttp）")
    var type: String = "streamablehttp"

    /**
     * 执行命令（仅 stdio 类型生效）
     */
    @Schema(description = "执行命令（仅 stdio 类型生效）")
    var command: String = ""

    /**
     * 服务地址（sse/streamablehttp 类型生效）
     */
    @Schema(description = "服务地址（sse/streamablehttp 类型生效）")
    var url: String = ""

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

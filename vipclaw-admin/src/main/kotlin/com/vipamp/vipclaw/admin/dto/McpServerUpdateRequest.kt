package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

/**
 * MCP 服务更新请求 DTO
 */
@Schema(description = "MCP 服务更新请求对象")
data class McpServerUpdateRequest(
    @Schema(description = "MCP ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    val id: Long = 0,

    @Schema(description = "MCP 名称", example = "my-mcp-server")
    @Size(max = 100, message = "MCP 名称长度不能超过 100 个字符")
    val name: String = "",

    @Schema(description = "MCP 描述", example = "这是一个 MCP 服务")
    val description: String = "",

    @Schema(description = "MCP 类型（stdio/sse/streamablehttp）", example = "stdio")
    val type: String = "streamablehttp",

    @Schema(
        description = "执行命令（仅 stdio 类型生效）",
        example = "npx -y @modelcontextprotocol/server-filesystem /tmp",
    )
    val command: String = "",

    @Schema(description = "服务地址（sse/streamablehttp 类型生效）", example = "http://localhost:3000/sse")
    val url: String = "",

    @Schema(description = "状态（0:禁用，1:启用）", example = "1")
    val status: Int = 0,

    @Schema(description = "是否公开（0:否，1:是）", example = "1")
    val isPublic: Int = 0,
)

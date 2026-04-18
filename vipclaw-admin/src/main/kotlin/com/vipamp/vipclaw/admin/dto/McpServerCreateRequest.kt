package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * MCP 服务创建请求 DTO
 */
@Schema(description = "MCP 服务创建请求对象")
data class McpServerCreateRequest(
    @Schema(description = "MCP 名称", example = "my-mcp-server", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "MCP 名称不能为空")
    @Size(max = 100, message = "MCP 名称长度不能超过 100 个字符")
    val name: String? = null,

    @Schema(description = "MCP 描述", example = "这是一个 MCP 服务")
    val description: String? = null,

    @Schema(description = "MCP 类型（stdio/sse/streamablehttp）", example = "stdio", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "MCP 类型不能为空")
    val type: String? = null,

    @Schema(description = "执行命令（仅 stdio 类型生效）", example = "npx -y @modelcontextprotocol/server-filesystem /tmp")
    val command: String? = null,

    @Schema(description = "服务地址（sse/streamablehttp 类型生效）", example = "http://localhost:3000/sse")
    val url: String? = null,

    @Schema(description = "状态（0:禁用，1:启用）", example = "1")
    val status: Int? = null
)

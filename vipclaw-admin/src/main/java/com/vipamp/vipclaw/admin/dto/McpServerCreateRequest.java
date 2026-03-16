package com.vipamp.vipclaw.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * MCP 服务创建请求 DTO
 *
 * @author vipamp
 * @since 2026-03-12
 */
@Data
@Schema(description = "MCP 服务创建请求对象")
public class McpServerCreateRequest {

    /**
     * MCP 名称
     */
    @Schema(description = "MCP 名称", example = "my-mcp-server", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "MCP 名称不能为空")
    @Size(max = 100, message = "MCP 名称长度不能超过 100 个字符")
    private String name;

    /**
     * MCP 描述
     */
    @Schema(description = "MCP 描述", example = "这是一个 MCP 服务")
    private String description;

    /**
     * MCP 类型（stdio/sse/streamablehttp）
     */
    @Schema(description = "MCP 类型（stdio/sse/streamablehttp）", example = "stdio", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "MCP 类型不能为空")
    private String type;

    /**
     * 执行命令（仅 stdio 类型生效）
     */
    @Schema(description = "执行命令（仅 stdio 类型生效）", example = "npx -y @modelcontextprotocol/server-filesystem /tmp")
    private String command;

    /**
     * 服务地址（sse/streamablehttp 类型生效）
     */
    @Schema(description = "服务地址（sse/streamablehttp 类型生效）", example = "http://localhost:3000/sse")
    private String url;

    /**
     * 状态（0:禁用，1:启用）
     */
    @Schema(description = "状态（0:禁用，1:启用）", example = "1")
    private Integer status;
}

package com.vipamp.vipclaw.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * MCP 服务更新请求 DTO
 *
 * @author vipamp
 * @since 2026-03-12
 */
@Data
@Schema(description = "MCP 服务更新请求对象")
public class McpServerUpdateRequest {

    /**
     * MCP ID（更新时必须）
     */
    @Schema(description = "MCP ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    /**
     * MCP 名称
     */
    @Schema(description = "MCP 名称", example = "my-mcp-server")
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
    @Schema(description = "MCP 类型（stdio/sse/streamablehttp）", example = "stdio")
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

    /**
     * 是否公开（0:否，1:是）
     */
    @Schema(description = "是否公开（0:否，1:是）", example = "1")
    private Integer isPublic;
}

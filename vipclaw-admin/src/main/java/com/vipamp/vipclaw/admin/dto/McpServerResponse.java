package com.vipamp.vipclaw.admin.dto;

import com.vipamp.vipclaw.admin.entity.McpServer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * MCP 服务响应 DTO
 *
 * @author vipamp
 * @since 2026-03-12
 */
@Data
@Schema(description = "MCP 服务响应对象")
public class McpServerResponse {

    /**
     * MCP ID
     */
    @Schema(description = "MCP ID", example = "1")
    private Long id;

    /**
     * MCP 名称
     */
    @Schema(description = "MCP 名称", example = "my-mcp-server")
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
    @Schema(description = "执行命令（仅 stdio 类型生效）")
    private String command;

    /**
     * 服务地址（sse/streamablehttp 类型生效）
     */
    @Schema(description = "服务地址（sse/streamablehttp 类型生效）")
    private String url;

    /**
     * 是否启用（0:禁用，1:启用）
     */
    @Schema(description = "是否启用（0:禁用，1:启用）", example = "1")
    private Integer status;

    /**
     * 是否公开（0:否，1:是）
     */
    @Schema(description = "是否公开（0:否，1:是）", example = "1")
    private Integer isPublic;

    /**
     * 创建人
     */
    @Schema(description = "创建人", example = "admin")
    private String creator;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间", example = "2026-03-12 12:00:00")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Schema(description = "更新时间", example = "2026-03-12 12:00:00")
    private LocalDateTime updateTime;

    /**
     * 从实体对象转换
     *
     * @param mcpServer MCP 服务实体
     * @return MCP 服务响应对象
     */
    public static McpServerResponse fromEntity(McpServer mcpServer) {
        if (mcpServer == null) {
            return null;
        }
        McpServerResponse response = new McpServerResponse();
        response.setId(mcpServer.getId());
        response.setName(mcpServer.getName());
        response.setDescription(mcpServer.getDescription());
        response.setType(mcpServer.getType());
        response.setCommand(mcpServer.getCommand());
        response.setUrl(mcpServer.getUrl());
        response.setStatus(mcpServer.getStatus());
        response.setIsPublic(mcpServer.getIsPublic());
        response.setCreator(mcpServer.getCreator());
        response.setCreateTime(mcpServer.getCreateTime());
        response.setUpdateTime(mcpServer.getUpdateTime());
        return response;
    }
}

package com.vipamp.vipclaw.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 智能体更新请求 DTO
 *
 * @author vipamp
 * @since 2026-03-18
 */
@Data
@Schema(description = "智能体更新请求对象")
public class AgentUpdateRequest {

    /**
     * ID
     */
    @Schema(description = "ID", example = "1")
    private Long id;

    /**
     * 智能体名称
     */
    @Schema(description = "智能体名称", example = "assistant")
    @Size(min = 1, max = 100, message = "智能体名称长度必须在 1-100 之间")
    private String name;

    /**
     * 智能体描述
     */
    @Schema(description = "智能体描述")
    private String description;

    /**
     * 系统提示词（支持 Markdown）
     */
    @Schema(description = "系统提示词（支持 Markdown）")
    private String systemPrompt;

    /**
     * 对话模型 ID
     */
    @Schema(description = "对话模型 ID", example = "1")
    private Long modelId;

    /**
     * MCP 服务列表
     */
    @Schema(description = "MCP 服务列表")
    private List<AgentCreateRequest.McpConfig> mcpList;

    /**
     * 技能 ID 列表（逗号分隔，如 "1,2,3"）
     */
    @Schema(description = "技能 ID 列表（逗号分隔）", example = "1,2,3")
    private String skillList;

    /**
     * 所有者
     */
    @Schema(description = "所有者")
    private String owner;

    /**
     * 状态 (0:禁用 1:正常)
     */
    @Schema(description = "状态 (0:禁用 1:正常)", example = "1")
    private Integer status;

    /**
     * 是否公开 (0:否 1:是)
     */
    @Schema(description = "是否公开 (0:否 1:是)", example = "1")
    private Integer isPublic;
}

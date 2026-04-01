package com.vipamp.vipclaw.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话响应对象
 *
 * @author vipamp
 * @since 2026-03-25
 */
@Data
@Schema(description = "会话响应对象")
public class SessionResponse {

    /**
     * ID
     */
    @Schema(description = "ID", example = "1")
    private Long id;

    /**
     * 会话名称
     */
    @Schema(description = "会话名称", example = "我的会话")
    private String title;

    /**
     * 会话描述
     */
    @Schema(description = "会话描述", example = "这是一个会话描述")
    private String sessionDescription;

    /**
     * 会话ID
     */
    @Schema(description = "会话ID", example = "session-123")
    private String sessionId;

    /**
     * 关联的智能体ID
     */
    @Schema(description = "关联的智能体ID", example = "1")
    private Long agentId;

    /**
     * 智能体名称
     */
    @Schema(description = "智能体名称", example = "assistant")
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
     * 对话模型名称
     */
    @Schema(description = "对话模型名称", example = "GPT-4")
    private String modelName;

    /**
     * MCP 服务列表
     */
    @Schema(description = "MCP 服务列表")
    private List<McpItem> mcpList;

    /**
     * 技能列表
     */
    @Schema(description = "技能列表")
    private List<SkillItem> skillList;

    /**
     * 所有者
     */
    @Schema(description = "所有者", example = "admin")
    private String owner;

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
    @Schema(description = "创建时间", example = "2026-03-25 12:00:00")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Schema(description = "更新时间", example = "2026-03-25 12:00:00")
    private LocalDateTime updateTime;

    /**
     * MCP 项
     */
    @Data
    @Schema(description = "MCP 项")
    public static class McpItem {
        @Schema(description = "MCP ID", example = "1")
        private Long mcpId;

        @Schema(description = "MCP 名称", example = "filesystem")
        private String mcpName;

        @Schema(description = "MCP 描述", example = "文件系统服务")
        private String mcpDescription;

        @Schema(description = "是否允许跳过", example = "true")
        private String enableSkip;
    }

    /**
     * 技能项
     */
    @Data
    @Schema(description = "技能项")
    public static class SkillItem {
        @Schema(description = "仓库 ID", example = "1")
        private Long repositoryId;

        @Schema(description = "仓库名称", example = "qoder-skills")
        private String repositoryName;

        @Schema(description = "技能 ID", example = "1")
        private Long skillId;

        @Schema(description = "技能名称", example = "code-review")
        private String skillName;
    }
}

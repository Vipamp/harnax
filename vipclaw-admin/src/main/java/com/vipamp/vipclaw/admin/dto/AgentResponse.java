package com.vipamp.vipclaw.admin.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 智能体响应 DTO
 *
 * @author vipamp
 * @since 2026-03-18
 */
@Data
@Schema(description = "智能体响应对象")
public class AgentResponse {

    /**
     * ID
     */
    @Schema(description = "ID", example = "1")
    private Long id;

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
    @Schema(description = "创建时间", example = "2026-03-18 12:00:00")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Schema(description = "更新时间", example = "2026-03-18 12:00:00")
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
     * 内部类，用于反序列化 MCP 配置
     */
    @Data
    public static class McpConfigInternal {
        private Long id;
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

        @Schema(description = "技能描述", example = "代码审查技能")
        private String skillDescription;
    }

    /**
     * 从实体对象转换
     *
     * @param agent 智能体实体
     * @return 智能体响应对象
     */
    public static AgentResponse fromEntity(com.vipamp.vipclaw.admin.entity.Agent agent) {
        if (agent == null) {
            return null;
        }
        AgentResponse response = new AgentResponse();
        response.setId(agent.getId());
        response.setName(agent.getName());
        response.setDescription(agent.getDescription());
        response.setSystemPrompt(agent.getSystemPrompt());
        response.setModelId(agent.getModelId());
        response.setOwner(agent.getOwner());
        response.setStatus(agent.getStatus());
        response.setIsPublic(agent.getIsPublic());
        response.setCreator(agent.getCreator());
        response.setCreateTime(agent.getCreateTime());
        response.setUpdateTime(agent.getUpdateTime());
        
        // 解析 MCP 列表（JSON 格式）
        if (agent.getMcpList() != null && !agent.getMcpList().isEmpty()) {
            try {
                ObjectMapper objectMapper = new ObjectMapper();
                // 使用内部的 McpConfig 类进行反序列化
                List<McpConfigInternal> mcpConfigs = objectMapper.readValue(
                    agent.getMcpList(),
                    new TypeReference<List<McpConfigInternal>>() {}
                );
                
                // 转换为 McpItem 列表
                List<McpItem> mcpItems = mcpConfigs.stream()
                    .map(config -> {
                        McpItem item = new McpItem();
                        item.setMcpId(config.getId());
                        item.setEnableSkip(config.getEnableSkip());
                        return item;
                    })
                    .collect(Collectors.toList());
                
                response.setMcpList(mcpItems);
            } catch (Exception e) {
                // 解析失败时返回空列表
                response.setMcpList(List.of());
            }
        }
        
        // 解析技能列表（逗号分隔的字符串）
        if (agent.getSkillList() != null && !agent.getSkillList().isEmpty()) {
            try {
                String[] skillIds = agent.getSkillList().split(",");
                List<SkillItem> skillItems = new java.util.ArrayList<>();

                for (String skillIdStr : skillIds) {
                    try {
                        Long skillId = Long.parseLong(skillIdStr.trim());
                        SkillItem item = new SkillItem();
                        item.setSkillId(skillId);
                        // 注意：这里无法获取技能名称和仓库信息，因为只存储了 ID
                        // 如果需要显示名称，需要在 Service 层查询数据库
                        skillItems.add(item);
                    } catch (NumberFormatException e) {
                        // 跳过无效的技能 ID
                    }
                }

                response.setSkillList(skillItems);
            } catch (Exception e) {
                // 解析失败时返回空列表
                response.setSkillList(List.of());
            }
        }
        
        return response;
    }
}

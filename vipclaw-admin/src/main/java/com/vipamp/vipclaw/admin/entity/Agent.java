package com.vipamp.vipclaw.admin.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 智能体实体类
 *
 * @author vipamp
 * @since 2026-03-18
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("agent")
@Schema(description = "智能体实体类")
public class Agent implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "ID")
    private Long id;

    /**
     * 智能体名称
     */
    @Schema(description = "智能体名称")
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
    @Schema(description = "对话模型 ID")
    private Long modelId;

    /**
     * MCP 服务列表（JSON 格式）
     */
    @Schema(description = "MCP 服务列表（JSON 格式）")
    private String mcpList;

    /**
     * 技能列表（JSON 格式）
     */
    @Schema(description = "技能列表（JSON 格式）")
    private String skillList;

    /**
     * 所有者
     */
    @Schema(description = "所有者")
    private String owner;

    /**
     * 是否启用（0:禁用，1:启用）
     */
    @Schema(description = "是否启用（0:禁用，1:启用）")
    private Integer status;

    /**
     * 是否公开（0:否，1:是）
     */
    @Schema(description = "是否公开（0:否，1:是）")
    private Integer isPublic;

    /**
     * 创建人
     */
    @Schema(description = "创建人")
    private String creator;

    /**
     * 是否可用（0:被删除，1:可用）
     */
    @Schema(description = "是否可用（0:被删除，1:可用）")
    @TableLogic(value = "1", delval = "0")
    private Integer active;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

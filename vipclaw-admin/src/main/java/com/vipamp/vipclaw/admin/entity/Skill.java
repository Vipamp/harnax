package com.vipamp.vipclaw.admin.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 技能实体类
 *
 * @author vipamp
 * @since 2026-03-16
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("skill")
@Schema(description = "技能实体类")
public class Skill implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "ID")
    private Long id;

    /**
     * 技能名称
     */
    @Schema(description = "技能名称")
    private String name;

    /**
     * 仓库ID
     */
    @Schema(description = "仓库ID")
    private Long repositoryId;

    /**
     * 技能描述
     */
    @Schema(description = "技能描述")
    private String description;

    /**
     * skill.md 内容
     */
    @Schema(description = "skill.md 内容")
    private String skillmd;

    /**
     * 资源信息
     */
    @Schema(description = "资源信息")
    private String resources;

    /**
     * 是否启用（0:禁用，1:启用）
     */
    @Schema(description = "是否启用（0:禁用，1:启用）")
    private Integer status;

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

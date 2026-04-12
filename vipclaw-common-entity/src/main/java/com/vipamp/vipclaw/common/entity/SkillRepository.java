package com.vipamp.vipclaw.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 技能仓库实体类
 *
 * @author vipamp
 * @since 2026-03-16
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("skill_repository")
@Schema(description = "技能仓库实体类")
public class SkillRepository implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "ID")
    private Long id;

    /**
     * 仓库名称
     */
    @Schema(description = "仓库名称")
    private String name;

    /**
     * 仓库地址
     */
    @Schema(description = "仓库地址")
    private String url;

    /**
     * 分支名称
     */
    @Schema(description = "分支名称", example = "main")
    private String branch;

    /**
     * 仓库描述
     */
    @Schema(description = "仓库描述")
    private String description;

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

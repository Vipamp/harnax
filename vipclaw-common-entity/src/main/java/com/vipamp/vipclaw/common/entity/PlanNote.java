package com.vipamp.vipclaw.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * PlanNote 实体类
 *
 * @author vipamp
 * @since 2026-04-16
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("plan_note")
@Schema(description = "PlanNote实体类")
public class PlanNote implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "ID")
    private Long id;

    /**
     * 会话ID
     */
    @Schema(description = "会话ID")
    private String sessionId;

    /**
     * 计划ID
     */
    @Schema(description = "计划ID")
    private String planId;

    /**
     * 计划名称
     */
    @Schema(description = "计划名称")
    private String name;

    /**
     * 计划描述
     */
    @Schema(description = "计划描述")
    private String description;

    /**
     * 预期结果
     */
    @Schema(description = "预期结果")
    private String expectedOutcome;

    /**
     * 子任务列表（JSON格式）
     */
    @Schema(description = "子任务列表（JSON格式）")
    private String subtasks;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间")
    private String createdAt;

    /**
     * 完成时间
     */
    @Schema(description = "完成时间")
    private String finishedAt;

    /**
     * 耗时（秒）
     */
    @Schema(description = "耗时（秒）")
    private Long costTimeseconds;

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

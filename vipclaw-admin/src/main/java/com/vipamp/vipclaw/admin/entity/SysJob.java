package com.vipamp.vipclaw.admin.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 定时任务实体类
 *
 * @author vipamp
 * @since 2026-03-16
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("sys_job")
@Schema(description = "定时任务实体类")
public class SysJob implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 任务ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "任务ID")
    private Long id;

    /**
     * 任务名称
     */
    @Schema(description = "任务名称")
    private String jobName;

    /**
     * 任务组名
     */
    @Schema(description = "任务组名")
    private String jobGroup;

    /**
     * 执行类全路径
     */
    @Schema(description = "执行类全路径")
    private String jobClass;

    /**
     * Cron执行表达式
     */
    @Schema(description = "Cron执行表达式")
    private String cronExpression;

    /**
     * 状态（0-暂停，1-运行）
     */
    @Schema(description = "状态（0-暂停，1-运行）")
    private Integer jobStatus;

    /**
     * 是否允许并发（0-禁止，1-允许）
     */
    @Schema(description = "是否允许并发（0-禁止，1-允许）")
    private Integer concurrent;

    /**
     * 任务描述
     */
    @Schema(description = "任务描述")
    private String description;

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
     * 是否可用（0-已删除，1-未删除）
     */
    @Schema(description = "是否可用（0-已删除，1-未删除）")
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

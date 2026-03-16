package com.vipamp.vipclaw.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 定时任务更新请求 DTO
 *
 * @author vipamp
 * @since 2026-03-16
 */
@Data
@Schema(description = "定时任务更新请求对象")
public class SysJobUpdateRequest {

    /**
     * 任务ID
     */
    @Schema(description = "任务ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "任务ID不能为空")
    private Long id;

    /**
     * 任务名称
     */
    @Schema(description = "任务名称", example = "示例任务", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "任务名称不能为空")
    @Size(max = 100, message = "任务名称长度不能超过 100")
    private String jobName;

    /**
     * 任务组名
     */
    @Schema(description = "任务组名", example = "DEFAULT")
    @Size(max = 100, message = "任务组名长度不能超过 100")
    private String jobGroup;

    /**
     * 执行类全路径
     */
    @Schema(description = "执行类全路径", example = "com.vipclaw.admin.job.SampleJob", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "执行类不能为空")
    @Size(max = 255, message = "执行类长度不能超过 255")
    private String jobClass;

    /**
     * Cron执行表达式
     */
    @Schema(description = "Cron执行表达式", example = "0/5 * * * * ?", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Cron表达式不能为空")
    @Size(max = 100, message = "Cron表达式长度不能超过 100")
    private String cronExpression;

    /**
     * 是否允许并发（0-禁止，1-允许）
     */
    @Schema(description = "是否允许并发（0-禁止，1-允许）", example = "1")
    private Integer concurrent;

    /**
     * 任务描述
     */
    @Schema(description = "任务描述", example = "这是一个示例定时任务")
    @Size(max = 500, message = "任务描述长度不能超过 500")
    private String description;
}

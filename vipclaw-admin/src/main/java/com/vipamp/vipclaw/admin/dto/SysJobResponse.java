package com.vipamp.vipclaw.admin.dto;

import com.vipamp.vipclaw.admin.entity.SysJob;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 定时任务响应 DTO
 *
 * @author vipamp
 * @since 2026-03-16
 */
@Data
@Schema(description = "定时任务响应对象")
public class SysJobResponse {

    /**
     * 任务ID
     */
    @Schema(description = "任务ID", example = "1")
    private Long id;

    /**
     * 任务名称
     */
    @Schema(description = "任务名称", example = "示例任务")
    private String jobName;

    /**
     * 任务组名
     */
    @Schema(description = "任务组名", example = "DEFAULT")
    private String jobGroup;

    /**
     * 执行类全路径
     */
    @Schema(description = "执行类全路径", example = "com.vipclaw.admin.job.SampleJob")
    private String jobClass;

    /**
     * Cron执行表达式
     */
    @Schema(description = "Cron执行表达式", example = "0/5 * * * * ?")
    private String cronExpression;

    /**
     * 状态（0-暂停，1-运行）
     */
    @Schema(description = "状态（0-暂停，1-运行）", example = "1")
    private Integer jobStatus;

    /**
     * 是否允许并发（0-禁止，1-允许）
     */
    @Schema(description = "是否允许并发（0-禁止，1-允许）", example = "1")
    private Integer concurrent;

    /**
     * 任务描述
     */
    @Schema(description = "任务描述", example = "这是一个示例定时任务")
    private String description;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间", example = "2026-03-16 12:00:00")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Schema(description = "更新时间", example = "2026-03-16 12:00:00")
    private LocalDateTime updateTime;

    /**
     * 从实体对象转换
     *
     * @param job 定时任务实体
     * @return 定时任务响应对象
     */
    public static SysJobResponse fromEntity(SysJob job) {
        if (job == null) {
            return null;
        }
        SysJobResponse response = new SysJobResponse();
        response.setId(job.getId());
        response.setJobName(job.getJobName());
        response.setJobGroup(job.getJobGroup());
        response.setJobClass(job.getJobClass());
        response.setCronExpression(job.getCronExpression());
        response.setJobStatus(job.getJobStatus());
        response.setConcurrent(job.getConcurrent());
        response.setDescription(job.getDescription());
        response.setCreateTime(job.getCreateTime());
        response.setUpdateTime(job.getUpdateTime());
        return response;
    }
}

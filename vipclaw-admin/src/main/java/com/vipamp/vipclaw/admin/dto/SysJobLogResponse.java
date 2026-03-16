package com.vipamp.vipclaw.admin.dto;

import com.vipamp.vipclaw.admin.entity.SysJobLog;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 定时任务日志响应 DTO
 *
 * @author vipamp
 * @since 2026-03-16
 */
@Data
@Schema(description = "定时任务日志响应对象")
public class SysJobLogResponse {

    /**
     * 日志ID
     */
    @Schema(description = "日志ID", example = "1")
    private Long id;

    /**
     * 任务ID
     */
    @Schema(description = "任务ID", example = "1")
    private Long jobId;

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
     * 调用目标
     */
    @Schema(description = "调用目标", example = "com.vipclaw.admin.job.SampleJob")
    private String invokeTarget;

    /**
     * 执行信息
     */
    @Schema(description = "执行信息", example = "任务执行成功")
    private String jobMessage;

    /**
     * 执行状态（0-失败，1-成功）
     */
    @Schema(description = "执行状态（0-失败，1-成功）", example = "1")
    private Integer status;

    /**
     * 异常信息
     */
    @Schema(description = "异常信息")
    private String exceptionInfo;

    /**
     * 开始时间
     */
    @Schema(description = "开始时间", example = "2026-03-16 12:00:00")
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    @Schema(description = "结束时间", example = "2026-03-16 12:00:05")
    private LocalDateTime endTime;

    /**
     * 执行耗时（毫秒）
     */
    @Schema(description = "执行耗时（毫秒）", example = "5000")
    private Long duration;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间", example = "2026-03-16 12:00:00")
    private LocalDateTime createTime;

    /**
     * 从实体对象转换
     *
     * @param log 定时任务日志实体
     * @return 定时任务日志响应对象
     */
    public static SysJobLogResponse fromEntity(SysJobLog log) {
        if (log == null) {
            return null;
        }
        SysJobLogResponse response = new SysJobLogResponse();
        response.setId(log.getId());
        response.setJobId(log.getJobId());
        response.setJobName(log.getJobName());
        response.setJobGroup(log.getJobGroup());
        response.setInvokeTarget(log.getInvokeTarget());
        response.setJobMessage(log.getJobMessage());
        response.setStatus(log.getStatus());
        response.setExceptionInfo(log.getExceptionInfo());
        response.setStartTime(log.getStartTime());
        response.setEndTime(log.getEndTime());
        response.setCreateTime(log.getCreateTime());
        
        // 计算执行耗时
        if (log.getStartTime() != null && log.getEndTime() != null) {
            response.setDuration(java.time.Duration.between(log.getStartTime(), log.getEndTime()).toMillis());
        }
        
        return response;
    }
}

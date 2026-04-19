package com.vipamp.vipclaw.admin.dto

import com.vipamp.vipclaw.admin.entity.SysJobLog
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Duration
import java.time.LocalDateTime

/**
 * 定时任务日志响应对象
 */
@Schema(description = "定时任务日志响应对象")
data class SysJobLogResponse(
    @Schema(description = "日志ID", example = "1")
    val id: Long? = null,
    @Schema(description = "任务ID", example = "1")
    val jobId: Long? = null,
    @Schema(description = "任务名称", example = "示例任务")
    val jobName: String? = null,
    @Schema(description = "任务组名", example = "DEFAULT")
    val jobGroup: String? = null,
    @Schema(description = "调用目标", example = "com.vipclaw.admin.job.SampleJob")
    val invokeTarget: String? = null,
    @Schema(description = "执行信息", example = "任务执行成功")
    val jobMessage: String? = null,
    @Schema(description = "执行状态（0-失败，1-成功）", example = "1")
    val status: Int? = null,
    @Schema(description = "异常信息")
    val exceptionInfo: String? = null,
    @Schema(description = "开始时间", example = "2026-03-16 12:00:00")
    val startTime: LocalDateTime? = null,
    @Schema(description = "结束时间", example = "2026-03-16 12:00:05")
    val endTime: LocalDateTime? = null,
    @Schema(description = "执行耗时（毫秒）", example = "5000")
    var duration: Long? = null,
    @Schema(description = "创建时间", example = "2026-03-16 12:00:00")
    val createTime: LocalDateTime? = null
) {
    companion object {
        @JvmStatic
        fun fromEntity(entity: SysJobLog?): SysJobLogResponse {
            if (entity == null) return SysJobLogResponse()
            return SysJobLogResponse(
                id = entity.id,
                jobId = entity.jobId,
                jobName = entity.jobName,
                jobGroup = entity.jobGroup,
                invokeTarget = entity.invokeTarget,
                jobMessage = entity.jobMessage,
                status = entity.status,
                exceptionInfo = entity.exceptionInfo,
                startTime = entity.startTime,
                endTime = entity.endTime,
                duration = Duration.between(entity.startTime, entity.endTime).toMillis(),
                createTime = entity.createTime
            )
        }
    }
}

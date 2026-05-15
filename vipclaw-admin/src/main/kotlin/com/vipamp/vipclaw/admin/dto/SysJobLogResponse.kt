package com.vipamp.vipclaw.admin.dto

import com.vipamp.vipclaw.admin.entity.SysJobLog
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Duration
import java.time.LocalDateTime

/**
 * Scheduled job log response object
 */
@Schema(description = "Scheduled job log response object")
data class SysJobLogResponse(
    @Schema(description = "Log ID", example = "1")
    val id: Long? = null,
    @Schema(description = "Job ID", example = "1")
    val jobId: Long? = null,
    @Schema(description = "Job name", example = "Sample Job")
    val jobName: String? = null,
    @Schema(description = "Job group name", example = "DEFAULT")
    val jobGroup: String? = null,
    @Schema(description = "Invoke target", example = "com.vipclaw.admin.job.SampleJob")
    val invokeTarget: String? = null,
    @Schema(description = "Execution message", example = "Job executed successfully")
    val jobMessage: String? = null,
    @Schema(description = "Execution status (0:failed, 1:success)", example = "1")
    val status: Int? = null,
    @Schema(description = "Exception information")
    val exceptionInfo: String? = null,
    @Schema(description = "Start time", example = "2026-03-16 12:00:00")
    val startTime: LocalDateTime? = null,
    @Schema(description = "End time", example = "2026-03-16 12:00:05")
    val endTime: LocalDateTime? = null,
    @Schema(description = "Execution duration (milliseconds)", example = "5000")
    var duration: Long? = null,
    @Schema(description = "Creation time", example = "2026-03-16 12:00:00")
    val createTime: LocalDateTime? = null,
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
                createTime = entity.createTime,
            )
        }
    }
}

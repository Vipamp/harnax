package com.vipamp.vipclaw.admin.dto

import com.vipamp.vipclaw.admin.entity.SysJob
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * Scheduled job response object
 */
@Schema(description = "Scheduled job response object")
data class SysJobResponse(
    @Schema(description = "Job ID", example = "1")
    val id: Long? = null,
    @Schema(description = "Job name", example = "Sample Job")
    val jobName: String? = null,
    @Schema(description = "Job group name", example = "DEFAULT")
    val jobGroup: String? = null,
    @Schema(description = "Full path of execution class", example = "com.vipclaw.admin.job.SampleJob")
    val jobClass: String? = null,
    @Schema(description = "Cron expression", example = "0/5 * * * * ?")
    val cronExpression: String? = null,
    @Schema(description = "Status (0:paused, 1:running)", example = "1")
    val jobStatus: Int? = null,
    @Schema(description = "Allow concurrent execution (0:no, 1:yes)", example = "1")
    val concurrent: Int? = null,
    @Schema(description = "Job description", example = "This is a sample scheduled job")
    val description: String? = null,
    @Schema(description = "Whether public (0:no, 1:yes)", example = "1")
    val isPublic: Int? = null,
    @Schema(description = "Creator", example = "admin")
    val creator: String? = null,
    @Schema(description = "Creation time", example = "2026-03-16 12:00:00")
    val createTime: LocalDateTime? = null,
    @Schema(description = "Update time", example = "2026-03-16 12:00:00")
    val updateTime: LocalDateTime? = null,
) {
    companion object {
        @JvmStatic
        fun fromEntity(entity: SysJob?): SysJobResponse {
            if (entity == null) return SysJobResponse()
            return SysJobResponse(
                id = entity.id,
                jobName = entity.jobName,
                jobGroup = entity.jobGroup,
                jobClass = entity.jobClass,
                cronExpression = entity.cronExpression,
                jobStatus = entity.jobStatus,
                concurrent = entity.concurrent,
                description = entity.description,
                isPublic = entity.isPublic,
                creator = entity.creator,
                createTime = entity.createTime,
                updateTime = entity.updateTime,
            )
        }
    }
}

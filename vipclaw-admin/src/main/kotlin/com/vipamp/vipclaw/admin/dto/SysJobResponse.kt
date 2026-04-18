package com.vipamp.vipclaw.admin.dto

import com.vipamp.vipclaw.admin.entity.SysJob
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * 定时任务响应对象
 */
@Schema(description = "定时任务响应对象")
data class SysJobResponse(
    @Schema(description = "任务ID", example = "1")
    val id: Long? = null,
    @Schema(description = "任务名称", example = "示例任务")
    val jobName: String? = null,
    @Schema(description = "任务组名", example = "DEFAULT")
    val jobGroup: String? = null,
    @Schema(description = "执行类全路径", example = "com.vipclaw.admin.job.SampleJob")
    val jobClass: String? = null,
    @Schema(description = "Cron执行表达式", example = "0/5 * * * * ?")
    val cronExpression: String? = null,
    @Schema(description = "状态（0-暂停，1-运行）", example = "1")
    val jobStatus: Int? = null,
    @Schema(description = "是否允许并发（0-禁止，1-允许）", example = "1")
    val concurrent: Int? = null,
    @Schema(description = "任务描述", example = "这是一个示例定时任务")
    val description: String? = null,
    @Schema(description = "是否公开（0:否，1:是）", example = "1")
    val isPublic: Int? = null,
    @Schema(description = "创建人", example = "admin")
    val creator: String? = null,
    @Schema(description = "创建时间", example = "2026-03-16 12:00:00")
    val createTime: LocalDateTime? = null,
    @Schema(description = "更新时间", example = "2026-03-16 12:00:00")
    val updateTime: LocalDateTime? = null
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
                updateTime = entity.updateTime
            )
        }
    }
}

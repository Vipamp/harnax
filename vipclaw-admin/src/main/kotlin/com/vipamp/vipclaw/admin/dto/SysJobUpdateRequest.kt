package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.*

/**
 * 定时任务更新请求对象
 */
@Schema(description = "定时任务更新请求对象")
data class SysJobUpdateRequest(
    @Schema(description = "任务ID")
    @NotNull(message = "任务ID不能为空")
    val id: Long? = null,
    @Size(max = 100, message = "任务名称长度不能超过 100")
    val jobName: String? = null,
    @Schema(description = "任务组名")
    @Size(max = 100, message = "任务组名长度不能超过 100")
    val jobGroup: String? = null,
    @Size(max = 255, message = "执行类长度不能超过 255")
    val jobClass: String? = null,
    @Size(max = 100, message = "Cron表达式长度不能超过 100")
    val cronExpression: String? = null,
    @Schema(description = "是否允许并发（0-禁止，1-允许）", example = "1")
    val concurrent: Int? = null,
    @Schema(description = "任务描述")
    @Size(max = 500, message = "任务描述长度不能超过 500")
    val description: String? = null
)

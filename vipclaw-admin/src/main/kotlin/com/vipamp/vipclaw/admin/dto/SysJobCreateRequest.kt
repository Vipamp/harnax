package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

/**
 * 定时任务创建请求对象
 */
@Schema(description = "定时任务创建请求对象")
data class SysJobCreateRequest(
    @Size(max = 100, message = "任务名称长度不能超过 100")
    val jobName: String = "",
    @Schema(description = "任务组名")
    @Size(max = 100, message = "任务组名长度不能超过 100")
    val jobGroup: String = "",
    @Size(max = 255, message = "执行类长度不能超过 255")
    val jobClass: String = "",
    @Size(max = 100, message = "Cron表达式长度不能超过 100")
    val cronExpression: String = "",
    @Schema(description = "是否允许并发（0-禁止，1-允许）", example = "1")
    val concurrent: Int = 1,
    @Schema(description = "任务描述")
    @Size(max = 500, message = "任务描述长度不能超过 500")
    val description: String = "",
)

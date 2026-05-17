package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

/**
 * Scheduled task creation request object
 */
@Schema(description = "Scheduled task creation request object")
data class SysJobCreateRequest(
    @Size(max = 100, message = "Task name length cannot exceed 100")
    val jobName: String = "",
    @Schema(description = "Task group name")
    @Size(max = 100, message = "Task group name length cannot exceed 100")
    val jobGroup: String = "",
    @Size(max = 255, message = "Execute class length cannot exceed 255")
    val jobClass: String = "",
    @Size(max = 100, message = "Cron expression length cannot exceed 100")
    val cronExpression: String = "",
    @Schema(description = "Allow concurrent execution (0-disabled, 1-allowed)", example = "1")
    val concurrent: Int = 1,
    @Schema(description = "Task description")
    @Size(max = 500, message = "Task description length cannot exceed 500")
    val description: String = "",
)

package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/**
 * Scheduled task update request object
 */
@Schema(description = "Scheduled task update request object")
data class SysJobUpdateRequest(
    @Schema(description = "Task ID")
    @NotNull(message = "Task ID cannot be empty")
    val id: Long? = null,
    @Size(max = 100, message = "Task name length cannot exceed 100")
    val jobName: String? = null,
    @Schema(description = "Task group name")
    @Size(max = 100, message = "Task group name length cannot exceed 100")
    val jobGroup: String? = null,
    @Size(max = 255, message = "Execute class length cannot exceed 255")
    val jobClass: String? = null,
    @Size(max = 100, message = "Cron expression length cannot exceed 100")
    val cronExpression: String? = null,
    @Schema(description = "Allow concurrent execution (0-disabled, 1-allowed)", example = "1")
    val concurrent: Int? = null,
    @Schema(description = "Task description")
    @Size(max = 500, message = "Task description length cannot exceed 500")
    val description: String? = null,
)

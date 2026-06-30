package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

@Schema(description = "Agent task update request")
data class AgentTaskUpdateRequest(
    @Schema(description = "Task name")
    @field:Size(max = 128, message = "Task name must not exceed 128 characters")
    val name: String? = null,

    @Schema(description = "Agent ID")
    val agentId: Long? = null,

    @Schema(description = "Prompt content")
    val prompt: String? = null,

    @Schema(description = "Cron expression")
    val cronExpression: String? = null,

    @Schema(description = "Concurrent mode (0:no, 1:yes)")
    val concurrent: Int? = null,

    @Schema(description = "Timeout seconds")
    val timeoutSeconds: Int? = null,

    @Schema(description = "Description")
    @field:Size(max = 512, message = "Description must not exceed 512 characters")
    val description: String? = null,

    @Schema(description = "Public status (0:no, 1:yes)")
    val isPublic: Int? = null,
)

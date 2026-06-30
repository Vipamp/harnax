package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

@Schema(description = "Agent task create request")
data class AgentTaskCreateRequest(
    @Schema(description = "Task name", requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotBlank(message = "Task name is required")
    @field:Size(max = 128, message = "Task name must not exceed 128 characters")
    val name: String = "",

    @Schema(description = "Agent ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotNull(message = "Agent ID is required")
    val agentId: Long? = null,

    @Schema(description = "Prompt content", requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotBlank(message = "Prompt is required")
    val prompt: String = "",

    @Schema(description = "Cron expression", requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotBlank(message = "Cron expression is required")
    val cronExpression: String = "",

    @Schema(description = "Concurrent mode (0:no, 1:yes)")
    val concurrent: Int = 0,

    @Schema(description = "Timeout seconds")
    val timeoutSeconds: Int = 300,

    @Schema(description = "Description")
    @field:Size(max = 512, message = "Description must not exceed 512 characters")
    val description: String = "",

    @Schema(description = "Public status (0:no, 1:yes)")
    val isPublic: Int = 0,
)

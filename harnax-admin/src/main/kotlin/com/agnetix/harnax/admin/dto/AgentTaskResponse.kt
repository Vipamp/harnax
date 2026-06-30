package com.agnetix.harnax.admin.dto

import com.agnetix.harnax.entity.AgentTask
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "Agent task response")
data class AgentTaskResponse(
    @Schema(description = "ID")
    var id: Long = 0,

    @Schema(description = "Tenant ID")
    var tenantId: Long = 1,

    @Schema(description = "Task name")
    var name: String = "",

    @Schema(description = "Agent ID")
    var agentId: Long = 0,

    @Schema(description = "Agent name")
    var agentName: String = "",

    @Schema(description = "Prompt content")
    var prompt: String = "",

    @Schema(description = "Cron expression")
    var cronExpression: String = "",

    @Schema(description = "Task status (0:paused, 1:running)")
    var taskStatus: Int = 0,

    @Schema(description = "Concurrent mode (0:no, 1:yes)")
    var concurrent: Int = 0,

    @Schema(description = "Timeout seconds")
    var timeoutSeconds: Int = 300,

    @Schema(description = "Description")
    var description: String = "",

    @Schema(description = "Public status (0:no, 1:yes)")
    var isPublic: Int = 0,

    @Schema(description = "Creator")
    var creator: String = "",

    @Schema(description = "Active status (0:deleted, 1:active)")
    var active: Int = 1,

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now(),

    @Schema(description = "Update time")
    var updateTime: LocalDateTime = LocalDateTime.now(),
) {
    companion object {
        fun fromEntity(entity: AgentTask): AgentTaskResponse {
            return AgentTaskResponse(
                id = entity.id,
                tenantId = entity.tenantId,
                name = entity.name,
                agentId = entity.agentId,
                agentName = entity.agentName,
                prompt = entity.prompt,
                cronExpression = entity.cronExpression,
                taskStatus = entity.taskStatus,
                concurrent = entity.concurrent,
                timeoutSeconds = entity.timeoutSeconds,
                description = entity.description,
                isPublic = entity.isPublic,
                creator = entity.creator,
                active = entity.active,
                createTime = entity.createTime,
                updateTime = entity.updateTime,
            )
        }
    }
}

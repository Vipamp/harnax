package com.agnetix.harnax.admin.dto

import com.agnetix.harnax.entity.AgentTaskLog
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "Agent task log response")
data class AgentTaskLogResponse(
    @Schema(description = "ID")
    var id: Long = 0,

    @Schema(description = "Task ID")
    var taskId: Long = 0,

    @Schema(description = "Task name")
    var taskName: String = "",

    @Schema(description = "Prompt content")
    var prompt: String = "",

    @Schema(description = "Agent response")
    var response: String = "",

    @Schema(description = "Session ID")
    var sessionId: String = "",

    @Schema(description = "Status (0:failed, 1:success, 2:timeout)")
    var status: Int = 1,

    @Schema(description = "Error info")
    var errorInfo: String = "",

    @Schema(description = "Token usage JSON")
    var tokenUsage: String = "",

    @Schema(description = "Start time")
    var startTime: LocalDateTime? = null,

    @Schema(description = "End time")
    var endTime: LocalDateTime? = null,

    @Schema(description = "Duration in milliseconds")
    var durationMs: Long = 0,

    @Schema(description = "Creator")
    var creator: String = "",

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now(),
) {
    companion object {
        fun fromEntity(entity: AgentTaskLog): AgentTaskLogResponse = AgentTaskLogResponse(
            id = entity.id,
            taskId = entity.taskId,
            taskName = entity.taskName,
            prompt = entity.prompt,
            response = entity.response,
            sessionId = entity.sessionId,
            status = entity.status,
            errorInfo = entity.errorInfo,
            tokenUsage = entity.tokenUsage,
            startTime = entity.startTime,
            endTime = entity.endTime,
            durationMs = entity.durationMs,
            creator = entity.creator,
            createTime = entity.createTime,
        )
    }
}

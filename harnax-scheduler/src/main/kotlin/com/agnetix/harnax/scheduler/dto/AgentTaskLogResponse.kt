package com.agnetix.harnax.scheduler.dto

import com.agnetix.harnax.scheduler.entity.AgentTaskLog
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * One execution log as the task API answers it — a copy of `harnax-admin`'s DTO of the same name, field for
 * field, because the webui's run-history table is built on these keys.
 *
 * [response] and [errorInfo] carry another user's task traffic verbatim, which is why the only collection
 * read of this table goes through the owning task's visibility rule
 * ([com.agnetix.harnax.scheduler.mapper.AgentTaskLogMapper.selectLogList]) and never around it.
 */
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

    @Schema(description = "Status (0:failed, 1:success, 2:timeout, 3:running)")
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

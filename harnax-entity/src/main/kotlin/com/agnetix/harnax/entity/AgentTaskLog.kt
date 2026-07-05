package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

@Schema(description = "Agent Task Log entity")
class AgentTaskLog : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "ID")
    var id: Long = 0

    @Schema(description = "Task ID")
    var taskId: Long = 0

    @Schema(description = "Task name")
    var taskName: String = ""

    @Schema(description = "Prompt content")
    var prompt: String = ""

    @Schema(description = "Agent response")
    var response: String = ""

    @Schema(description = "Session ID")
    var sessionId: String = ""

    @Schema(description = "Status (0:failed, 1:success, 2:timeout, 3:running)")
    var status: Int = 3

    @Schema(description = "Error info")
    var errorInfo: String = ""

    @Schema(description = "Token usage JSON")
    var tokenUsage: String = ""

    @Schema(description = "Start time")
    var startTime: LocalDateTime? = null

    @Schema(description = "End time")
    var endTime: LocalDateTime? = null

    @Schema(description = "Duration in milliseconds")
    var durationMs: Long = 0

    @Schema(description = "Creator")
    var creator: String = ""

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()
}

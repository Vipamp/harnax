package com.vipamp.vipclaw.admin.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

@Schema(description = "Tool call log entity")
class ToolCallLogEntity : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "ID")
    var id: Long = 0

    @Schema(description = "Agent ID")
    var agentId: Long = 0

    @Schema(description = "Session ID")
    var sessionId: String = ""

    @Schema(description = "Tool name")
    var toolName: String = ""

    @Schema(description = "Tool arguments (JSON format)")
    var args: String = ""

    @Schema(description = "Tool execution result")
    var result: String = ""

    @Schema(description = "Success status (1-success, 0-failure)")
    var success: Int = 0

    @Schema(description = "Start timestamp")
    var startTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "End timestamp")
    var endTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Execution duration (milliseconds)")
    var duration: Long = 0

    @Schema(description = "Timestamp")
    var ts: LocalDateTime = LocalDateTime.now()
}

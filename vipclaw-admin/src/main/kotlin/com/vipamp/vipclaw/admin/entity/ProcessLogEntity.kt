package com.vipamp.vipclaw.admin.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

@Schema(description = "Process log entity")
class ProcessLogEntity : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "ID")
    var id: Long = 0

    @Schema(description = "Agent ID")
    var agentId: Long = 0

    @Schema(description = "Agent name")
    var agentName: String = ""

    @Schema(description = "Session ID")
    var sessionId: String = ""

    @Schema(description = "Log message")
    var message: String = ""

    @Schema(description = "Log type (INFO/WARN/ERROR)")
    var logType: String = "INFO"

    @Schema(description = "Exception stack trace")
    var stackTrace: String = ""

    @Schema(description = "Timestamp")
    var ts: LocalDateTime = LocalDateTime.now()
}

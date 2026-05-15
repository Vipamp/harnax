package com.vipamp.vipclaw.admin.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.math.BigDecimal
import java.time.LocalDateTime

@Schema(description = "Token consumption statistics entity")
class TokenStats : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "ID")
    var id: Long = 0

    @Schema(description = "Agent ID")
    var agentId: Long = 0

    @Schema(description = "Session ID")
    var sessionId: String = ""

    @Schema(description = "Chat model ID")
    var chatModelId: Long = 0

    @Schema(description = "Input token count")
    var inputToken: Long = 0

    @Schema(description = "Output token count")
    var outputToken: Long = 0

    @Schema(description = "Total token count")
    var totalToken: Long = 0

    @Schema(description = "Model fee (unit: yuan)")
    var fee: BigDecimal = BigDecimal.ZERO

    @Schema(description = "Timestamp")
    var ts: LocalDateTime = LocalDateTime.now()
}

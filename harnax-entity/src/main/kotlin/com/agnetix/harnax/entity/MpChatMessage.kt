package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

@Schema(description = "Mobile chat message entity")
class MpChatMessage : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "ID")
    var id: Long = 0

    @Schema(description = "Session ID (FK to mp_session.id)")
    var sessionId: Long = 0

    @Schema(description = "Message role (user / assistant / system)")
    var role: String = "user"

    @Schema(description = "Message content (plain text summary)")
    var content: String = ""

    @Schema(description = "Message segments (JSON array)")
    var segmentsJson: String = "[]"

    @Schema(description = "Token usage info (JSON object)")
    var tokenUsageJson: String? = null

    @Schema(description = "Image URLs (JSON array)")
    var imageUrlsJson: String? = null

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()
}

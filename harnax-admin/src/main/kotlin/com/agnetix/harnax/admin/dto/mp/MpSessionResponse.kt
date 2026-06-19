package com.agnetix.harnax.admin.dto.mp

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "Mobile session response")
data class MpSessionResponse(
    @Schema(description = "Session ID")
    val id: Long,

    @Schema(description = "Session name")
    val sessionName: String,

    @Schema(description = "Corresponding router session ID")
    val routerSessionId: String,

    @Schema(description = "Status (0:archived, 1:active)")
    val status: Int,

    @Schema(description = "Message count")
    val messageCount: Int = 0,

    @Schema(description = "Last message preview")
    val lastMessage: String? = null,

    @Schema(description = "Creation time")
    val createTime: LocalDateTime,

    @Schema(description = "Update time")
    val updateTime: LocalDateTime,
)

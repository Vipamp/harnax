package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

@Schema(description = "Mobile session entity")
class MpSession : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "ID")
    var id: Long = 0

    @Schema(description = "User ID (FK to sys_user)")
    var userId: Long = 0

    @Schema(description = "Session name")
    var sessionName: String = ""

    @Schema(description = "Corresponding router session ID")
    var routerSessionId: String = ""

    @Schema(description = "Associated agent ID")
    var agentId: Long = 0

    @Schema(description = "Status (0:archived, 1:active)")
    var status: Int = 1

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Update time")
    var updateTime: LocalDateTime = LocalDateTime.now()
}

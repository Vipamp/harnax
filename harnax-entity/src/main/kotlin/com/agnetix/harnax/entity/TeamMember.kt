package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * Team-member binding entity.
 *
 * Holds the reference and what this agent is responsible for inside this team. The responsibility note
 * defaults to the member agent's own description and is adjustable per team without writing back to
 * [Agent.description].
 */
@Schema(description = "Team member binding entity")
class TeamMember : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "Binding ID")
    var id: Long = 0

    @Schema(description = "FK to team.id")
    var teamId: Long = 0

    @Schema(description = "FK to agent.id, the member agent")
    var memberAgentId: Long = 0

    @Schema(description = "What this member is responsible for in this team")
    var delegationDescription: String = ""

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Update time")
    var updateTime: LocalDateTime = LocalDateTime.now()
}

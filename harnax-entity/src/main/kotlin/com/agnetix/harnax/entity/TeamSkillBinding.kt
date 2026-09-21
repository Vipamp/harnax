package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * Team-Skill binding entity: the skills the lead of a team is given.
 *
 * This is the only capability table a team owns. Tool, MCP and CLI bindings stay on [Agent], because a
 * lead orchestrates and executes nothing itself (design D8), so there is no entry point that could fill
 * such a binding in. Unlike [AgentSkillBinding] there is no `env_bindings` column: per-skill environment
 * values have no consumer on either side.
 */
@Schema(description = "Team Skill Binding entity")
class TeamSkillBinding : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "Binding ID")
    var id: Long = 0

    @Schema(description = "FK to team.id")
    var teamId: Long = 0

    @Schema(description = "FK to skill.id")
    var skillId: Long = 0

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Update time")
    var updateTime: LocalDateTime = LocalDateTime.now()
}

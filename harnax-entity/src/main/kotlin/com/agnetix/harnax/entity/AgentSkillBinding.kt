package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * Agent-Skill binding entity.
 * Represents a skill association with an agent, including
 * environment variable binding snapshots (for future use).
 */
@Schema(description = "Agent Skill Binding entity")
class AgentSkillBinding : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "Binding ID")
    var id: Long = 0

    @Schema(description = "FK to agent.id")
    var agentId: Long = 0

    @Schema(description = "FK to skill.id")
    var skillId: Long = 0

    @Schema(description = "Environment bindings JSON snapshot")
    var envBindings: String? = null

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Update time")
    var updateTime: LocalDateTime = LocalDateTime.now()
}

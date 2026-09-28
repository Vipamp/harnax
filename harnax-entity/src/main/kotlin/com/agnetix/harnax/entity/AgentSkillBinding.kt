package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * Agent-Skill binding entity.
 * Represents a skill association with an agent. There is no per-skill environment channel: unlike
 * [AgentToolBinding] and [AgentMcpBinding], whose `env_bindings` admin resolves into plaintext on
 * delivery, skills resolve none — so there is no `env_bindings` column on this table rather than a
 * reserved promise nothing reads.
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

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Update time")
    var updateTime: LocalDateTime = LocalDateTime.now()
}

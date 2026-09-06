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

    /**
     * Not in effect: nothing writes or reads this column today.
     *
     * `AgentServiceImpl.saveSkillBindings` persists agentId / skillId only, and the skill delivery
     * path never looks for per-skill env values — unlike [AgentToolBinding] and [AgentMcpBinding],
     * whose same column is resolved into plaintext by `InternalApiController.resolveEnvBindingsJson`.
     * Kept because the column is already there and a future skill-level env channel would use the
     * same shape; do not read it as "per-skill environment variables are supported".
     */
    @Schema(description = "Environment bindings JSON snapshot (reserved, never written for skills)")
    var envBindings: String? = null

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Update time")
    var updateTime: LocalDateTime = LocalDateTime.now()
}

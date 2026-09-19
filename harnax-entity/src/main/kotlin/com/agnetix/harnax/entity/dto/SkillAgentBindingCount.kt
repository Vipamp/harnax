package com.agnetix.harnax.entity.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * How many agents bind one skill.
 *
 * A projection because the read behind it groups the binding table by `skill_id` and selects two
 * columns; a [com.agnetix.harnax.entity.AgentSkillBinding] row would carry an `id` and an
 * `env_bindings` that no caller of this query may look at, and one row per binding is the N+1 this
 * grouping exists to avoid. Mutable with a no-arg constructor so MyBatis maps it by setter.
 */
@Schema(description = "Agent binding count for one skill")
class SkillAgentBindingCount {

    @Schema(description = "Skill id the count belongs to")
    var skillId: Long = 0

    @Schema(description = "Distinct agents binding that skill")
    var agentCount: Int = 0
}

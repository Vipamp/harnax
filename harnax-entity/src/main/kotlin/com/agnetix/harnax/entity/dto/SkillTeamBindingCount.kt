package com.agnetix.harnax.entity.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * How many teams bind one skill as their lead's configuration.
 *
 * The counterpart of [SkillAgentBindingCount] for `team_skill_binding`, and same-shaped on purpose:
 * the skill list shows both counts next to the same switch, and the guard that refuses to take a skill
 * out of circulation has to see both holders or it disagrees with the page.
 */
@Schema(description = "Team binding count for one skill")
class SkillTeamBindingCount {

    @Schema(description = "Skill id the count belongs to")
    var skillId: Long = 0

    @Schema(description = "Distinct teams binding that skill")
    var teamCount: Int = 0
}

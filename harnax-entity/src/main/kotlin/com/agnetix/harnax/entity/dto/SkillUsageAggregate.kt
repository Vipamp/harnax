package com.agnetix.harnax.entity.dto

import com.agnetix.harnax.entity.Skill
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * Usage of one skill inside a reporting window.
 *
 * A projection of `skill` LEFT JOIN `skill_usage`, so a skill with no events at all still comes back —
 * with zero counts — which is exactly the list the operations page is built to show. Mutable with a
 * no-arg constructor so MyBatis maps it by setter.
 */
@Schema(description = "Per-skill usage inside one window")
class SkillUsageAggregate {

    @Schema(description = "Skill id")
    var skillId: Long = 0

    @Schema(description = "Skill name")
    var name: String = ""

    @Schema(description = "Repository the skill lives in")
    var repositoryId: Long = 0

    @Schema(description = "Skill status (0:disabled, 1:enabled)")
    var status: Int = 1

    @Schema(description = "Provenance (human / agent_promoted)")
    var origin: String = Skill.ORIGIN_HUMAN

    @Schema(description = "Times loaded into a context within the window")
    var viewCount: Int = 0

    @Schema(description = "Times its instructions were followed within the window")
    var useCount: Int = 0

    @Schema(description = "Most recent event of either kind within the window, null when there none")
    var lastUsedAt: LocalDateTime? = null
}

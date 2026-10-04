package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * How far one skill is allowed to reach the model at runtime.
 *
 * Absence of a row means [MODE_ALL]: a skill an operator never configured stays visible, because a new
 * skill that nobody can load reads as a bug rather than as a guard. The modes that restrict carry their
 * parameter in the column named after them and leave the others null, so one row never holds two
 * contradictory answers.
 *
 * The runtime decision is made by `TenantSkillVisibilityFilter` from the copy delivered inside the agent
 * spec — see design section 5.4 — so this row is never read on the inference path.
 */
@Schema(description = "Runtime visibility policy of one skill")
class SkillVisibilityPolicy : Serializable {

    companion object {
        private const val serialVersionUID = 1L

        /** Visible to every user of every tenant that receives the skill. */
        const val MODE_ALL = "ALL"

        /** Visible to a stable percentage of users, keyed by user id and skill name. */
        const val MODE_CANARY = "CANARY"

        /** Visible only to the user ids listed in [userIds]. */
        const val MODE_ALLOW_LIST = "ALLOW_LIST"

        /** Visible only to runtimes whose environment label appears in [environments]. */
        const val MODE_ENV = "ENV"

        val MODES = setOf(MODE_ALL, MODE_CANARY, MODE_ALLOW_LIST, MODE_ENV)
    }

    @Schema(description = "Policy ID")
    var id: Long = 0

    @Schema(description = "Skill the policy restricts")
    var skillId: Long = 0

    @Schema(description = "Tenant owning that skill")
    var tenantId: Long = 1

    @Schema(description = "ALL / CANARY / ALLOW_LIST / ENV")
    var mode: String = MODE_ALL

    @Schema(description = "Rollout percentage 0-100 when mode is CANARY")
    var canaryPct: Int? = null

    @Schema(description = "User ids as a JSON array when mode is ALLOW_LIST")
    var userIds: String? = null

    @Schema(description = "Comma separated environment labels when mode is ENV")
    var environments: String? = null

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Last change time")
    var updateTime: LocalDateTime = LocalDateTime.now()
}

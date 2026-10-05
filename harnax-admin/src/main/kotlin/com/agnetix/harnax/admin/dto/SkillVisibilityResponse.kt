package com.agnetix.harnax.admin.dto

import com.agnetix.harnax.entity.SkillVisibilityPolicy
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * The runtime visibility of one skill, as the control plane shows it.
 *
 * A skill with no policy row answers with [mode] = ALL rather than as a 404: absence means open, the same
 * answer the runtime filter and the delivered spec give, so the screen never has to treat a missing row as
 * a separate state.
 *
 * [editable] is what keeps the UI from offering a dead-end control. A skill of the shared builtin
 * repository is visible to every tenant, so its policy is readable there too, but only the tenant that
 * owns the row may set it — an operator elsewhere would otherwise get a form that refuses on save.
 */
@Schema(description = "Runtime visibility policy of one skill")
data class SkillVisibilityResponse(
    @Schema(description = "Skill id", example = "1")
    val skillId: Long = 0,

    @Schema(description = "Skill name", example = "web-search")
    val skillName: String = "",

    @Schema(
        description = "Tenant owning the skill. Its members are exactly the ids an ALLOW_LIST may name, " +
            "so this is the set the control plane picks users from and the set the write validates against",
        example = "7",
    )
    val tenantId: Long? = null,

    @Schema(description = "ALL / CANARY / ALLOW_LIST / ENV", example = "CANARY")
    val mode: String = SkillVisibilityPolicy.MODE_ALL,

    @Schema(description = "Rollout percentage, when mode is CANARY", example = "20")
    val canaryPct: Int? = null,

    @Schema(description = "Allowed user ids, when mode is ALLOW_LIST")
    val userIds: List<Long> = emptyList(),

    @Schema(description = "The same ids with their usernames, so a list reads as people rather than numbers")
    val users: List<User> = emptyList(),

    @Schema(description = "Allowed environment labels, when mode is ENV")
    val environments: List<String> = emptyList(),

    @Schema(description = "Whether this caller's tenant may write this policy", example = "true")
    val editable: Boolean = false,

    @Schema(description = "When the policy last changed; null while no row exists")
    val updateTime: LocalDateTime? = null,
) {
    @Schema(description = "One allowed user, named")
    data class User(
        @Schema(description = "User id", example = "7")
        val id: Long = 0,

        @Schema(description = "Username; null when the account is gone, which is how a stale id stays visible as one", example = "linqing")
        val username: String? = null,
    )
}

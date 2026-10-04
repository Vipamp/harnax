package com.agnetix.harnax.entity.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Runtime visibility of one skill, delivered inside [SkillDetailDto] so the harness decides without
 * asking admin anything (design section 5.4).
 *
 * Mirrors `skill_visibility_policy` with the two encoded columns already decoded: [userIds] and
 * [environments] are what the runtime compares, and the JSON/CSV formats are a storage detail that
 * belongs on the admin side.
 *
 * A skill with no policy row is delivered without this field at all, which the runtime reads as
 * visible. An unrecognised [mode] is likewise treated as visible by
 * `com.agnetix.harnax.harness.skill.TenantSkillVisibilityFilter`: a mode added on the admin side
 * before the runtime understands it must not silently hide a working skill.
 */
@Schema(description = "Who may load this skill at runtime")
data class SkillVisibilityDto(
    @Schema(description = "ALL / CANARY / ALLOW_LIST / ENV", example = "CANARY")
    val mode: String = "",

    @Schema(description = "Rollout percentage 0-100, when mode is CANARY", example = "20")
    val canaryPct: Int? = null,

    @Schema(description = "User ids allowed to load the skill, when mode is ALLOW_LIST")
    val userIds: List<Long> = emptyList(),

    @Schema(description = "Environment labels allowed to load the skill, when mode is ENV")
    val environments: List<String> = emptyList(),
)

package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * A visibility policy about to be stored for one skill.
 *
 * One shape for all four modes, with the fields a mode does not use ignored and stored as null. That is
 * deliberate: the control plane posts the whole form on every save, and a partial-update semantics would
 * make "switch back to ALL" depend on the caller also remembering to clear the percentage it had set —
 * leaving a CANARY number on a row that now reads ALL, which the next operator would trust as the
 * rollout state.
 *
 * [mode] is required. The rest are validated by `SkillVisibilityService` against the mode, not here,
 * because which fields matter depends on which mode is set.
 */
@Schema(description = "Visibility policy for one skill")
data class SkillVisibilityUpdateRequest(
    @Schema(description = "ALL / CANARY / ALLOW_LIST / ENV", example = "CANARY", requiredMode = Schema.RequiredMode.REQUIRED)
    val mode: String? = null,

    @Schema(description = "Rollout percentage 0-100, required when mode is CANARY", example = "20")
    val canaryPct: Int? = null,

    @Schema(description = "User ids allowed to load the skill, required when mode is ALLOW_LIST")
    val userIds: List<Long>? = null,

    @Schema(description = "Environment labels allowed to load the skill, required when mode is ENV")
    val environments: List<String>? = null,
)

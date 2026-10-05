package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * A reviewer's "no", with the reason.
 *
 * The reason is required and stored twice on purpose: on the draft row, so the queue says why that name is
 * closed, and in the review log, so the trail reads as a sequence of decisions rather than a set of state
 * changes. An agent that offers the same skill again has nothing to learn from a bare REJECTED.
 */
@Schema(description = "Reject one agent-proposed skill")
data class SkillDraftRejectRequest(
    @Schema(
        description = "Why the proposal was refused",
        example = "Re-implements an existing skill and shells out to curl",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val reason: String? = null,
)

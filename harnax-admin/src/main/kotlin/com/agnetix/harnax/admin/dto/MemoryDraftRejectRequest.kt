package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * An owner's "no", with the reason.
 *
 * The reason is what the queue shows after the row closes, and it is load-bearing in a way it is not for a
 * skill: a rejected memory candidate leaves the conversation's own layer untouched, so the next merge window
 * proposes the same material again. The owner reads this sentence in the queue when that happens; the agent
 * that merged it reads nothing, because the runtime does not ask the queue why its proposal was refused.
 */
@Schema(description = "Reject one conversation's memory merge")
data class MemoryDraftRejectRequest(
    @Schema(
        description = "Why the merge was refused",
        example = "Records another project's credentials and repeats a rule the layer already holds",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val reason: String? = null,
)

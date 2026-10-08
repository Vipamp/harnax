package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * An owner's "yes", with the content they said yes to.
 *
 * [expectedDigest] is required and comes from `GET /api/admin/memory-drafts/{id}`. A conversation keeps
 * proposing while its candidate sits open — every time its throttle window opens the merge rewrites the row
 * — so without it the approval would apply whatever text the row holds now, which may not be the text the
 * owner read.
 *
 * There is no name or target to choose here, unlike a skill approval: the candidate says whose layer it
 * rewrites and which conversation it came out of, and both are settled at intake. What an owner decides is
 * the text.
 */
@Schema(description = "Approve one conversation's memory merge into its owner's long-term layer")
data class MemoryDraftApproveRequest(
    @Schema(
        description = "contentDigest of the candidate as the owner read it",
        example = "3f2a9c…",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val expectedDigest: String? = null,
)

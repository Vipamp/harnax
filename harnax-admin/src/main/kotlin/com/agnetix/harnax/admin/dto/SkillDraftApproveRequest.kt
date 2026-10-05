package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * A reviewer's "yes", with the content they said yes to.
 *
 * [expectedDigest] is required and comes from `GET /api/admin/skill-drafts/{id}`. A draft stays patchable by
 * the agent while it is PENDING, so without it the approval would land whatever body the row holds now —
 * which may not be the body the reviewer read.
 *
 * [conflictResolution] only matters when the tenant already holds a skill of that name in the landing
 * repository. There is no default: `replace` overwrites a row somebody else published, and `rename` puts a
 * second skill on a name users may already have bound, so both have to be a decision the reviewer made
 * rather than something the service guessed. A rename is checked against the same unique key the stored row
 * will be, so a name that is also taken comes back as a conflict rather than being silently suffixed.
 *
 * There is no repository choice here on purpose — the landing repository is resolved server-side, per
 * [com.agnetix.harnax.admin.constant.BuiltinRepository.AGENT_SKILLS]. A reviewer decides content, not where
 * it gets filed.
 */
@Schema(description = "Approve one agent-proposed skill and promote it into the skill table")
data class SkillDraftApproveRequest(
    @Schema(
        description = "contentDigest of the draft as the reviewer saw it",
        example = "3f2a9c…",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val expectedDigest: String? = null,

    @Schema(description = "How to resolve a name conflict: replace / rename", example = "rename")
    val conflictResolution: String? = null,

    @Schema(description = "Name to promote under when conflictResolution is rename", example = "invoice-pdf-fill-v2")
    val newName: String? = null,
)

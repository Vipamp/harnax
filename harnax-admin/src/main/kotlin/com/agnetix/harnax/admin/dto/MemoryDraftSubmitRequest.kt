package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * One conversation's merged memory layer, submitted for its owner to decide on.
 *
 * [sessionId] is the authority in the request, as in [SkillDraftSubmitRequest]: the owner and the tenant are
 * resolved from it here rather than read off the caller, so a runtime cannot file a merge into somebody else's
 * long-term layer by naming an agent it likes. [agentName] travels only so this service can refuse a proposal
 * whose conversation does not belong to that agent — the bucket segment the promotion pass mounted is the
 * agent's name, and a candidate that names a different one describes a layer no approval can reach.
 *
 * [baseVersion] is the store version of the owner's `MEMORY.md` the merge read, and it is what makes the
 * approval a decision about a known text: a layer that moved since then is refused rather than overwritten by
 * whoever clicks first. 0 means the owner had none yet, so the approval creates the object.
 *
 * [sources] are the conversation's own files the merge took its material out of, with the exact bytes it found
 * in each. Approval clears those and only those, and only while they still hold these bytes.
 */
@Schema(description = "A conversation's memory merge awaiting its owner's approval")
data class MemoryDraftSubmitRequest(
    @Schema(description = "Runtime session whose own layer was merged", example = "web-0f2a…")
    val sessionId: String? = null,

    @Schema(description = "Agent bucket segment the layer is mounted under", example = "Research")
    val agentName: String? = null,

    @Schema(description = "The complete MEMORY.md the merge produced")
    val mergedMarkdown: String? = null,

    @Schema(description = "The owner's text the merge read, null when they had none yet")
    val baseMarkdown: String? = null,

    @Schema(description = "Store version that text was read at; 0 when the object did not exist")
    val baseVersion: Long = 0,

    @Schema(description = "Every conversation-layer file the merge read, with the bytes found there")
    val sources: List<MemoryDraftSource>? = null,
)

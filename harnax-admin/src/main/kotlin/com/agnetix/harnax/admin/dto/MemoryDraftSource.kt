package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * One conversation-layer file a merge took its material out of, as the promotion pass recorded it.
 *
 * [path] is the route the pass read through — `MEMORY.md` for the conversation's own draft,
 * `memory/<file>.md` for one of its ledgers — and [content] is the exact text it found there. Both are
 * needed to finish an approval: the path resolves to the object to clear
 * ([com.agnetix.harnax.admin.util.MemoryObjectKeys.sessionSourceKey]), and the content is what that object
 * has to still hold for the clear to be allowed. A conversation keeps writing between the merge and the
 * decision, and a file whose bytes moved is a file the candidate does not describe.
 *
 * The two field names are the wire contract with `MemoryDraftSource` in harnax-agent-service, which sends
 * them inside `POST /api/admin/internal/memory/drafts`.
 */
@Schema(description = "One file of a conversation's own memory layer, with the bytes the merge read there")
data class MemoryDraftSource(
    @Schema(description = "Path inside the conversation's layer", example = "memory/2026-10-05.md")
    val path: String? = null,

    @Schema(description = "The text this object held when the merge read it")
    val content: String? = null,
)

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

/**
 * One day of the agent's long-term ledger that this candidate would rewrite.
 *
 * A candidate decides on the conclusion layer and on these together, and each of them needs its own
 * precondition: [expectedVersion] is the store version this day's file held when the merge read it, the same
 * claim [MemoryDraftSubmitRequest.baseVersion] makes for `MEMORY.md`. A day another conversation wrote in the
 * meantime is refused by name rather than overwritten. [path] resolves to the object through
 * [com.agnetix.harnax.admin.util.MemoryObjectKeys.longTermSourceKey], and [baseText] is what that day looked
 * like before the merge, so the screen can show one day's change instead of only its result.
 *
 * The four field names are the wire contract with `MemoryDraftTarget` in harnax-agent-service, which sends
 * them inside `POST /api/admin/internal/memory/drafts`.
 */
@Schema(description = "One daily file of the agent's long-term memory, with the version and bytes the merge read there")
data class MemoryDraftTarget(
    @Schema(description = "Path inside the agent's long-term layer", example = "memory/2026-10-08.md")
    val path: String? = null,

    @Schema(description = "Store version that day's file was read at; 0 when it did not exist")
    val expectedVersion: Long = 0,

    @Schema(description = "The text that day's file held when the merge read it, empty when it did not exist")
    val baseText: String? = null,

    @Schema(description = "The complete text the merge produced for that day")
    val mergedText: String? = null,
)

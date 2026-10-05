package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * One agent's memory as the owner's list shows it.
 *
 * [content] is the curated `MEMORY.md` text the agent loads into its prompt — empty when the agent has only
 * daily ledgers so far. [dates] is the append-only ledger: one entry per day the agent ran, listed but not
 * read here, because a page of agents has no business fetching a whole memory history per row.
 */
@Schema(description = "One agent's long-term memory of the current user")
data class MemoryAgentResponse(
    @Schema(description = "Agent the memory belongs to, as the runtime keys it", example = "Research")
    val agentId: String = "",

    @Schema(description = "Curated MEMORY.md text, empty when there is none yet")
    val content: String = "",

    @Schema(description = "When the curated layer was last written, ISO-8601 UTC", example = "2026-10-05T12:00:00Z")
    val lastModified: String? = null,

    @Schema(description = "Daily ledger dates present, oldest first")
    val dates: List<String> = emptyList(),
)

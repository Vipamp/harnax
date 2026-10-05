package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Everything one agent remembers about the caller: the curated layer and the daily ledgers behind it.
 *
 * The two layers are what the runtime keeps apart — a consolidator rewrites `MEMORY.md`, the per-call flush
 * only appends a day — so they are shown apart rather than merged into one text.
 */
@Schema(description = "One agent's full memory of the current user")
data class MemoryDetailResponse(
    @Schema(description = "Agent the memory belongs to, as the runtime keys it", example = "Research")
    val agentId: String = "",

    @Schema(description = "Curated MEMORY.md text, empty when there is none yet")
    val content: String = "",

    @Schema(description = "When the curated layer was last written, ISO-8601 UTC", example = "2026-10-05T12:00:00Z")
    val lastModified: String? = null,

    @Schema(description = "Daily ledger entries, oldest first")
    val entries: List<MemoryDailyEntryResponse> = emptyList(),
)

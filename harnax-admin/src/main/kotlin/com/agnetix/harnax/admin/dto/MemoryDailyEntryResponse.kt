package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * One daily ledger entry: the append-only text of one day, as the runtime wrote it.
 *
 * The bucket keys these by date (`memory/2026-10-05.md`), which is why [date] is the identity a client
 * shows and sorts by; the object key itself stays out — it names a location in a shared bucket and a
 * client never needs it.
 */
@Schema(description = "One day of the append-only memory ledger")
data class MemoryDailyEntryResponse(
    @Schema(description = "Entry date", example = "2026-10-05")
    val date: String = "",

    @Schema(description = "The day's ledger text")
    val content: String = "",

    @Schema(description = "When the entry was last written, ISO-8601 UTC", example = "2026-10-05T12:00:00Z")
    val lastModified: String? = null,
)

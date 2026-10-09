package com.agnetix.harnax.entity.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * The newest model call billed for one session: what it cost and which row says so.
 *
 * The row id travels next to the number because a reader cannot tell from `input_token` alone whether that
 * call still describes the conversation. A row written before the context was rewritten by a compaction is
 * the price of a request that no longer exists, and only insertion order puts it on one side of that moment.
 * `ts` is not that order: several calls of one turn share a `ts` written to the second.
 *
 * Mutable with a no-arg constructor so MyBatis maps it by setter, like the entities.
 */
@Schema(description = "Newest billed model call of one session")
class LatestCallUsage {

    @Schema(description = "token_stats row id of that call")
    var rowId: Long = 0

    @Schema(description = "Input tokens billed for that call")
    var inputTokens: Long = 0
}

package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDate

/**
 * One day of [ToolInvocationLog] folded into counters, keyed by `(statDate, tenantId, kind, subjectId,
 * toolName)`.
 *
 * Rows are recomputable and overwritten whole, so a re-run of any day changes nothing; that is what lets
 * several replicas schedule this rollup without a lock. The key holds no `agentId` or `sessionId`: their
 * cardinality is not bounded, and a day keyed by them would grow as fast as the detail table.
 *
 * [subjectId] is `0` rather than null for the kinds with no subject because a unique index does not treat
 * nulls as equal — a null there would let the upsert insert a second row for the same day.
 */
@Schema(description = "Daily tool invocation aggregate")
class ToolInvocationStats : Serializable {

    companion object {
        private const val serialVersionUID = 1L

        /** Upper bounds of the six duration buckets, in milliseconds. */
        val BUCKET_BOUNDS = listOf(100L, 500L, 2000L, 10000L, 30000L)
    }

    @Schema(description = "Aggregate row ID")
    var id: Long = 0

    @Schema(description = "Day of the calls")
    var statDate: LocalDate = LocalDate.now()

    @Schema(description = "Owning tenant")
    var tenantId: Long = 0

    @Schema(description = "Origin bucket")
    var kind: String = ToolInvocationLog.KIND_FRAMEWORK

    @Schema(description = "mcp_id or cli_id, 0 when neither applies")
    var subjectId: Long = 0

    @Schema(description = "Command name for kind cli, empty otherwise")
    var toolName: String = ""

    @Schema(description = "Total invocations")
    var calls: Int = 0

    @Schema(description = "Invocations ending SUCCESS")
    var successes: Int = 0

    @Schema(description = "Invocations ending ERROR")
    var errors: Int = 0

    @Schema(description = "Invocations ending DENIED")
    var denials: Int = 0

    @Schema(description = "Invocations ending INTERRUPTED")
    var interruptions: Int = 0

    @Schema(description = "Duration total, milliseconds")
    var sumDurationMs: Long = 0

    @Schema(description = "Longest single call of the day, milliseconds")
    var maxDurationMs: Long = 0

    @Schema(description = "Calls of at most 100 ms")
    var le100ms: Int = 0

    @Schema(description = "Calls over 100 ms and at most 500 ms")
    var le500ms: Int = 0

    @Schema(description = "Calls over 500 ms and at most 2 s")
    var le2s: Int = 0

    @Schema(description = "Calls over 2 s and at most 10 s")
    var le10s: Int = 0

    @Schema(description = "Calls over 10 s and at most 30 s")
    var le30s: Int = 0

    @Schema(description = "Calls over 30 s")
    var gt30s: Int = 0
}

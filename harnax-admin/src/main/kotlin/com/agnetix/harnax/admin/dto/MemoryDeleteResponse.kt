package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * The answer a memory deletion gives.
 *
 * The count is the objects that left the bucket, both routes together: an agent whose curated layer is gone
 * but whose three ledgers are not is still a memory the runtime will read, so "1" and "4" are different
 * answers to an operator.
 */
@Schema(description = "Result of deleting one agent's memory")
data class MemoryDeleteResponse(
    @Schema(description = "Agent whose memory was deleted", example = "Research")
    val agentId: String = "",

    @Schema(description = "Objects removed from the store")
    val deletedObjects: Int = 0,
)

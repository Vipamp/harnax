package com.agnetix.harnax.entity.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * How many live rows of each kind still point at one model.
 *
 * A projection because the delete guard needs all three answers to say what stands on the model, and
 * one read of each table would take three round trips to make one decision. Mutable with a no-arg
 * constructor so MyBatis maps it by setter, like the entities.
 */
@Schema(description = "Reference counts for one model")
class ModelUsage {

    @Schema(description = "Live agents running on the model")
    var agentCount: Int = 0

    @Schema(description = "Live teams running their lead on the model")
    var teamCount: Int = 0

    @Schema(description = "Live sessions pinning the model")
    var sessionCount: Int = 0
}

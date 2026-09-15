package com.agnetix.harnax.entity.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Who owns one channel session, read off the `channel` row that carries the id.
 *
 * A projection instead of [com.agnetix.harnax.entity.Channel] because the read that fills it selects
 * exactly these three columns: handing back a `Channel` would leave every other field at its Kotlin
 * default, and a default `active` of 1 on a row read without that column would say "live" about a
 * deleted channel. Mutable with a no-arg constructor so MyBatis maps it by setter, like the entities.
 */
@Schema(description = "Ownership answer for a channel session")
class ChannelSessionOwner {

    @Schema(description = "Session id, minted at channel creation and never changed")
    var sessionId: String = ""

    @Schema(description = "Tenant that owns the channel")
    var tenantId: Long = 0

    @Schema(description = "Agent the channel runs")
    var agentId: Long = 0
}

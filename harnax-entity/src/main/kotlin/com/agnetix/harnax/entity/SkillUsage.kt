package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * One skill being loaded into a context (`VIEW`) or its instructions being followed (`USE`).
 *
 * Append-only: the analytics side aggregates these and never updates a row. Keyed by `skillId`
 * rather than by name, because a skill name is only unique inside one repository and the same name
 * under two tenants must never share a counter.
 */
@Schema(description = "Skill load/use event")
class SkillUsage : Serializable {

    companion object {
        private const val serialVersionUID = 1L

        /** The skill was loaded into the model's context. */
        const val EVENT_VIEW = "VIEW"

        /** The model acted on the skill's instructions. */
        const val EVENT_USE = "USE"
    }

    @Schema(description = "Event ID")
    var id: Long = 0

    @Schema(description = "Tenant ID")
    var tenantId: Long = 1

    @Schema(description = "Skill ID")
    var skillId: Long = 0

    /**
     * Null until the runtime actually carries a user: an unknown owner stays unknown rather than
     * being filed under a sentinel, which would silently make per-user canaries look bucketed.
     */
    @Schema(description = "Owning user id, null when the runtime has none")
    var userId: Long? = null

    @Schema(description = "Event type (VIEW / USE)")
    var event: String = EVENT_VIEW

    @Schema(description = "Session that produced the event")
    var sessionId: String = ""

    @Schema(description = "Event time")
    var occurredAt: LocalDateTime = LocalDateTime.now()
}

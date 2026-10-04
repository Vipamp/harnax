package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * What the runtime saw in one session: which skills entered its context, and which of them the model
 * acted on.
 *
 * The session id is the whole identity in the request. No tenant field exists because the tenant is
 * resolved server-side from that id — a runtime that named its own tenant could file its events inside
 * somebody else's workspace, and no user field exists because the runtime does not reliably know who
 * is on the other end of the conversation.
 *
 * No event timestamp exists either: [com.agnetix.harnax.admin.service.SkillUsageService.report] stamps
 * receipt time. A caller-supplied clock would let a backdated report rewrite a closed reporting window.
 */
@Schema(description = "Skill usage events produced by one runtime session")
data class SkillUsageReportRequest(
    @Schema(description = "Runtime session that produced the events", example = "web-0f2a...")
    val sessionId: String? = null,

    @Schema(description = "Events, in the order the runtime noticed them")
    val events: List<Event>? = null,
) {
    /**
     * One event. [event] is [com.agnetix.harnax.entity.SkillUsage.EVENT_VIEW] or
     * [com.agnetix.harnax.entity.SkillUsage.EVENT_USE]; anything else is refused rather than stored,
     * since every read of this table compares the column against those two literals.
     */
    @Schema(description = "One skill usage event")
    data class Event(
        @Schema(description = "Skill id, never a name: names are only unique inside one repository", example = "1")
        val skillId: Long? = null,

        @Schema(description = "VIEW or USE", example = "VIEW")
        val event: String? = null,
    )
}

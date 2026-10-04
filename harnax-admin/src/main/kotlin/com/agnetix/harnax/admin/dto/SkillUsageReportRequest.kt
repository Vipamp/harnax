package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * What the runtime saw in one session: which skills entered its context, and which of them the model
 * acted on.
 *
 * The session id is the authority in the request. No tenant field exists because the tenant is resolved
 * server-side from that id — a runtime that named its own tenant could file its events inside somebody
 * else's workspace. [userId] is reported rather than resolved, because it is the one identity the runtime
 * does hold: the router authenticated the end user behind the run. It is still checked, not trusted — an
 * id outside the tenant the session resolved to is dropped, so a caller cannot attribute counts to people
 * who were not there.
 *
 * No event timestamp exists either: [com.agnetix.harnax.admin.service.SkillUsageService.report] stamps
 * receipt time. A caller-supplied clock would let a backdated report rewrite a closed reporting window.
 */
@Schema(description = "Skill usage events produced by one runtime session")
data class SkillUsageReportRequest(
    @Schema(description = "Runtime session that produced the events", example = "web-0f2a...")
    val sessionId: String? = null,

    @Schema(
        description = "End user the run is attributed to; absent or unmatched to the session's tenant leaves the rows unattributed",
        example = "42",
    )
    val userId: Long? = null,

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

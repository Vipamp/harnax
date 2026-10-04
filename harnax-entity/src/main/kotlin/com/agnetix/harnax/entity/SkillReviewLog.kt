package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * One recorded state change in the skill domain.
 *
 * `actor` is the whole point of the table: the upstream harness audit writes a literal `"agent"` for
 * everything its own tools do, which would erase the difference between a skill an agent produced and
 * a human agreeing to ship it. Every write here therefore names a real `sys_user` username, or the
 * documented sentinel [ACTOR_SYSTEM] when no human is on the path.
 */
@Schema(description = "Skill domain audit entry")
class SkillReviewLog : Serializable {

    companion object {
        private const val serialVersionUID = 1L

        const val SUBJECT_SKILL = "SKILL"
        const val SUBJECT_DRAFT = "DRAFT"

        /** Sentinel for a write with no human on the path, such as the runtime recording a scan. */
        const val ACTOR_SYSTEM = "system"

        const val ACTION_PROPOSE = "PROPOSE"
        const val ACTION_SCAN = "SCAN"
        const val ACTION_APPROVE = "APPROVE"
        const val ACTION_REJECT = "REJECT"
        const val ACTION_ENABLE = "ENABLE"
        const val ACTION_DISABLE = "DISABLE"
        const val ACTION_DELETE = "DELETE"
        const val ACTION_VISIBILITY_CHANGE = "VISIBILITY_CHANGE"
    }

    @Schema(description = "Log ID")
    var id: Long = 0

    @Schema(description = "Tenant ID")
    var tenantId: Long = 1

    @Schema(description = "Subject kind (SKILL / DRAFT)")
    var subject: String = SUBJECT_SKILL

    @Schema(description = "Row id of the subject")
    var subjectId: Long = 0

    @Schema(description = "Real operator: a sys_user username or the sentinel system")
    var actor: String = ACTOR_SYSTEM

    @Schema(description = "What happened")
    var action: String = ACTION_ENABLE

    @Schema(description = "Scan findings, reject reason or before/after values as JSON")
    var detail: String? = null

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()
}

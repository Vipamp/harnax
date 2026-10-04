package com.agnetix.harnax.admin.skill

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.entity.SkillReviewLog
import com.agnetix.harnax.mapper.SkillReviewLogMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * Writes the skill-domain audit trail.
 *
 * One entry point rather than an inline `insert` per call site because the columns that must never be
 * invented — `actor` and `tenant_id` — are both ambient, and a call site that guesses them is exactly
 * the failure this table exists to make visible.
 */
@Component
class SkillReviewRecorder(
    private val skillReviewLogMapper: SkillReviewLogMapper,
    private val jwtUtil: JwtUtil,
) {

    private val log = LoggerFactory.getLogger(SkillReviewRecorder::class.java)

    /**
     * Records one state change of a skill.
     *
     * [tenantId] overrides the ambient tenant for the paths that resolved it from a session rather than
     * from a login, such as an internal call from the runtime.
     */
    fun recordSkill(
        skillId: Long,
        action: String,
        detail: String? = null,
        tenantId: Long? = null,
        actor: String? = null,
    ) {
        record(
            subject = SkillReviewLog.SUBJECT_SKILL,
            subjectId = skillId,
            action = action,
            detail = detail,
            tenantId = tenantId,
            actor = actor,
        )
    }

    /** Newest first: the review page leads with what just happened. */
    fun history(
        subject: String,
        subjectId: Long,
        tenantId: Long,
    ): List<SkillReviewLog> = skillReviewLogMapper.selectBySubject(tenantId, subject, subjectId)

    private fun record(
        subject: String,
        subjectId: Long,
        action: String,
        detail: String?,
        tenantId: Long?,
        actor: String?,
    ) {
        val resolvedTenant = tenantId ?: TenantContext.getTenantId()
        if (resolvedTenant == null) {
            // An audit row without a tenant would read as every tenant's history; refusing is honest
            log.warn("Skipped skill audit write for {} {} action {}: no tenant in context", subject, subjectId, action)
            return
        }
        val resolvedActor = actor ?: currentActor()
        skillReviewLogMapper.insert(
            SkillReviewLog().apply {
                this.subject = subject
                this.subjectId = subjectId
                this.action = action
                this.tenantId = resolvedTenant
                this.detail = detail
                this.actor = resolvedActor
                this.createTime = LocalDateTime.now()
            },
        )
        log.info("Recorded skill audit {} {} action {} by {}", subject, subjectId, action, resolvedActor)
    }

    /**
     * Internal service calls carry no login, and [UserContextUtil.getCurrentUsername] throws for them.
     * The documented sentinel gets written instead of a plausible-looking name: an audit trail that
     * attributes a machine action to a person is worse than one that admits nobody was on the path.
     */
    private fun currentActor(): String = try {
        UserContextUtil.getCurrentUsername(jwtUtil)
    } catch (e: Exception) {
        SkillReviewLog.ACTOR_SYSTEM
    }
}

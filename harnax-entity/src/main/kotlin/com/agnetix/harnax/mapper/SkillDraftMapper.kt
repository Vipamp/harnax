package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.SkillDraft
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * The review queue of agent-proposed skills.
 *
 * [selectDraftList] is the one read that carries a tenant predicate, because it is the queue screen: it
 * has no row id to check first. The id-based reads and both writes match on the primary key alone, which
 * is safe for the same reason `SkillMapper` gives for itself — the service that calls them has already
 * resolved the row and run it through the tenant check that owns it, and a second filter in SQL could only
 * disagree with the first.
 */
@Mapper
interface SkillDraftMapper {

    fun insert(draft: SkillDraft): Int

    fun selectById(@Param("id") id: Long): SkillDraft?

    /**
     * The still-open draft of [name] in this tenant, if the agent has one.
     *
     * An agent patches the same proposal over and over, and a queue that grows a fresh row per patch turns
     * "review this skill" into "review these eleven copies". Most recently touched wins when two sessions
     * proposed the same name at once.
     */
    fun selectPendingByTenantAndName(
        @Param("tenantId") tenantId: Long,
        @Param("name") name: String,
    ): SkillDraft?

    /**
     * Replace the body of an open draft. Returns 0 when the row has since been decided, which is the answer
     * the caller must not treat as a success: a reviewer's decision outranks a late patch.
     */
    fun updateContent(draft: SkillDraft): Int

    /**
     * Decide one draft, only if it is still PENDING.
     *
     * One conditional statement rather than a read-then-write, so two reviewers opening the same draft at
     * the same second end with one of their decisions and a named refusal for the other, not with the
     * second silently overwriting the first.
     */
    fun markReviewed(
        @Param("id") id: Long,
        @Param("status") status: String,
        @Param("reviewedBy") reviewedBy: String,
        @Param("rejectReason") rejectReason: String? = null,
    ): Int

    /** Queue screen: newest first, filtered by status and name within one tenant. */
    fun selectDraftList(
        @Param("tenantId") tenantId: Long,
        @Param("status") status: String? = null,
        @Param("name") name: String? = null,
        @Param("sourceSessionId") sourceSessionId: String? = null,
    ): List<SkillDraft>
}

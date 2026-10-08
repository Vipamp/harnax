package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.MemoryDraft
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * The approval queue of conversation memory merges.
 *
 * [selectDraftList] is the one read carrying both scoping predicates, because it is the queue screen: it has
 * no row to check first. Its `user_id` condition is what makes this queue personal rather than the workspace's
 * — a memory merge is somebody's own recollection, so a row is visible to exactly the person whose long-term
 * layer it would rewrite. The id-based reads and both writes match on the primary key alone, which is safe for
 * the same reason `SkillDraftMapper` gives for itself: the service that calls them has already resolved the row
 * and run it through the tenant-and-owner check that owns it, and a second filter in SQL could only disagree
 * with the first.
 */
@Mapper
interface MemoryDraftMapper {

    fun insert(draft: MemoryDraft): Int

    fun selectById(@Param("id") id: Long): MemoryDraft?

    /**
     * The still-open candidate of one conversation, if it has one.
     *
     * A conversation proposes again each time its throttle window opens, and the newer proposal subsumes the
     * older one — it merged the same layer plus whatever the turns since then wrote. A queue that grew a fresh
     * row per proposal would ask the owner to decide the same merge several times over.
     *
     * Most recently touched wins when two rows for one session are somehow both open, which is the shape a
     * proposal racing its own decision produces.
     */
    fun selectPendingBySession(
        @Param("tenantId") tenantId: Long,
        @Param("sessionId") sessionId: String,
    ): MemoryDraft?

    /**
     * Replace the whole body of an open candidate. Returns 0 when the row has since been decided, which is the
     * answer the caller must not treat as a success: an owner's decision outranks a late proposal.
     */
    fun updateContent(draft: MemoryDraft): Int

    /**
     * Decide one candidate, only if it is still PENDING.
     *
     * One conditional statement rather than a read-then-write, so two approvals of the same candidate on two
     * tabs end with one of them applied and a named refusal for the other — not with the second writing the
     * owner's long-term layer over the first's choice.
     */
    fun markReviewed(
        @Param("id") id: Long,
        @Param("status") status: String,
        @Param("reviewedBy") reviewedBy: String,
        @Param("rejectReason") rejectReason: String? = null,
    ): Int

    /** Queue screen: newest first, filtered by status, agent and conversation within one owner. */
    fun selectDraftList(
        @Param("tenantId") tenantId: Long,
        @Param("userId") userId: Long,
        @Param("status") status: String? = null,
        @Param("agentName") agentName: String? = null,
        @Param("sessionId") sessionId: String? = null,
    ): List<MemoryDraft>
}

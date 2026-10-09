package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.SkillDraftApproveRequest
import com.agnetix.harnax.admin.dto.SkillDraftDecisionResponse
import com.agnetix.harnax.admin.dto.SkillDraftDetailResponse
import com.agnetix.harnax.admin.dto.SkillDraftRejectRequest
import com.agnetix.harnax.admin.dto.SkillDraftResponse
import com.agnetix.harnax.admin.dto.SkillDraftSubmitRequest

/**
 * The review queue of agent-proposed skills: intake from the runtime, decisions from a reviewer.
 *
 * The two halves meet at one invariant — nothing reaches the `skill` table without a human. Intake is the
 * runtime's side and never touches it; the decisions are the reviewer's side and are the only path that does.
 */
interface SkillDraftService {

    /**
     * Queues one proposal, merging it into the tenant's open draft of the same name.
     *
     * @return the id of the row that now holds the proposal, new or merged
     * @throws com.agnetix.harnax.admin.exception.BizException when the proposal cannot be attributed to a
     * tenant, or when it is not storable as sent — a name over the column's width, an empty body, a file
     * path that would escape the skill directory. The caller is a promotion gate sitting inside a live
     * inference, so the refusal has to name the reason it will not be retried.
     */
    fun submit(request: SkillDraftSubmitRequest): Long

    /**
     * The queue within the caller's tenant, newest touched first.
     *
     * [sessionId] narrows the queue to one conversation's proposals, which is what a session panel asks for;
     * it is a SQL predicate rather than a post-filter because PageHelper counts the rows it hands back, so a
     * filter applied after paging would report a total that does not match the page.
     *
     * @param status one of `PENDING` / `APPROVED` / `REJECTED`, or null for all of them. `EXPIRED` is a
     * documented column value that no code writes, so filtering by it is refused rather than answered with
     * an empty list that would read as "nothing to review".
     * @throws com.agnetix.harnax.admin.exception.BizException when [status] names none of the above
     */
    fun page(
        status: String?,
        name: String?,
        sessionId: String?,
        pageNum: Int,
        pageSize: Int,
    ): Page<SkillDraftResponse>

    /**
     * One draft with everything a decision needs, including the digest an approval has to send back.
     *
     * @throws com.agnetix.harnax.admin.exception.BizException when the id is unknown or belongs to another
     * tenant — both answer the same way, because a different refusal would confirm whose draft it is.
     */
    fun detail(id: Long): SkillDraftDetailResponse

    /**
     * Promotes a draft into the tenant's skill table.
     *
     * Nothing here throws to say "the reviewer was too slow": a changed digest, a draft somebody already
     * decided, and a name that is taken are all answers the screen has to act on, so they come back as an
     * outcome. A refusal never writes, and the row stays PENDING for the next attempt.
     *
     * @throws com.agnetix.harnax.admin.exception.BizException when the request itself is unusable — no
     * digest, an unknown conflict resolution, a rename with no new name — or when the landing repository
     * cannot be used at all.
     * @throws org.springframework.dao.DuplicateKeyException when another publisher claimed the name between
     * the conflict probe and this write; the whole approval rolls back, draft decision included.
     */
    fun approve(
        id: Long,
        request: SkillDraftApproveRequest,
    ): SkillDraftDecisionResponse

    /**
     * Closes a proposal with a reason. Writes nothing to the skill table and rolls back nothing else: a
     * rejection is a record, not an undo.
     *
     * A draft somebody already decided comes back as an outcome, the same way [approve] answers it, so the
     * screen can show which decision beat this one instead of failing on a row that is fine.
     *
     * @throws com.agnetix.harnax.admin.exception.BizException when the reason is blank or too long for the
     * column, or when the id is unknown or belongs to another tenant
     */
    fun reject(
        id: Long,
        request: SkillDraftRejectRequest,
    ): SkillDraftDecisionResponse
}

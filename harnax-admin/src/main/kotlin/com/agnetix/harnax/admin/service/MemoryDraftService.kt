package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.MemoryDraftApproveRequest
import com.agnetix.harnax.admin.dto.MemoryDraftDecisionResponse
import com.agnetix.harnax.admin.dto.MemoryDraftDetailResponse
import com.agnetix.harnax.admin.dto.MemoryDraftRejectRequest
import com.agnetix.harnax.admin.dto.MemoryDraftResponse
import com.agnetix.harnax.admin.dto.MemoryDraftSubmitRequest
import com.agnetix.harnax.admin.dto.Page

/**
 * The queue of conversation memory merges: intake from a runtime, decisions from the person the memory
 * belongs to.
 *
 * The two halves meet at one invariant — nothing reaches an owner's long-term `MEMORY.md` without that
 * owner. Intake is the runtime's side and writes only a row here; the decisions are the owner's side and are
 * the only path that touches the memory bucket. That makes this service the single writer of the long-term
 * layer, which is why an approval is a version-checked store write rather than a database update: a second
 * approval of a stale candidate has to be refused by the bytes it would overwrite, not by a flag in a table.
 */
interface MemoryDraftService {

    /**
     * Queues one conversation's merged layer, replacing that conversation's still-open candidate.
     *
     * @return the id of the row that now holds the candidate, new or rewritten
     * @throws com.agnetix.harnax.admin.exception.BizException when the proposal cannot be attributed to a
     *   person and an agent, when the conversation did not run the agent it names, when either text is over
     *   the column's ceiling, or when a source path is not a file a merge could have read. The caller is a
     *   promotion pass sitting inside a live conversation, so the refusal has to name the reason it will
     *   repeat next window rather than be retried blind.
     */
    fun submit(request: MemoryDraftSubmitRequest): Long

    /**
     * The caller's own candidates, newest touched first.
     *
     * @param status one of `PENDING` / `APPROVED` / `REJECTED`, or null for all of them.
     * @throws com.agnetix.harnax.admin.exception.BizException when [status] names none of the above, or when
     *   the caller is not a logged-in owner
     */
    fun page(
        status: String?,
        agentName: String?,
        sessionId: String?,
        pageNum: Int,
        pageSize: Int,
    ): Page<MemoryDraftResponse>

    /**
     * One candidate with both texts and the source files, including the digest an approval has to send back.
     *
     * @throws com.agnetix.harnax.admin.exception.BizException when the id is unknown, belongs to another
     *   tenant, or belongs to another person — all three answer the same way, because a different refusal
     *   would confirm whose merge it is.
     */
    fun detail(id: Long): MemoryDraftDetailResponse

    /**
     * Writes the candidate into the owner's long-term layer and clears the conversation files it merged out
     * of.
     *
     * Nothing here throws to say the owner was too slow: a changed digest, a candidate already decided, and a
     * layer that moved since the merge read it all come back as an outcome carrying what the next attempt
     * needs. A refusal writes nothing, and the row stays PENDING.
     *
     * The store write and the source clear happen in that order on purpose. A clear that ran first would drop
     * a conversation's memory on the strength of a candidate that then failed to land; a clear that fails
     * after a successful write leaves text the owner did approve in place, and the same candidate re-lands
     * idempotently on the next attempt.
     *
     * @throws com.agnetix.harnax.admin.exception.BizException when the request carries no digest, when this
     *   deployment has no reachable memory store, or when the store refused the write after the row was
     *   claimed — that last one is a race with a second approval, and it rolls the claim back so the row is
     *   still PENDING when the owner re-reads it.
     */
    fun approve(
        id: Long,
        request: MemoryDraftApproveRequest,
    ): MemoryDraftDecisionResponse

    /**
     * Closes a candidate with a reason. Touches neither the long-term layer nor the conversation's own files.
     *
     * The rejected conversation keeps its layer, so its next merge window proposes the same material again;
     * the reason is what the owner sees when it does.
     *
     * @throws com.agnetix.harnax.admin.exception.BizException when the reason is blank or too long for the
     *   column, or when the id is unknown or not the caller's own
     */
    fun reject(
        id: Long,
        request: MemoryDraftRejectRequest,
    ): MemoryDraftDecisionResponse
}

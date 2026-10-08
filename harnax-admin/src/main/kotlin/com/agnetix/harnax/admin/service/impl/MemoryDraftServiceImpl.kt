package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.MemoryDraftApproveRequest
import com.agnetix.harnax.admin.dto.MemoryDraftDecisionResponse
import com.agnetix.harnax.admin.dto.MemoryDraftDetailResponse
import com.agnetix.harnax.admin.dto.MemoryDraftRejectRequest
import com.agnetix.harnax.admin.dto.MemoryDraftResponse
import com.agnetix.harnax.admin.dto.MemoryDraftSource
import com.agnetix.harnax.admin.dto.MemoryDraftSubmitRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.mapRecords
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.security.SecurityUtils
import com.agnetix.harnax.admin.service.MemoryDraftService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.McpSessionOwnerResolver
import com.agnetix.harnax.admin.util.MemoryDraftCodec
import com.agnetix.harnax.admin.util.MemoryObjectKeys
import com.agnetix.harnax.admin.util.TenantResolver
import com.agnetix.harnax.common.session.TaskSessionId
import com.agnetix.harnax.entity.MemoryDraft
import com.agnetix.harnax.mapper.AgentMapper
import com.agnetix.harnax.mapper.MemoryDraftMapper
import com.agnetix.harnax.mapper.SessionMapper
import com.github.pagehelper.PageHelper
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The memory merge queue, both halves: intake from a runtime, decisions from the person the memory is about.
 *
 * The two halves refuse in different currencies, as the skill queue does. Every intake refusal is a
 * `BizException` naming the offending value, because the caller is a merge sitting inside a live conversation
 * and its only move is to leave the conversation's layer alone and report why nothing was queued. A decision
 * refusal is an outcome on a normal response, because the screen has to act on it — re-read a candidate that
 * moved, show who decided first, tell the owner their layer is no longer the one the merge read.
 *
 * What both hold to is that an owner's long-term `MEMORY.md` changes only here, and only for the person whose
 * token is on the request. [currentOwner] is therefore stricter than it looks: the row is scoped by
 * `sys_user.id`, not just by workspace, because a memory merge is somebody's own recollection and a workspace
 * admin has no business reading or signing it.
 */
@Service
class MemoryDraftServiceImpl(
    private val memoryDraftMapper: MemoryDraftMapper,
    private val sessionMapper: SessionMapper,
    private val agentMapper: AgentMapper,
    private val mcpSessionOwnerResolver: McpSessionOwnerResolver,
    private val memoryStoreGateway: MemoryStoreGateway,
    private val jwtUtil: JwtUtil,
) : MemoryDraftService {

    private val log = LoggerFactory.getLogger(MemoryDraftServiceImpl::class.java)

    override fun submit(request: MemoryDraftSubmitRequest): Long {
        val sessionId = request.sessionId?.trim().orEmpty()
        if (sessionId.isEmpty()) throw BizException("sessionId is required")
        // The two bucket segments are checked by the same predicates the read path and the clear use, so a
        // proposal cannot queue a layer no approval can address.
        if (!MemoryObjectKeys.isValidSessionId(sessionId)) {
            throw BizException("session id '$sessionId' cannot address a memory layer")
        }
        val agentName = request.agentName?.trim().orEmpty()
        if (!MemoryObjectKeys.isValidAgentId(agentName)) {
            throw BizException("agent name '$agentName' cannot address a memory layer")
        }
        val merged = request.mergedMarkdown.orEmpty()
        if (merged.isBlank()) throw BizException("mergedMarkdown is required: a candidate with no text has nothing to approve")
        requireBytes("mergedMarkdown", merged, MAX_MERGED_BYTES)
        val base = request.baseMarkdown?.takeIf { it.isNotBlank() }
        if (base != null) requireBytes("baseMarkdown", base, MAX_MERGED_BYTES)
        if (request.baseVersion < 0L) {
            throw BizException("baseVersion ${request.baseVersion} is not a store version")
        }
        val sources = validatedSources(request.sources)

        // No tenant and no owner off the request: a merge filed in somebody else's queue would be approved by
        // the wrong person and written into the wrong bucket, and neither is visible to them.
        val owner = resolveOwner(sessionId)
            ?: throw BizException(404, "session '$sessionId' resolves to no owner and agent, so its merge has no long-term layer to join")
        val agent = agentMapper.selectById(owner.agentId)
            ?: throw BizException(404, "session '$sessionId' ran no agent this service can read, so its merge has no long-term layer to join")
        // The bucket segment is the agent's *name*, so a proposal naming a different agent describes a layer
        // its conversation never wrote — and approving it would put this conversation's text into another
        // agent's memory. The id the session ran is the only answer that can be checked against it.
        if (agent.name != agentName) {
            throw BizException(
                "session '$sessionId' runs agent '${agent.name}', not '$agentName', so this merge is not about the layer its conversation wrote",
            )
        }
        if (agent.tenantId != owner.tenantId) {
            throw BizException(
                "session '$sessionId' belongs to tenant ${owner.tenantId} while agent '$agentName' belongs to ${agent.tenantId}, so this merge has no workspace to enter",
            )
        }
        val userId = MemoryObjectKeys.userSegment(owner.userId)

        // Resolved through the store's own key builder rather than a pattern this class would keep: intake and
        // the clear must not be able to disagree about what counts as a file a merge read.
        for (source in sources) {
            val path = source.path.orEmpty()
            if (memoryStoreGateway.sessionSourceKey(owner.tenantId, userId, agentName, sessionId, path) == null) {
                throw BizException("source '$path' of conversation '$sessionId' is not a file a merge could have read")
            }
        }

        val draft = MemoryDraft()
        draft.tenantId = owner.tenantId
        draft.userId = owner.userId
        draft.agentName = agentName
        draft.sessionId = sessionId
        draft.mergedMd = merged
        draft.baseMd = base
        draft.baseVersion = request.baseVersion
        draft.sources = MemoryDraftCodec.sourcesJson(sources)
        draft.status = MemoryDraft.STATUS_PENDING

        // One open candidate per conversation: the newer merge subsumes the older one, because it read the
        // same layer plus whatever the turns since then wrote.
        val open = memoryDraftMapper.selectPendingBySession(owner.tenantId, sessionId)
        val rewritten = open?.id?.let { targetId ->
            draft.id = targetId
            if (memoryDraftMapper.updateContent(draft) == 1) targetId else null
        }
        // 0 from that UPDATE means the owner decided the candidate between the read and the write. The merge
        // still stands and nobody has read it, so it becomes a fresh row and the decided one stays as the
        // record of that decision.
        val draftId = rewritten ?: run {
            draft.id = 0
            memoryDraftMapper.insert(draft)
            draft.id
        }
        log.info(
            "[memory] Merge for agent '{}' of conversation {} filed for user {} in tenant {} ({})",
            agentName,
            sessionId,
            owner.userId,
            owner.tenantId,
            if (rewritten != null) "rewrote open candidate $draftId" else "queued as candidate $draftId",
        )
        return draftId
    }

    /** The caller's own candidates, newest touched first. */
    override fun page(
        status: String?,
        agentName: String?,
        sessionId: String?,
        pageNum: Int,
        pageSize: Int,
    ): Page<MemoryDraftResponse> {
        val caller = currentOwner()
        val state = status?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        // Refused rather than passed through: an unknown status would answer with an empty queue and read as
        // "nothing waiting" to the one person whose merge is in it.
        if (state != null && state !in STATUSES) {
            throw BizException("status '$status' is not one of ${STATUSES.joinToString("/")}")
        }
        val safePageNum = pageNum.coerceAtLeast(1)
        val safePageSize = pageSize.coerceIn(1, MAX_PAGE_SIZE)
        PageHelper.startPage<MemoryDraft>(safePageNum, safePageSize)
        return Page.fromPageInfo(
            memoryDraftMapper.selectDraftList(
                tenantId = caller.tenantId,
                userId = caller.userId,
                status = state,
                agentName = agentName?.trim()?.takeIf { it.isNotEmpty() },
                sessionId = sessionId?.trim()?.takeIf { it.isNotEmpty() },
            ),
        ).mapRecords { it.toResponse() }
    }

    override fun detail(id: Long): MemoryDraftDetailResponse {
        val caller = currentOwner()
        return requireDraft(id, caller).toDetail()
    }

    /**
     * The gate order below is the whole design of this method, and it is the store read that makes it
     * different from a skill approval.
     *
     * A skill's approval can check everything it needs against the draft row; this one has to ask the memory
     * bucket whether it still holds the text the merge read, because that is what the approval overwrites.
     * So the three answers that need no write — digest, layer version, reachability — all come before the
     * claim, and the claim comes before the store. [MemoryStoreGateway.writeCuratedIfVersion] then makes the
     * write conditional again, this time against the object's own ETag, because two owners of the same bucket
     * deciding at once is a race no row in this table can see.
     *
     * The clear runs last and only after a write that landed. A clear that went first would drop a
     * conversation's memory on the strength of a candidate that then failed to apply; a clear that fails after
     * the write leaves text the owner did approve in place, the claim rolls back with it, and the next attempt
     * finds the layer already holding this exact text and finishes the job instead of writing it twice.
     */
    @Transactional(rollbackFor = [Exception::class])
    override fun approve(
        id: Long,
        request: MemoryDraftApproveRequest,
    ): MemoryDraftDecisionResponse {
        val caller = currentOwner()
        val expected = request.expectedDigest?.trim().orEmpty()
        if (expected.isEmpty()) {
            throw BizException("expectedDigest is required: it is the only thing saying which text this approval covers")
        }
        val draft = requireDraft(id, caller)
        if (draft.status != MemoryDraft.STATUS_PENDING) return alreadyReviewed(draft)
        val digest = MemoryDraftCodec.contentDigest(draft)
        if (expected != digest) return MemoryDraftDecisionResponse.draftChanged(digest)
        if (!memoryStoreGateway.isAvailable()) {
            throw BizException(503, "no memory store is reachable, so this approval cannot write the long-term layer")
        }

        val userId = MemoryObjectKeys.userSegment(draft.userId)
        val sources = MemoryDraftCodec.sourcesOf(draft)
        val layer = memoryStoreGateway.readCuratedLayer(draft.tenantId, userId, draft.agentName)
        val apply = layer.version == draft.baseVersion
        // The layer holds this very text at a version the candidate was not merged against: a previous attempt
        // of this approval wrote it and then lost the clear. Approving again completes it rather than writing
        // the same bytes a second time.
        val alreadyLanded = !apply && layer.content == draft.mergedMd
        if (!apply && !alreadyLanded) return MemoryDraftDecisionResponse.staleBase(layer.version)

        if (memoryDraftMapper.markReviewed(id, MemoryDraft.STATUS_APPROVED, caller.username) == 0) {
            // Re-read for the answer: the row now carries the decision that beat this one, including who made it.
            return alreadyReviewed(memoryDraftMapper.selectById(id) ?: draft)
        }

        if (apply && !memoryStoreGateway.writeCuratedIfVersion(draft.tenantId, userId, draft.agentName, draft.baseVersion, draft.mergedMd)) {
            // The layer moved between the read above and this conditional write — a second approval that won.
            // The claim goes back down with the transaction, so what the owner re-reads is a PENDING candidate.
            throw BizException(409, "the long-term layer changed while this approval ran; the candidate is still waiting, re-read it")
        }
        val cleared = memoryStoreGateway.clearSessionSources(draft.tenantId, userId, draft.agentName, draft.sessionId, sources)
        val longTermVersion = if (apply) draft.baseVersion + 1 else layer.version
        log.info(
            "[memory] Candidate {} for agent '{}' of conversation {} approved by {}: long-term layer at version {}, sources cleared {} kept {} absent {}",
            id,
            draft.agentName,
            draft.sessionId,
            caller.username,
            longTermVersion,
            cleared.cleared,
            cleared.kept,
            cleared.absent,
        )
        return if (apply) {
            MemoryDraftDecisionResponse.approved(longTermVersion, cleared.cleared, cleared.kept, cleared.absent)
        } else {
            MemoryDraftDecisionResponse.alreadyApplied(longTermVersion, cleared.cleared, cleared.kept, cleared.absent)
        }
    }

    /**
     * Closes a candidate with a reason, on the same conditional claim an approval uses, and with the same
     * consequence spelled out for the owner: the conversation keeps its own layer, so this material comes
     * back next merge window.
     */
    @Transactional(rollbackFor = [Exception::class])
    override fun reject(
        id: Long,
        request: MemoryDraftRejectRequest,
    ): MemoryDraftDecisionResponse {
        val caller = currentOwner()
        val reason = request.reason?.trim().orEmpty()
        if (reason.isEmpty()) throw BizException("a rejection needs a reason; REJECTED on its own tells the owner nothing when the same merge comes back")
        if (reason.length > MAX_REJECT_REASON_CHARS) {
            throw BizException("the reason is ${reason.length} characters, over the $MAX_REJECT_REASON_CHARS the queue stores")
        }
        val draft = requireDraft(id, caller)
        if (draft.status != MemoryDraft.STATUS_PENDING) return alreadyReviewed(draft)
        if (memoryDraftMapper.markReviewed(id, MemoryDraft.STATUS_REJECTED, caller.username, reason) == 0) {
            return alreadyReviewed(memoryDraftMapper.selectById(id) ?: draft)
        }
        log.info(
            "[memory] Candidate {} for agent '{}' of conversation {} rejected by {}: {}",
            id,
            draft.agentName,
            draft.sessionId,
            caller.username,
            reason,
        )
        return MemoryDraftDecisionResponse.rejected(reason)
    }

    /** Bytes of [value] within [limit], or a refusal naming the field and both numbers. */
    private fun requireBytes(
        field: String,
        value: String,
        limit: Int,
    ) {
        val bytes = value.toByteArray(Charsets.UTF_8).size
        if (bytes > limit) {
            throw BizException("$field is $bytes bytes, over the $limit the queue stores")
        }
    }

    /**
     * The source files as they may be stored: at least one, none over the ceiling, every path present.
     *
     * An empty list is refused rather than stored empty, because a candidate that names no source is a
     * candidate no approval can finish — the owner would approve a merge whose material is still sitting in
     * the conversation, and the next window would propose the same text again with nothing cleared. Whether a
     * path is a file a merge could have read is answered afterwards, by the store's own key builder.
     */
    private fun validatedSources(sources: List<MemoryDraftSource>?): List<MemoryDraftSource> {
        if (sources.isNullOrEmpty()) throw BizException("sources are required: a merge that names no conversation file cleared nothing on approval")
        if (sources.size > MAX_SOURCES) {
            throw BizException("the merge names ${sources.size} source files, over the $MAX_SOURCES the queue stores")
        }
        val trimmed = sources.map { source ->
            val path = source.path?.trim().orEmpty()
            if (path.isEmpty()) throw BizException("a source file carries no path")
            MemoryDraftSource(path = path, content = source.content)
        }
        val total = trimmed.sumOf { it.content?.toByteArray(Charsets.UTF_8)?.size ?: 0 }
        if (total > MAX_SOURCES_BYTES) {
            throw BizException("the source files total $total bytes, over the $MAX_SOURCES_BYTES the queue stores")
        }
        return trimmed
    }

    /**
     * The candidate this caller is about to act on.
     *
     * Unknown, another workspace's, and another person's all answer the same way. They are three different
     * facts, but only to the owner of the row: telling anybody else which of the three it was would confirm
     * that a conversation of theirs proposed a merge, which is what the queue exists to keep private.
     */
    private fun requireDraft(
        id: Long,
        caller: Owner,
    ): MemoryDraft {
        val draft = memoryDraftMapper.selectById(id)
        if (draft == null || draft.tenantId != caller.tenantId || draft.userId != caller.userId) {
            throw BizException(404, "candidate $id does not exist in this workspace and account")
        }
        return draft
    }

    private fun alreadyReviewed(draft: MemoryDraft) = MemoryDraftDecisionResponse.alreadyReviewed(
        agentName = draft.agentName,
        reviewedBy = draft.reviewedBy,
        reviewedAt = draft.reviewedAt,
        rejectReason = draft.rejectReason,
    )

    /**
     * Whose long-term layer a merge joins, which conversation wrote it, and which agent the runtime mounted
     * it under — all of it read back from the session id, which is the only identity a runtime holds.
     *
     * Three shapes, because a layer hangs off the conversation that wrote it. A web or mini-program run has a
     * `session` row whose creator is the person and whose `agent_id` is the agent. A scheduled run's task row
     * left this database with the scheduled-task domain, so [McpSessionOwnerResolver] reads its owner from
     * the scheduler over HTTP and the agent comes out of contract C1's id. A team member's conversation is an
     * id of this system's own making — `team-<root>-m<agentId>`, the shape `TeamRuntimeSpec.prefix` builds —
     * whose owner is the root session's and whose agent is the member the tail names; it is unwrapped here
     * rather than by widening a resolver whose own domain has settled what it answers.
     *
     * `chn-` answers nobody. A channel conversation's `creator` is a sender id from the chat platform, not a
     * `sys_user`, so there is no person whose long-term layer this could join and no queue to file it in —
     * which is also what the runtime's own owner rule gives that deployment.
     */
    private fun resolveOwner(sessionId: String): Owner? {
        val child = CHILD_SESSION.matchEntire(sessionId)
        val layerSessionId = child?.groupValues?.get(1) ?: sessionId
        val resolved = mcpSessionOwnerResolver.resolve(layerSessionId) ?: return null
        val agentId = child?.groupValues?.get(2)?.toLongOrNull()
            ?: sessionMapper.selectBySessionIdAndStatus(layerSessionId, ACTIVE_SESSION_STATUS)?.agentId
            ?: TaskSessionId.parse(layerSessionId)?.agentId
            ?: return null
        return Owner(userId = resolved.userId, tenantId = resolved.tenantId, agentId = agentId)
    }

    /**
     * The person on the other side of a decision, and the namespace their memory lives in.
     *
     * The tenant is resolved the way [MemoryServiceImpl] resolves it, not the stricter way the skill queue
     * does: the memory page and this queue show and change the same bucket, so the two must answer with one
     * rule about which workspace a caller is acting in — and the guard that actually keeps another person's
     * candidate out of reach here is [requireDraft]'s `userId` comparison, which is exact.
     */
    private fun currentOwner(): Owner {
        val authentication = SecurityContextHolder.getContext()?.authentication
        if (authentication?.principal == INTERNAL_SERVICE_PRINCIPAL) {
            throw BizException(401, "Memory belongs to a logged-in user, not to a service call")
        }
        val user = SecurityUtils.getCurrentUser() ?: throw BizException(401, "User not logged in")
        if (user.id <= 0) throw BizException(401, "User not logged in")
        return Owner(
            username = user.username ?: "",
            tenantId = TenantResolver.resolve(jwtUtil),
            userId = user.id,
        )
    }

    private fun MemoryDraft.toResponse() = MemoryDraftResponse(
        id = id,
        agentName = agentName,
        sessionId = sessionId,
        status = status,
        baseVersion = baseVersion,
        mergedChars = mergedMd.length,
        sourceCount = MemoryDraftCodec.sourcesOf(this).size,
        createTime = createTime,
        updateTime = updateTime,
        reviewedBy = reviewedBy,
        reviewedAt = reviewedAt,
        rejectReason = rejectReason,
    )

    private fun MemoryDraft.toDetail() = MemoryDraftDetailResponse(
        id = id,
        agentName = agentName,
        sessionId = sessionId,
        status = status,
        mergedMd = mergedMd,
        baseMd = baseMd,
        baseVersion = baseVersion,
        sources = MemoryDraftCodec.sourcesOf(this),
        contentDigest = MemoryDraftCodec.contentDigest(this),
        createTime = createTime,
        updateTime = updateTime,
        reviewedBy = reviewedBy,
        reviewedAt = reviewedAt,
        rejectReason = rejectReason,
    )

    /** Whose memory, in which workspace, under which agent row. */
    private data class Owner(
        val username: String = "",
        val tenantId: Long = 0,
        val userId: Long = 0,
        val agentId: Long = 0,
    )

    companion object {
        /**
         * Ceiling on one candidate's merged text. Far above the writer's own budget for `MEMORY.md` (4000
         * tokens, which the promotion prompt states and the injection cuts at), so a layer that has drifted
         * past its budget is still approvable — refusing it would strand the conversation's memory with no
         * queue able to say why.
         */
        private const val MAX_MERGED_BYTES = 200_000

        /** Ceiling on the files one merge names. A conversation's layer is its draft plus one ledger per day. */
        private const val MAX_SOURCES = 64

        /** Ceiling on those files' text, same reasoning as [MAX_MERGED_BYTES]: a bound, not a design limit. */
        private const val MAX_SOURCES_BYTES = 2_000_000

        /** Width of `memory_draft.reject_reason`. */
        private const val MAX_REJECT_REASON_CHARS = 512

        private val STATUSES = setOf(MemoryDraft.STATUS_PENDING, MemoryDraft.STATUS_APPROVED, MemoryDraft.STATUS_REJECTED)

        /** Same ceiling `SkillServiceImpl.page` puts on a page size. */
        private const val MAX_PAGE_SIZE = 1000

        private const val ACTIVE_SESSION_STATUS = 1

        /** A team member's conversation, as `TeamRuntimeSpec.prefix(root)` plus the member agent id. */
        private val CHILD_SESSION = Regex("^team-(.+)-m([0-9]+)$")

        /** What `JwtAuthenticationFilter` sets for a shared-secret service call; see [UserContextUtil]. */
        private const val INTERNAL_SERVICE_PRINCIPAL = "internal-service"
    }
}

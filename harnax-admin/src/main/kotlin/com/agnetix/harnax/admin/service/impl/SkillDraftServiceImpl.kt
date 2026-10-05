package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.ReviewHistoryItem
import com.agnetix.harnax.admin.dto.SkillDraftApproveRequest
import com.agnetix.harnax.admin.dto.SkillDraftDecisionResponse
import com.agnetix.harnax.admin.dto.SkillDraftDetailResponse
import com.agnetix.harnax.admin.dto.SkillDraftRejectRequest
import com.agnetix.harnax.admin.dto.SkillDraftResponse
import com.agnetix.harnax.admin.dto.SkillDraftSubmitRequest
import com.agnetix.harnax.admin.dto.mapRecords
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.SkillDraftService
import com.agnetix.harnax.admin.skill.SkillContentScanner
import com.agnetix.harnax.admin.skill.SkillDraftCodec
import com.agnetix.harnax.admin.skill.SkillDraftPromoter
import com.agnetix.harnax.admin.skill.SkillInstaller
import com.agnetix.harnax.admin.skill.SkillReviewRecorder
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.TenantResolver
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.common.session.TaskSessionId
import com.agnetix.harnax.entity.SkillDraft
import com.agnetix.harnax.entity.SkillReviewLog
import com.agnetix.harnax.mapper.AgentMapper
import com.agnetix.harnax.mapper.ChannelMapper
import com.agnetix.harnax.mapper.SessionMapper
import com.agnetix.harnax.mapper.SkillDraftMapper
import com.agnetix.harnax.mapper.SkillMapper
import com.github.pagehelper.PageHelper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

/**
 * The agent-proposal queue, both halves: intake from a runtime, decisions from a reviewer.
 *
 * The two refuse differently on purpose. Every intake refusal is a `BizException` with the offending value
 * named, because the caller is a promotion gate sitting inside a live inference and its only move is to tell
 * the model why nothing was queued — there is no retry worth having on a proposal that cannot be attributed,
 * or whose name does not fit the column. A review refusal is an answer the screen has to act on, so a changed
 * digest, a draft somebody already decided and a name that is taken come back as outcomes on a normal
 * response, with the information that lets the reviewer do something about them.
 *
 * What both halves hold to is that nothing reaches the `skill` table except through an approval here.
 */
@Service
class SkillDraftServiceImpl(
    private val skillDraftMapper: SkillDraftMapper,
    private val sessionMapper: SessionMapper,
    private val channelMapper: ChannelMapper,
    private val agentMapper: AgentMapper,
    private val skillReviewRecorder: SkillReviewRecorder,
    private val skillMapper: SkillMapper,
    private val skillDraftPromoter: SkillDraftPromoter,
    private val jwtUtil: JwtUtil,
) : SkillDraftService {

    private val log = LoggerFactory.getLogger(SkillDraftServiceImpl::class.java)
    private val objectMapper = ObjectMapper()

    override fun submit(request: SkillDraftSubmitRequest): Long {
        val sessionId = request.sessionId?.trim().orEmpty()
        if (sessionId.isEmpty()) throw BizException("sessionId is required")
        val name = request.name?.trim().orEmpty()
        if (name.isEmpty()) throw BizException("name is required")
        if (name.length > MAX_NAME_LENGTH) {
            throw BizException("skill name '$name' is longer than the $MAX_NAME_LENGTH characters the queue stores")
        }
        val skillmd = request.skillmd.orEmpty()
        if (skillmd.isBlank()) throw BizException("skillmd is required: a draft with no body has nothing to review")
        requireBytes("skillmd", skillmd, MAX_SKILLMD_BYTES)
        val description = request.description?.trim()?.takeIf { it.isNotEmpty() }
        if (description != null && description.length > MAX_DESCRIPTION_CHARS) {
            throw BizException("description is ${description.length} characters, over the $MAX_DESCRIPTION_CHARS the queue stores")
        }
        val files = validatedResources(request.resources)

        // No fallback to a default workspace, and no tenant read off the request: a proposal filed under the
        // wrong tenant is somebody else's review queue showing them a skill they never ran.
        val owner = resolveOwner(sessionId)?.takeIf { it.tenantId > 0 }
            ?: throw BizException(404, "session '$sessionId' resolves to no tenant, so its proposal has no queue to enter")

        val draft = SkillDraft().apply {
            tenantId = owner.tenantId
            this.name = name
            this.description = description
            this.skillmd = skillmd
            // Stored as canonical JSON so the digest a reviewer confirmed covers the bytes that were kept
            this.resources = SkillDraftCodec.resourcesJson(files)
            scriptPreviews = SkillDraftCodec.scriptPreviewsJson(files)
            scanVerdict = request.scanVerdict?.trim()?.uppercase()?.takeIf { it in SCAN_VERDICTS }
            scanFindings = SkillDraftCodec.findingsJson(request.scanFindings)
            sourceSessionId = sessionId
            agentId = owner.agentId.takeIf { it > 0L }
        }

        val open = skillDraftMapper.selectPendingByTenantAndName(owner.tenantId, name)
        val patched = if (open != null) {
            draft.id = open.id
            skillDraftMapper.updateContent(draft)
        } else {
            0
        }
        // 0 from that UPDATE means the draft was decided between the read and the write. The proposal still
        // stands — the agent is allowed to re-offer what a reviewer has not seen yet — so it becomes a fresh
        // row instead, and the decided one stays as the record of that decision.
        val merged = patched == 1
        if (!merged) {
            draft.id = 0
            skillDraftMapper.insert(draft)
        }
        val draftId = if (merged) open!!.id else draft.id

        skillReviewRecorder.recordDraft(
            draftId = draftId,
            action = SkillReviewLog.ACTION_PROPOSE,
            detail = objectMapper.writeValueAsString(
                mapOf(
                    "sessionId" to sessionId,
                    "merged" to merged,
                    "files" to files.size,
                    "scanVerdict" to draft.scanVerdict,
                    "agentId" to draft.agentId,
                ),
            ),
            tenantId = owner.tenantId,
            actor = SkillReviewLog.ACTOR_AGENT,
        )
        log.info(
            "Skill draft {} for tenant {} from session {} ({})",
            name,
            owner.tenantId,
            sessionId,
            if (merged) "merged into open draft $draftId" else "queued as draft $draftId",
        )
        return draftId
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
     * The support files as they may be stored.
     *
     * A path is checked rather than normalised because this content is later written back out as a skill
     * directory: `../` or an absolute path would let a proposal place a file outside its own skill folder.
     */
    private fun validatedResources(resources: Map<String, String>?): Map<String, String> {
        if (resources.isNullOrEmpty()) return emptyMap()
        if (resources.size > MAX_RESOURCE_FILES) {
            throw BizException("the proposal carries ${resources.size} files, over the $MAX_RESOURCE_FILES the queue stores")
        }
        var total = 0
        for ((path, content) in resources) {
            val trimmed = path.trim()
            if (trimmed.isEmpty()) throw BizException("a support file has no path")
            if (trimmed.startsWith("/")) throw BizException("support file '$path' uses an absolute path")
            if (trimmed.contains("..")) throw BizException("support file '$path' reaches outside the skill directory")
            if (trimmed.length > MAX_RESOURCE_PATH_CHARS) {
                throw BizException("support file path is ${trimmed.length} characters, over the $MAX_RESOURCE_PATH_CHARS allowed")
            }
            total += content.toByteArray(Charsets.UTF_8).size
        }
        if (total > MAX_RESOURCES_BYTES) {
            throw BizException("the proposal's files total $total bytes, over the $MAX_RESOURCES_BYTES the queue stores")
        }
        return resources.entries
            .map { (path, content) -> path.trim() to content }
            .toMap()
    }

    /** The queue within this tenant, newest touched first. */
    override fun page(
        status: String?,
        name: String?,
        pageNum: Int,
        pageSize: Int,
    ): Page<SkillDraftResponse> {
        val state = status?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        // Refused rather than passed through: an unknown status would answer with an empty queue and read as
        // "nothing to review" to the one person whose job is to notice that it is not.
        if (state != null && state !in STATUSES) {
            throw BizException("status '$status' is not one of ${STATUSES.joinToString("/")}")
        }
        val safePageNum = pageNum.coerceAtLeast(1)
        val safePageSize = pageSize.coerceIn(1, MAX_PAGE_SIZE)
        PageHelper.startPage<SkillDraft>(safePageNum, safePageSize)
        return Page.fromPageInfo(
            skillDraftMapper.selectDraftList(currentTenantId(), state, name?.trim()?.takeIf { it.isNotEmpty() }),
        ).mapRecords { it.toResponse() }
    }

    override fun detail(id: Long): SkillDraftDetailResponse {
        val draft = requireDraft(id)
        val resources = SkillDraftCodec.resourcesOf(draft)
        val findings = SkillContentScanner.scan(draft.skillmd, resources)
        return SkillDraftDetailResponse(
            id = draft.id,
            name = draft.name,
            description = draft.description,
            status = draft.status,
            skillmd = draft.skillmd,
            resources = resources,
            scripts = SkillDraftCodec.previewsOf(draft),
            scanVerdict = draft.scanVerdict,
            scanFindings = SkillDraftCodec.findingsOf(draft),
            localFindings = findings.map { "${it.resource}: ${it.reason}" },
            contentDigest = SkillDraftCodec.contentDigest(draft),
            sourceSessionId = draft.sourceSessionId,
            agentId = draft.agentId,
            createTime = draft.createTime,
            updateTime = draft.updateTime,
            reviewedBy = draft.reviewedBy,
            reviewedAt = draft.reviewedAt,
            rejectReason = draft.rejectReason,
            history = skillReviewRecorder
                .history(SkillReviewLog.SUBJECT_DRAFT, draft.id, draft.tenantId)
                .map { ReviewHistoryItem(action = it.action, actor = it.actor, detail = it.detail, createTime = it.createTime) },
        )
    }

    /**
     * The order of the four gates below is the whole design of this method.
     *
     * Everything that can be answered without touching a row comes first — tenant, decision, digest, name —
     * because a refusal that has already written would leave a half-applied approval behind. The claim is
     * last and is the only thing that can lose a race, and the promotion is the only thing after it. All of
     * it is one transaction, so a promotion that dies on the unique index takes the claim down with it and
     * the reviewer is left with a PENDING draft rather than an approved row that points at nothing.
     */
    @Transactional(rollbackFor = [Exception::class])
    override fun approve(
        id: Long,
        request: SkillDraftApproveRequest,
    ): SkillDraftDecisionResponse {
        val reviewer = UserContextUtil.getCurrentUsername(jwtUtil)
        val expected = request.expectedDigest?.trim().orEmpty()
        if (expected.isEmpty()) {
            throw BizException("expectedDigest is required: it is the only thing saying which content this approval covers")
        }
        val draft = requireDraft(id)
        if (draft.status != SkillDraft.STATUS_PENDING) return alreadyReviewed(draft)

        val digest = SkillDraftCodec.contentDigest(draft)
        if (expected != digest) return SkillDraftDecisionResponse.draftChanged(digest)

        val resolution = request.conflictResolution?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (resolution != null && resolution !in CONFLICT_RESOLUTIONS) {
            throw BizException("conflictResolution '$resolution' is not one of ${CONFLICT_RESOLUTIONS.joinToString("/")}")
        }
        val targetName = if (resolution == CONFLICT_RENAME) requireRenamedName(request.newName, draft.name) else draft.name
        val repository = skillDraftPromoter.landingRepository(draft.tenantId)

        // Both conflict answers are the same refusal with a different name in it. A rename onto a second
        // taken name comes back as a refusal rather than a suffix nobody asked for, and no resolution at all
        // never overwrites somebody else's row by default.
        val taken = skillMapper.selectByNameAndRepo(targetName, repository.id)
        if (taken != null && resolution != CONFLICT_REPLACE) {
            return SkillDraftDecisionResponse.nameTaken(targetName, taken.id)
        }

        if (skillDraftMapper.markReviewed(id, SkillDraft.STATUS_APPROVED, reviewer) == 0) {
            // Re-read for the answer: the row now carries the decision that beat this one, including who made it
            return alreadyReviewed(skillDraftMapper.selectById(id) ?: draft)
        }

        val promotion = skillDraftPromoter.promote(draft, repository, targetName, reviewer)
        // Two rows, because two people will look for this: the queue wants to know what became of the
        // proposal, and the skill wants to know who agreed to have it. The rescan findings live here rather
        // than in the draft's scan_findings column, which is the sandbox's own answer and stays that way.
        skillReviewRecorder.recordDraft(
            draftId = id,
            action = SkillReviewLog.ACTION_APPROVE,
            detail = objectMapper.writeValueAsString(
                mapOf(
                    "skillId" to promotion.skillId,
                    "name" to promotion.name,
                    "status" to promotion.status,
                    "repositoryId" to repository.id,
                    "findings" to promotion.findings,
                    "sessionId" to draft.sourceSessionId,
                    "digest" to digest,
                ),
            ),
            tenantId = draft.tenantId,
            actor = reviewer,
        )
        skillReviewRecorder.recordSkill(
            skillId = promotion.skillId,
            action = SkillReviewLog.ACTION_APPROVE,
            detail = objectMapper.writeValueAsString(
                mapOf(
                    "draftId" to id,
                    "name" to promotion.name,
                    "status" to promotion.status,
                    "findings" to promotion.findings,
                    "sessionId" to draft.sourceSessionId,
                    "agentId" to draft.agentId,
                ),
            ),
            tenantId = draft.tenantId,
            actor = reviewer,
        )
        log.info(
            "Draft {} of skill {} approved by {} as skill {} (status {})",
            id,
            draft.name,
            reviewer,
            promotion.skillId,
            promotion.status,
        )
        return SkillDraftDecisionResponse.promoted(
            skillId = promotion.skillId,
            skillStatus = promotion.status,
            promotedName = promotion.name,
            findings = promotion.findings,
        )
    }

    /**
     * Closes a proposal with a reason, on the same conditional claim an approval uses.
     *
     * A rejection is the one decision that has no second step, so it is also the one that must not be
     * reachable without a reason: an agent re-offering the same skill learns nothing from `REJECTED` alone,
     * and a queue of refused rows nobody explained is a queue nobody can audit.
     */
    @Transactional(rollbackFor = [Exception::class])
    override fun reject(
        id: Long,
        request: SkillDraftRejectRequest,
    ): SkillDraftDecisionResponse {
        val reviewer = UserContextUtil.getCurrentUsername(jwtUtil)
        val reason = request.reason?.trim().orEmpty()
        if (reason.isEmpty()) throw BizException("a rejection needs a reason; REJECTED on its own teaches the proposer nothing")
        if (reason.length > MAX_REJECT_REASON_CHARS) {
            throw BizException("the reason is ${reason.length} characters, over the $MAX_REJECT_REASON_CHARS the queue stores")
        }
        val draft = requireDraft(id)
        if (draft.status != SkillDraft.STATUS_PENDING) return alreadyReviewed(draft)
        if (skillDraftMapper.markReviewed(id, SkillDraft.STATUS_REJECTED, reviewer, reason) == 0) {
            return alreadyReviewed(skillDraftMapper.selectById(id) ?: draft)
        }
        skillReviewRecorder.recordDraft(
            draftId = id,
            action = SkillReviewLog.ACTION_REJECT,
            detail = objectMapper.writeValueAsString(
                mapOf("reason" to reason, "sessionId" to draft.sourceSessionId, "name" to draft.name),
            ),
            tenantId = draft.tenantId,
            actor = reviewer,
        )
        log.info("Draft {} of skill {} rejected by {}: {}", id, draft.name, reviewer, reason)
        return SkillDraftDecisionResponse.rejected(reason)
    }

    /**
     * The row a reviewer is about to act on, within their own tenant.
     *
     * Unknown and not-yours get one answer. They are different facts, but only to the tenant that owns the
     * draft: telling another workspace which of the two it hit would confirm that somebody else proposed a
     * skill by that id.
     */
    private fun requireDraft(id: Long): SkillDraft {
        val draft = skillDraftMapper.selectById(id)
        if (draft == null || draft.tenantId != currentTenantId()) {
            throw BizException(404, "draft $id does not exist in this workspace")
        }
        return draft
    }

    private fun alreadyReviewed(draft: SkillDraft) = SkillDraftDecisionResponse.alreadyReviewed(
        name = draft.name,
        reviewedBy = draft.reviewedBy,
        reviewedAt = draft.reviewedAt,
        rejectReason = draft.rejectReason,
    )

    /** The name a `rename` resolution asked for, checked against the column it will grow into. */
    private fun requireRenamedName(
        newName: String?,
        proposed: String,
    ): String {
        val trimmed = newName?.trim().orEmpty()
        if (trimmed.isEmpty()) throw BizException("renaming '$proposed' needs the name to rename it to")
        if (trimmed.length > MAX_NAME_LENGTH) {
            throw BizException("'$trimmed' is longer than the $MAX_NAME_LENGTH characters a skill name holds")
        }
        return trimmed
    }

    private fun currentTenantId(): Long = TenantResolver.resolve(jwtUtil)

    private fun SkillDraft.toResponse() = SkillDraftResponse(
        id = id,
        name = name,
        description = description,
        status = status,
        scanVerdict = scanVerdict,
        upstreamFindingCount = SkillDraftCodec.findingsOf(this).size,
        sourceSessionId = sourceSessionId,
        agentId = agentId,
        createTime = createTime,
        updateTime = updateTime,
        reviewedBy = reviewedBy,
        reviewedAt = reviewedAt,
        rejectReason = rejectReason,
    )

    /**
     * Whose tenant and which agent a session id belongs to, from the tables that recorded who ran it.
     *
     * The same three shapes the agent-spec resolution answers, because a proposal arrives on a runtime path
     * that has a session id and nothing else: a web/mini-program run has a `session` row, a channel
     * conversation has a `channel` row and no session row, and a scheduled-task run carries its agent id in
     * the id itself (contract C1). The session's tenant wins over the agent's, which is what makes a draft
     * land in the workspace that ran the session rather than the one that owns the agent.
     */
    private fun resolveOwner(sessionId: String): Owner? = when {
        sessionId.startsWith(WEB_PREFIX) || sessionId.startsWith(MP_PREFIX) ->
            sessionMapper.selectBySessionIdAndStatus(sessionId, ACTIVE_SESSION_STATUS)
                ?.let { Owner(tenantId = it.tenantId, agentId = it.agentId ?: 0L) }

        sessionId.startsWith(CHANNEL_PREFIX) ->
            channelMapper.selectOwnerBySessionId(sessionId)?.let { Owner(tenantId = it.tenantId, agentId = it.agentId) }

        sessionId.startsWith(TaskSessionId.PREFIX) ->
            TaskSessionId.parse(sessionId)?.let { Owner(tenantId = tenantOfAgent(it.agentId), agentId = it.agentId) }

        else -> null
    }

    private fun tenantOfAgent(agentId: Long): Long = agentMapper.selectById(agentId)?.tenantId ?: 0L

    /** A proposal needs a tenant to be reviewable by anybody; an agent is recorded when the id names one. */
    private data class Owner(
        val tenantId: Long,
        val agentId: Long,
    )

    companion object {
        /** Same ceiling [SkillInstaller] puts on a name it imports, so a draft fits the column it will grow into. */
        private const val MAX_NAME_LENGTH = SkillInstaller.MAX_SKILL_NAME_LENGTH

        private const val MAX_DESCRIPTION_CHARS = 4_000
        private const val MAX_SKILLMD_BYTES = 1_000_000
        private const val MAX_RESOURCE_FILES = 64
        private const val MAX_RESOURCE_PATH_CHARS = 255

        /**
         * Ceiling on one proposal's files. The gate has no rate limit and an agent can patch as often as it
         * likes, so a per-submit byte bound is the only thing between a runaway session and a queue nobody
         * can read — it is a bound on size, not on volume, which is the risk the design records as accepted.
         */
        private const val MAX_RESOURCES_BYTES = 2_000_000

        /** Upstream `SkillSecurityScanner.Verdict`, and the only values worth echoing back to a reviewer. */
        private val SCAN_VERDICTS = setOf("SAFE", "CAUTION", "DANGEROUS")

        /**
         * The statuses a queue filter may name.
         *
         * `EXPIRED` is in the column's documentation but not here, because nothing writes it — no expiry job
         * exists, so a reviewer filtering by it would be shown an empty list and told it was the whole queue.
         * Whoever adds expiry adds the status to this set.
         */
        private val STATUSES = setOf(SkillDraft.STATUS_PENDING, SkillDraft.STATUS_APPROVED, SkillDraft.STATUS_REJECTED)

        /** The two ways a reviewer resolves a taken name; there is no default, see [approve]. */
        private val CONFLICT_RESOLUTIONS = setOf(CONFLICT_REPLACE, CONFLICT_RENAME)

        private const val CONFLICT_REPLACE = "replace"

        private const val CONFLICT_RENAME = "rename"

        /** Width of `skill_draft.reject_reason`. */
        private const val MAX_REJECT_REASON_CHARS = 512

        /** Same ceiling `SkillServiceImpl.page` puts on a page size. */
        private const val MAX_PAGE_SIZE = 1000

        private const val WEB_PREFIX = "web-"
        private const val MP_PREFIX = "mp-"
        private const val CHANNEL_PREFIX = "chn-"
        private const val ACTIVE_SESSION_STATUS = 1
    }
}

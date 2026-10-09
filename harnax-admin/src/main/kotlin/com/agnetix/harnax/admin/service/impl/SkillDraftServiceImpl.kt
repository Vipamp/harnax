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
import com.agnetix.harnax.admin.service.SchedulerClient
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
import com.agnetix.harnax.mapper.ChannelMapper
import com.agnetix.harnax.mapper.SessionMapper
import com.agnetix.harnax.mapper.SkillDraftMapper
import com.agnetix.harnax.mapper.SkillMapper
import com.github.pagehelper.PageHelper
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
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
 * What both halves hold to is that nothing reaches the `skill` table except through an approval here, and
 * that an approval names a person: the review half answers only to a signed-in reviewer's own token and a
 * request that says which workspace it acts within, because the bearer a proposal arrives on is the shared
 * internal secret the proposing sandbox also holds — see [requireReviewer].
 */
@Service
class SkillDraftServiceImpl(
    private val skillDraftMapper: SkillDraftMapper,
    private val sessionMapper: SessionMapper,
    private val channelMapper: ChannelMapper,
    private val schedulerClient: SchedulerClient,
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
            scanFindings = SkillDraftCodec.findingsJson(validatedFindings(request.scanFindings))
            sourceSessionId = sessionId
            agentId = owner.agentId.takeIf { it > 0L }
        }

        // A turn-end scan offers everything its session has staged, so an untouched draft comes back on every
        // following turn. Answering with the row the reviewer already has is the only way to tell that re-offer
        // from a revision: the proposal itself carries no version, and the client's cooldown is per agent
        // instance, so it resets with the assembly cache that holds it.
        val unchanged = skillDraftMapper
            .selectLatestByTenantNameAndSession(owner.tenantId, name, sessionId)
            ?.takeIf { SkillDraftCodec.contentDigest(it) == SkillDraftCodec.contentDigest(draft) }
        if (unchanged != null) {
            log.info(
                "Skill draft {} for tenant {} from session {} repeats draft {}, leaving the queue untouched",
                name,
                owner.tenantId,
                sessionId,
                unchanged.id,
            )
            return unchanged.id
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

    /**
     * The scan notes as they may be stored.
     *
     * Advisory text — the authoritative findings are the ones the rescan inside an approval produces — but
     * it arrives over the same bearer as the proposal, and a `mediumtext` column is a ceiling on the row, not
     * a bound on what a caller may send. Same shape as [validatedResources]: refuse, name both numbers, let
     * the gate tell the model why nothing was queued.
     */
    private fun validatedFindings(findings: List<String>?): List<String> {
        val kept = findings?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (kept.size > MAX_SCAN_FINDINGS) {
            throw BizException("the scan reported ${kept.size} findings, over the $MAX_SCAN_FINDINGS the queue stores")
        }
        val total = kept.sumOf { it.toByteArray(Charsets.UTF_8).size }
        if (total > MAX_SCAN_FINDINGS_BYTES) {
            throw BizException("the scan findings total $total bytes, over the $MAX_SCAN_FINDINGS_BYTES the queue stores")
        }
        return kept
    }

    /** The queue within this tenant, newest touched first. */
    override fun page(
        status: String?,
        name: String?,
        sessionId: String?,
        pageNum: Int,
        pageSize: Int,
    ): Page<SkillDraftResponse> {
        requireReviewer()
        val state = status?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        // Refused rather than passed through: an unknown status would answer with an empty queue and read as
        // "nothing to review" to the one person whose job is to notice that it is not.
        if (state != null && state !in STATUSES) {
            throw BizException("status '$status' is not one of ${STATUSES.joinToString("/")}")
        }
        // Narrowed here, not on the way back: PageHelper counts the rows this query hands it, so a session
        // filter applied after paging would publish a total that does not match the page under it.
        //
        // Present-but-blank is not the same question as absent. A caller that names no session is the reviewer
        // reading their whole tenant's queue, which is what this page has always answered. A caller that names
        // one and sends only whitespace asked for a single conversation, and dropping the predicate here would
        // hand that panel another conversation's pending nominations (design D11/§6: the session panel shows
        // only what this session proposed). So the blank case answers empty, before `startPage` — a paging
        // request started with no query behind it would leak its thread-local into the next call. No new code,
        // no different status: an empty page is already a shape this endpoint speaks.
        if (sessionId != null && sessionId.isBlank()) {
            return Page<SkillDraftResponse>(
                pageNum = pageNum.coerceAtLeast(1).toLong(),
                pageSize = pageSize.coerceIn(1, MAX_PAGE_SIZE).toLong(),
            )
        }
        val session = sessionId?.trim()?.takeIf { it.isNotEmpty() }
        val safePageNum = pageNum.coerceAtLeast(1)
        val safePageSize = pageSize.coerceIn(1, MAX_PAGE_SIZE)
        PageHelper.startPage<SkillDraft>(safePageNum, safePageSize)
        return Page.fromPageInfo(
            skillDraftMapper.selectDraftList(
                currentTenantId(),
                state,
                name?.trim()?.takeIf { it.isNotEmpty() },
                session,
            ),
        ).mapRecords { it.toResponse() }
    }

    override fun detail(id: Long): SkillDraftDetailResponse {
        requireReviewer()
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
        val reviewer = requireReviewer()
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
        val reviewer = requireReviewer()
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

    /**
     * The person on the other side of this call, or a refusal.
     *
     * This queue is the whole reason L3 is a gate rather than an autocommit: nothing an agent wrote reaches
     * a runtime until a human says so. [com.agnetix.harnax.admin.config.JwtAuthenticationFilter] however
     * authenticates the shared internal secret as a principal on *every* path it covers except `/internal`,
     * and `application.yml` injects that same secret into each sandbox as `platform.internalToken` — the
     * bearer a proposed skill's own author holds. Accepting it here would let the proposer approve itself,
     * and `UserContextUtil` would even stamp the decision with the `SYSTEM` marker it answers for that
     * principal. So the marker is refused by name, and a caller with neither a marker nor a token has no
     * name to sign a decision with either.
     */
    private fun requireReviewer(): String {
        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication?.principal == INTERNAL_SERVICE_PRINCIPAL) {
            throw BizException(403, "the review queue answers to a signed-in reviewer, not to the internal service secret")
        }
        return UserContextUtil.getCurrentUsername(jwtUtil)
    }

    /**
     * The workspace whose queue this is.
     *
     * Strict where [TenantResolver] is deliberately lenient: its last step files an unattributed call under
     * tenant 1, which is right for a callback that has nobody to attribute it to and wrong for a decision
     * that will be read back as somebody's. No header, no claim, no account row therefore has no queue.
     */
    private fun currentTenantId(): Long = TenantResolver.resolveOrNull(jwtUtil)
        ?: throw BizException(403, "this request names no workspace, so it has no review queue to read or decide")

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
     * conversation has a `channel` row and no session row, and a scheduled run's row is the scheduler's, so
     * [fromTask] reads it over HTTP rather than taking the agent id the id carries on trust. The tenant of
     * the row that recorded the run is what a draft is filed under, never a tenant the caller names.
     */
    private fun resolveOwner(sessionId: String): Owner? = when {
        sessionId.startsWith(WEB_PREFIX) || sessionId.startsWith(MP_PREFIX) ->
            sessionMapper.selectBySessionIdAndStatus(sessionId, ACTIVE_SESSION_STATUS)
                ?.let { Owner(tenantId = it.tenantId, agentId = it.agentId ?: 0L) }

        sessionId.startsWith(CHANNEL_PREFIX) ->
            channelMapper.selectOwnerBySessionId(sessionId)?.let { Owner(tenantId = it.tenantId, agentId = it.agentId) }

        sessionId.startsWith(TaskSessionId.PREFIX) ->
            TaskSessionId.parse(sessionId)?.let { fromTask(it) }

        else -> null
    }

    /**
     * A scheduled run's proposal, filed against the tenant the *task row* says, not the one the session id
     * claims.
     *
     * Contract C1 puts the agent id in the id itself, and that is what lets admin resolve an agent spec
     * without the task table — every message a task run sends has to be answered, so a cold read would not
     * do. A draft is the other case: it is written into somebody's review queue, and the id arrived over
     * the same bearer the agent runs with, so the claim in it is the caller's own. `agent_task` is the
     * scheduler's table since release 2, so its answer is read over HTTP, the same [SchedulerClient.taskOwner]
     * an OAuth MCP lookup uses — one proposal per draft makes this the cold path it was designed to be.
     *
     * Every way that read can fail refuses the proposal rather than falling back to the claimed id. A
     * `task-…` session id exists only because the scheduler ran that task, so "the scheduler cannot tell me
     * who owns it" already means the run cannot be attributed to a workspace — and an unattributable draft
     * is the one thing intake refuses everywhere else.
     */
    private fun fromTask(parsed: TaskSessionId.Parsed): Owner? {
        val answer = try {
            schedulerClient.taskOwner(parsed.taskId)
        } catch (e: Exception) {
            log.warn("Task {} could not be read from the scheduler: {}", parsed.taskId, e.message)
            return null
        }
        val taskOwner = answer.data
        if (!answer.isSuccess() || taskOwner == null) {
            log.warn(
                "Task {} is not confirmed by the scheduler ({}), so its proposal has no tenant to enter",
                parsed.taskId,
                answer.message,
            )
            return null
        }
        if (taskOwner.agentId != parsed.agentId) {
            log.warn(
                "Session id claims agent {} but task {} runs agent {}, so the proposal is not from that agent",
                parsed.agentId,
                parsed.taskId,
                taskOwner.agentId,
            )
            return null
        }
        return Owner(tenantId = taskOwner.tenantId, agentId = taskOwner.agentId)
    }

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

        /** Ceiling on the caller-reported scan notes, same reasoning as [MAX_RESOURCES_BYTES]. */
        private const val MAX_SCAN_FINDINGS = 200

        private const val MAX_SCAN_FINDINGS_BYTES = 32_000

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

        /**
         * The principal [com.agnetix.harnax.admin.config.JwtAuthenticationFilter] installs for a bearer that
         * is the internal shared secret rather than a token. `UserContextUtil` answers `SYSTEM` for it, which
         * is what would otherwise become a decision's `reviewed_by`.
         */
        private const val INTERNAL_SERVICE_PRINCIPAL = "internal-service"
    }
}

package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.SkillDraftSubmitRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.SkillDraftService
import com.agnetix.harnax.admin.skill.SkillDraftCodec
import com.agnetix.harnax.admin.skill.SkillInstaller
import com.agnetix.harnax.admin.skill.SkillReviewRecorder
import com.agnetix.harnax.common.session.TaskSessionId
import com.agnetix.harnax.entity.SkillDraft
import com.agnetix.harnax.entity.SkillReviewLog
import com.agnetix.harnax.mapper.AgentMapper
import com.agnetix.harnax.mapper.ChannelMapper
import com.agnetix.harnax.mapper.SessionMapper
import com.agnetix.harnax.mapper.SkillDraftMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * Intake of the agent-proposal queue.
 *
 * Every refusal here is a `BizException` with the offending value named, because the caller is a promotion
 * gate sitting inside a live inference and its only move is to tell the model why nothing was queued. There
 * is no retry worth having on any of them: a proposal that cannot be attributed, or whose name does not fit
 * the column, fails the same way on the next attempt.
 */
@Service
class SkillDraftServiceImpl(
    private val skillDraftMapper: SkillDraftMapper,
    private val sessionMapper: SessionMapper,
    private val channelMapper: ChannelMapper,
    private val agentMapper: AgentMapper,
    private val skillReviewRecorder: SkillReviewRecorder,
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
            scanFindings = findingsJson(request.scanFindings)
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

    /** Findings are display text only, so an unreadable list costs the note, not the proposal. */
    private fun findingsJson(findings: List<String>?): String? = findings
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() }
        ?.let { objectMapper.writeValueAsString(it) }

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

        private const val WEB_PREFIX = "web-"
        private const val MP_PREFIX = "mp-"
        private const val CHANNEL_PREFIX = "chn-"
        private const val ACTIVE_SESSION_STATUS = 1
    }
}

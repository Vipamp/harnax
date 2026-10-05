package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.SkillUsageReportRequest
import com.agnetix.harnax.admin.dto.SkillUsageSummaryResponse
import com.agnetix.harnax.admin.service.SkillRepositoryService
import com.agnetix.harnax.admin.service.SkillUsageService
import com.agnetix.harnax.admin.service.UserTenantService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.TenantResolver
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.common.session.TaskSessionId
import com.agnetix.harnax.entity.SkillUsage
import com.agnetix.harnax.mapper.AgentMapper
import com.agnetix.harnax.mapper.ChannelMapper
import com.agnetix.harnax.mapper.SessionMapper
import com.agnetix.harnax.mapper.SkillMapper
import com.agnetix.harnax.mapper.SkillUsageMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * Skill usage intake and aggregation.
 */
@Service
class SkillUsageServiceImpl(
    private val skillUsageMapper: SkillUsageMapper,
    private val skillMapper: SkillMapper,
    private val sessionMapper: SessionMapper,
    private val channelMapper: ChannelMapper,
    private val agentMapper: AgentMapper,
    private val skillRepositoryService: SkillRepositoryService,
    private val userTenantService: UserTenantService,
    private val jwtUtil: JwtUtil,
) : SkillUsageService {

    private val log = LoggerFactory.getLogger(SkillUsageServiceImpl::class.java)

    override fun report(request: SkillUsageReportRequest): Int {
        val sessionId = request.sessionId?.trim().orEmpty()
        if (sessionId.isEmpty()) {
            log.warn("Skill usage report without a session id, so nothing was stored")
            return 0
        }
        val events = request.events.orEmpty()
        if (events.isEmpty()) {
            return 0
        }

        // Resolved before anything is written, and with no fallback to the request's own tenant or to the
        // default workspace: an event filed under the wrong tenant is a number on another tenant's
        // analytics page, and nothing downstream can tell which one.
        val tenantId = resolveTenant(sessionId) ?: run {
            log.warn("Session {} resolves to no tenant, so its {} skill usage events were dropped", sessionId, events.size)
            return 0
        }

        // The runtime names the end user it was serving; the session still decides whose workspace that is.
        // An id with no membership row here would put counts beside people who were not in this tenant, so it
        // is dropped the way a skill id invisible to the tenant is. A channel conversation reports no user at
        // all, and that is the ordinary case rather than something to warn about.
        val userId = request.userId?.takeUnless { it <= 0 }?.let { reported ->
            if (userTenantService.isUserInTenant(reported, tenantId)) {
                reported
            } else {
                log.warn("Session {} reported user {} outside tenant {}, so its usage is left unattributed", sessionId, reported, tenantId)
                null
            }
        }

        val kept = events.take(MAX_EVENTS_PER_REPORT)
        if (kept.size < events.size) {
            log.warn("Session {} reported {} events, only the first {} were taken", sessionId, events.size, MAX_EVENTS_PER_REPORT)
        }

        val visible = visibleSkillIds(kept.mapNotNull { it.skillId }.distinct(), tenantId)
        val occurredAt = LocalDateTime.now()
        var stored = 0
        for (event in kept) {
            val skillId = event.skillId
            val kind = event.event?.trim()?.uppercase()
            if (skillId == null || skillId <= 0) {
                log.debug("Dropped a skill usage event with no skill id from session {}", sessionId)
                continue
            }
            if (skillId !in visible) {
                // Either no such row or a row this session has no business using; both are the same nothing
                log.warn("Dropped a {} event for skill {} not visible to session {}'s tenant {}", kind, skillId, sessionId, tenantId)
                continue
            }
            if (kind != SkillUsage.EVENT_VIEW && kind != SkillUsage.EVENT_USE) {
                log.warn("Dropped a skill usage event of unknown kind '{}' for skill {} in session {}", kind, skillId, sessionId)
                continue
            }
            stored += skillUsageMapper.insert(
                SkillUsage().apply {
                    this.tenantId = tenantId
                    this.skillId = skillId
                    // Already checked against this tenant, or null: a channel conversation has no harnax user
                    // to name, and a sentinel would make per-user reporting look like it was bucketed by somebody.
                    this.userId = userId
                    this.event = kind
                    this.sessionId = sessionId
                    this.occurredAt = occurredAt
                },
            ).coerceAtLeast(0)
        }
        log.info("Stored {} of {} skill usage events for session {} in tenant {}", stored, kept.size, sessionId, tenantId)
        return stored
    }

    override fun summary(days: Int): SkillUsageSummaryResponse {
        val windowDays = days.coerceIn(MIN_WINDOW_DAYS, MAX_WINDOW_DAYS)
        if (windowDays != days) {
            log.info("Usage window {} days clamped to {}", days, windowDays)
        }
        val tenantId = TenantResolver.resolve(jwtUtil)
        // The same viewer rule the skill list applies, read the same way: a private skill belongs to its
        // creator, so whose page this is decides which rows the aggregate may name.
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        val since = LocalDateTime.now().minusDays(windowDays.toLong())
        val builtinRepositoryId = skillRepositoryService.getBuiltinRepository()?.id
        val aggregates = skillUsageMapper.selectUsageByTenant(tenantId, since, builtinRepositoryId, currentUsername)

        val repositoryNames = aggregates.map { it.repositoryId }.distinct()
            .associateWith { skillRepositoryService.getSkillRepository(it)?.name }

        val rows = aggregates.map {
            SkillUsageSummaryResponse.Row(
                skillId = it.skillId,
                name = it.name,
                repositoryId = it.repositoryId,
                repositoryName = repositoryNames[it.repositoryId],
                status = it.status,
                origin = it.origin,
                viewCount = it.viewCount,
                useCount = it.useCount,
                lastUsedAt = it.lastUsedAt,
            )
        }
        return SkillUsageSummaryResponse(
            days = windowDays,
            since = since,
            totalSkills = rows.size,
            zeroUseCount = rows.count { it.viewCount == 0 && it.useCount == 0 },
            totalViews = rows.sumOf { it.viewCount },
            totalUses = rows.sumOf { it.useCount },
            rows = rows,
        )
    }

    /**
     * The tenant a session belongs to, from the tables that record who ran it.
     *
     * Three shapes, the same three [com.agnetix.harnax.admin.controller.InternalApiController] resolves an
     * agent spec by: a web/mini-program session has a `session` row, a channel conversation has a `channel`
     * row and no session row at all, and a scheduled-task run carries its agent id in the session id
     * (contract C1) because its task row left this database with the scheduler.
     *
     * The session's own tenant wins over the agent's for the first two: a skill shared into another
     * workspace is used *there*, and its load counts belong to the tenant that loaded it.
     */
    private fun resolveTenant(sessionId: String): Long? = when {
        sessionId.startsWith(WEB_PREFIX) || sessionId.startsWith(MP_PREFIX) ->
            sessionMapper.selectBySessionIdAndStatus(sessionId, ACTIVE_SESSION_STATUS)?.tenantId

        sessionId.startsWith(CHANNEL_PREFIX) -> channelMapper.selectBySessionId(sessionId)?.tenantId

        sessionId.startsWith(TaskSessionId.PREFIX) -> TaskSessionId.parse(sessionId)?.let {
            agentMapper.selectById(it.agentId)?.tenantId
        }

        else -> null
    }

    /**
     * Which of [skillIds] this tenant may have used.
     *
     * A skill of the shared builtin repository counts as visible to every tenant, the exemption the
     * delivery path applies in `SkillBindingResolver.deliverable` and the one
     * `SkillUsageMapper.selectUsageByTenant` reports on; a row of another tenant does not, so a made-up id
     * cannot put a number on somebody else's page.
     */
    private fun visibleSkillIds(
        skillIds: List<Long>,
        tenantId: Long,
    ): Set<Long> {
        if (skillIds.isEmpty()) return emptySet()
        val builtinRepositoryId = skillRepositoryService.getBuiltinRepository()?.id
        return skillMapper.selectByIds(skillIds)
            .filter { it.tenantId == tenantId || (builtinRepositoryId != null && it.repositoryId == builtinRepositoryId) }
            .map { it.id }
            .toSet()
    }

    companion object {
        private const val WEB_PREFIX = "web-"
        private const val MP_PREFIX = "mp-"
        private const val CHANNEL_PREFIX = "chn-"

        /** Same status the agent-spec resolution uses: 1 = active session. */
        private const val ACTIVE_SESSION_STATUS = 1

        /**
         * Ceiling on one report. The runtime sends a handful of events per turn; a request carrying
         * thousands is a broken caller rather than a busy session, and every one of them would be an
         * insert inside the turn that reported it.
         */
        private const val MAX_EVENTS_PER_REPORT = 200

        /** A window shorter than this reports a single day's noise as a trend. */
        private const val MIN_WINDOW_DAYS = 1

        /** Longer than this and the aggregate walks the whole stream. */
        private const val MAX_WINDOW_DAYS = 365
    }
}

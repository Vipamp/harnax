package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.SessionChatUpdateRequest
import com.agnetix.harnax.admin.dto.SessionCreateRequest
import com.agnetix.harnax.admin.dto.SessionResponse
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.*
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.entity.Session
import com.agnetix.harnax.mapper.AgentMcpBindingMapper
import com.agnetix.harnax.mapper.AgentSkillBindingMapper
import com.agnetix.harnax.mapper.SessionMapper
import com.agnetix.harnax.mapper.TeamMapper
import com.agnetix.harnax.mapper.TeamMemberMapper
import com.agnetix.harnax.mapper.TeamSkillBindingMapper
import com.github.pagehelper.PageHelper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.*

/**
 * Session service implementation
 */
@Service
class SessionServiceImpl(
    private val agentService: AgentService,
    private val mcpServerService: McpServerService,
    private val skillRepositoryService: SkillRepositoryService,
    private val skillService: SkillService,
    private val modelService: ModelService,
    private val jwtUtil: JwtUtil,
    private val sessionMapper: SessionMapper,
    private val mcpBindingMapper: AgentMcpBindingMapper,
    private val skillBindingMapper: AgentSkillBindingMapper,
    private val teamMapper: TeamMapper,
    private val teamMemberMapper: TeamMemberMapper,
    private val teamSkillBindingMapper: TeamSkillBindingMapper,
) : SessionService {

    private val log = LoggerFactory.getLogger(SessionServiceImpl::class.java)

    override fun page(
        keyword: String?,
        status: Int?,
        pageNum: Int,
        pageSize: Int,
    ): Page<Session> {
        log.info(
            "Paginated query for session list, pageNum: {}, pageSize: {}, keyword: {}, status: {}",
            pageNum,
            pageSize,
            keyword,
            status,
        )
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        val safePageNum = pageNum.coerceAtLeast(1)
        val safePageSize = pageSize.coerceIn(1, 1000)
        PageHelper.startPage<Session>(safePageNum, safePageSize)
        return Page.fromPageInfo(sessionMapper.selectSessionList(keyword, status, currentUsername, currentTenantId()))
    }

    private fun currentTenantId(): Long = TenantContext.getTenantId() ?: 1

    /**
     * The mapper's single-row statements carry no tenant condition and the tenant interceptor is
     * inert, so ownership is decided here: a row of another tenant answers as the absent one it is
     * to this caller.
     */
    private fun ownedSession(id: Long): Session? = sessionMapper.selectById(id)?.takeIf { it.tenantId == currentTenantId() }

    override fun getSession(id: Long): Session? = ownedSession(id)

    override fun convertToResponse(session: Session): SessionResponse {
        val response = SessionResponse()
        response.id = session.id
        response.title = session.title
        response.sessionDescription = session.sessionDescription
        response.sessionId = session.sessionId
        response.agentId = session.agentId
        response.teamId = session.teamId
        response.name = session.name
        response.description = session.description
        response.systemPrompt = session.systemPrompt
        response.modelId = session.modelId

        // Query model name
        session.modelId.let { modelId ->
            val model = modelService.getModel(modelId)
            model?.let {
                response.modelName = it.modelName
                response.modelPrice = it.price
                response.modelSupportReasoning = it.supportReasoning
                response.modelThinkingMode = it.thinkingMode
                response.modelSupportInternet = it.supportInternet
                response.modelSupportVision = it.supportVision
            }
        }

        response.enableThink = session.enableThink
        response.enableSearch = session.enableSearch
        response.enablePlan = session.enablePlan
        response.permissionMode = session.permissionMode

        response.owner = session.owner
        response.status = session.status
        response.isPublic = session.isPublic
        response.creator = session.creator
        response.createTime = session.createTime
        response.updateTime = session.updateTime

        // A session has no capability bindings of its own: both lists follow whoever it runs on. For a
        // team that is the team row — and a team takes no MCP configuration, so an empty list there is
        // the right answer rather than a missing one.
        val agentId = session.agentId
        val mcpItems = mutableListOf<SessionResponse.McpItem>()
        for (binding in agentId?.let(mcpBindingMapper::selectByAgentId).orEmpty()) {
            val mcp = mcpServerService.getMcpServer(binding.mcpId) ?: continue
            val item = SessionResponse.McpItem()
            item.mcpId = mcp.id
            item.mcpName = mcp.name
            item.mcpDescription = mcp.description
            mcpItems.add(item)
        }
        response.mcpList = mcpItems

        val skillIds = session.teamId?.let { teamSkillBindingMapper.selectByTeamId(it).map { binding -> binding.skillId } }
            ?: agentId?.let { skillBindingMapper.selectByAgentId(it).map { binding -> binding.skillId } }
            ?: emptyList()
        val skillItems = mutableListOf<SessionResponse.SkillItem>()
        for (skillId in skillIds) {
            val skill = skillService.getSkill(skillId) ?: continue
            val item = SessionResponse.SkillItem()
            item.skillId = skill.id
            item.skillName = skill.name

            skillRepositoryService.getSkillRepository(skill.repositoryId)?.let { repository ->
                item.repositoryId = repository.id
                item.repositoryName = repository.name
            }

            skillItems.add(item)
        }
        response.skillList = skillItems

        return response
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun createSession(request: SessionCreateRequest): Boolean = try {
        // Check if session name already exists
        val count = sessionMapper.countByTitle(request.title)
        if (count > 0) {
            throw BizException("Session name already exists, please use another name")
        }

        val tenantId = currentTenantId()
        val team = request.teamId?.let { teamId ->
            val loaded = teamMapper.selectById(teamId) ?: throw BizException("Team not found: $teamId")
            if (loaded.tenantId != tenantId) {
                throw BizException("Team belongs to another tenant")
            }
            if (loaded.status != 1) {
                throw BizException("Team '${loaded.name}' is disabled")
            }
            if (teamMemberMapper.selectByTeamId(teamId).isEmpty()) {
                throw BizException("Team '${loaded.name}' has no members")
            }
            loaded
        }

        val session = Session()
        session.title = request.title
        session.sessionDescription = request.sessionDescription
        session.sessionId = "web-${UUID.randomUUID()}"
        // The conversation keeps its own copy of who it runs on, so a later edit to the team or the
        // agent does not change a chat already started. `team_id` is what makes this a team
        // conversation, and only ever set here — updateById never writes it.
        session.teamId = team?.id
        val modelId: Long
        if (team != null) {
            // A team has no lead agent row to point at (design D1): agent_id stays NULL, and what used
            // to be copied off the lead comes off the team.
            session.name = team.name
            session.description = team.description
            session.systemPrompt = team.systemPrompt
            session.owner = team.creator
            modelId = team.modelId
        } else {
            val requestedAgentId = request.agentId
                ?: throw BizException("Agent ID cannot be empty")
            val agent = agentService.getAgent(requestedAgentId)
                ?: throw BizException("Agent not found")
            session.agentId = agent.id
            session.name = agent.name
            session.description = agent.description
            session.systemPrompt = agent.systemPrompt
            session.owner = agent.owner
            modelId = agent.modelId
        }
        session.status = 1

        // Default enable_think based on model thinking mode (optional/required -> on)
        val model = modelService.getModel(modelId)
        session.modelId = modelId
        session.enableThink = if ((model?.thinkingMode ?: 0) >= 1) 1 else 0

        // Set creator
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        session.creator = currentUsername

        // 归属跟随当前租户，否则行会落到 DDL 缺省租户，与它绑定的 agent 不同租户
        session.tenantId = tenantId

        // Default not public
        session.isPublic = 0

        val success = this.sessionMapper.insert(session) > 0
        log.info("Session creation {}, id: {}", if (success) "successful" else "failed", session.id)
        success
    } catch (e: Exception) {
        log.error("Failed to create session", e)
        throw RuntimeException("Failed to create session: ${e.message}")
    }

    override fun updateSession(id: Long, request: SessionCreateRequest): Boolean {
        val session = ownedSession(id)
            ?: throw BizException("Session not found")
        // A team session has no agent to bind: its lead is the team row and agent_id stays NULL. Naming
        // one here would only make the listing disagree with what actually runs.
        if (session.teamId != null && request.agentId != null) {
            throw BizException("A team session follows its team, not an agent; change the team instead")
        }
        session.title = request.title
        session.description = request.sessionDescription
        request.agentId?.let { session.agentId = it }
        session.updateTime = LocalDateTime.now()
        return sessionMapper.updateById(session) > 0
    }

    override fun getSessionChatConfig(sessionId: String): Session? {
        log.info("Getting session configuration, sessionId: {}", sessionId)

        // Query session by sessionId (status enabled)
        return sessionMapper.selectBySessionIdAndStatus(sessionId, 1)
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateSessionChatConfig(sessionId: String, request: SessionChatUpdateRequest) {
        log.info("Updating session configuration, sessionId: {}", sessionId)

        // Query session by sessionId (status enabled)
        val session = sessionMapper.selectBySessionIdAndStatus(sessionId, 1)
            ?: throw BizException("Session not found or disabled")

        // Update configuration fields (only update non-null fields)
        request.enableThink?.let {
            // Model with required thinking mode (thinkingMode=2) cannot have thinking disabled
            val model = modelService.getModel(session.modelId)
            if (!it && (model?.thinkingMode ?: 0) == 2) {
                throw BizException("Current model requires Deep Thinking and it cannot be turned off.")
            }
            session.enableThink = if (it) 1 else 0
        }
        request.enableSearch?.let { session.enableSearch = if (it) 1 else 0 }
        request.enablePlan?.let { session.enablePlan = if (it) 1 else 0 }
        request.permissionMode?.let { session.permissionMode = it }

        // Update timestamp
        session.updateTime = LocalDateTime.now()

        // Execute update
        val result = sessionMapper.updateById(session)
        if (result <= 0) {
            throw BizException("Failed to update session configuration")
        }

        log.info("Session configuration updated successfully, sessionId: {}", sessionId)
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun toggleSessionStatus(id: Long, status: Int): Boolean {
        log.info("Toggling session status, id: {}, status: {}", id, status)

        val session = ownedSession(id)
            ?: throw BizException("Session not found")

        return sessionMapper.updateStatus(id, status) > 0
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun deleteSession(id: Long): Boolean {
        log.info("Deleting session, id: {}", id)

        val session = ownedSession(id)
            ?: throw BizException("Session not found")

        return this.sessionMapper.deleteById(id) > 0
    }

    override fun existsByTitle(title: String): Boolean {
        val count = sessionMapper.countByTitle(title)
        return count > 0
    }
}

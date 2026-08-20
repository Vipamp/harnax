package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.SessionChatUpdateRequest
import com.agnetix.harnax.admin.dto.SessionCreateRequest
import com.agnetix.harnax.admin.dto.SessionResponse
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.*
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.entity.Session
import com.agnetix.harnax.mapper.SessionMapper
import com.github.pagehelper.PageHelper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
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
) : SessionService {

    private val log = LoggerFactory.getLogger(SessionServiceImpl::class.java)
    private val objectMapper = ObjectMapper()

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
        return Page.fromPageInfo(sessionMapper.selectSessionList(keyword, status, currentUsername))
    }

    override fun getSession(id: Long): Session? = this.sessionMapper.selectById(id)

    override fun convertToResponse(session: Session): SessionResponse {
        val response = SessionResponse()
        response.id = session.id
        response.title = session.title
        response.sessionDescription = session.sessionDescription
        response.sessionId = session.sessionId
        response.agentId = session.agentId
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

        // Parse MCP list (JSON format)
        if (session.mcpList.isNotEmpty()) {
            try {
                val mcpConfigs: List<Map<String, Any>> = objectMapper.readValue(
                    session.mcpList,
                    object : TypeReference<List<Map<String, Any>>>() {},
                )

                val mcpItems = mutableListOf<SessionResponse.McpItem>()
                for (config in mcpConfigs) {
                    val mcpId = (config["id"] as Number).toLong()
                    val enableSkip = config["enable_skip"] as String?

                    val fullMcp = mcpServerService.getMcpServer(mcpId)
                    fullMcp?.let {
                        val item = SessionResponse.McpItem()
                        item.mcpId = it.id
                        item.mcpName = it.name
                        item.mcpDescription = it.description
                        item.enableSkip = enableSkip
                        mcpItems.add(item)
                    }
                }
                response.mcpList = mcpItems
            } catch (e: Exception) {
                log.warn("Failed to parse MCP list", e)
                response.mcpList = mutableListOf()
            }
        }

        // Parse skill list (comma-separated string)
        if (session.skillList.isNotEmpty()) {
            try {
                val skillIds = session.skillList.split(",")
                val skillItems = mutableListOf<SessionResponse.SkillItem>()

                for (skillIdStr in skillIds) {
                    try {
                        val skillId = skillIdStr.trim().toLong()
                        val skill = skillService.getSkill(skillId)
                        skill?.let {
                            val item = SessionResponse.SkillItem()
                            item.skillId = it.id
                            item.skillName = it.name

                            val repository = skillRepositoryService.getSkillRepository(it.repositoryId)
                            repository?.let {
                                item.repositoryId = it.id
                                item.repositoryName = it.name
                            }

                            skillItems.add(item)
                        }
                    } catch (e: NumberFormatException) {
                        log.warn("Invalid skill ID: {}", skillIdStr)
                    }
                }

                response.skillList = skillItems
            } catch (e: Exception) {
                log.warn("Failed to parse skill list", e)
                response.skillList = mutableListOf()
            }
        }

        return response
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun createSession(request: SessionCreateRequest): Boolean = try {
        // Check if session name already exists
        val count = sessionMapper.countByTitle(request.title)
        if (count > 0) {
            throw BizException("Session name already exists, please use another name")
        }

        // Get agent information by agent ID
        val agent = agentService.getAgent(request.agentId)
            ?: throw BizException("Agent not found")

        val session = Session()
        session.title = request.title
        session.sessionDescription = request.sessionDescription
        session.sessionId = "web-${UUID.randomUUID()}"
        session.agentId = request.agentId

        // Copy information from agent
        session.name = agent.name
        session.description = agent.description
        session.systemPrompt = agent.systemPrompt
        session.modelId = agent.modelId
        session.mcpList = agent.mcpList
        session.skillList = agent.skillList
        session.owner = agent.owner
        session.status = 1

        // Default enable_think based on model thinking mode (optional/required -> on)
        val model = modelService.getModel(agent.modelId)
        session.enableThink = if ((model?.thinkingMode ?: 0) >= 1) 1 else 0

        // Set creator
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        session.creator = currentUsername

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
        val session = sessionMapper.selectById(id)
            ?: throw BizException("Session not found")
        session.title = request.title
        session.description = request.sessionDescription
        request.agentId.let { session.agentId = it }
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

        val session = sessionMapper.selectById(id)
            ?: throw BizException("Session not found")

        return sessionMapper.updateStatus(id, status) > 0
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun deleteSession(id: Long): Boolean {
        log.info("Deleting session, id: {}", id)

        val session = this.sessionMapper.selectById(id)
            ?: throw BizException("Session not found")

        return this.sessionMapper.deleteById(id) > 0
    }

    override fun existsByTitle(title: String): Boolean {
        val count = sessionMapper.countByTitle(title)
        return count > 0
    }
}

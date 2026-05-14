package com.vipamp.vipclaw.admin.service.impl

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.pagehelper.PageHelper
import com.vipamp.vipclaw.admin.dto.Page
import com.vipamp.vipclaw.admin.dto.SessionChatUpdateRequest
import com.vipamp.vipclaw.admin.dto.SessionCreateRequest
import com.vipamp.vipclaw.admin.dto.SessionResponse
import com.vipamp.vipclaw.admin.entity.Session
import com.vipamp.vipclaw.admin.entity.SysJob
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.mapper.SessionMapper
import com.vipamp.vipclaw.admin.service.*
import com.vipamp.vipclaw.admin.util.JwtUtil
import com.vipamp.vipclaw.admin.util.UserContextUtil
import com.vipamp.vipclaw.ascopagent.dto.SessionConfigResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.*

/**
 * Session service implementation
 *
 * @author vipamp
 * @since 2026-03-25
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
        PageHelper.startPage<SysJob>(pageNum, pageSize)
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
            }
        }

        response.enableThink = session.enableThink
        response.enableSearch = session.enableSearch
        response.enablePlan = session.enablePlan

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
        session.sessionId = UUID.randomUUID().toString()
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
        val session = Session()
        session.title = request.title
        session.description = request.sessionDescription
        request.agentId.let { session.agentId = it }
        return sessionMapper.updateById(session) > 0
    }

    override fun getSessionChatConfig(sessionId: String): SessionConfigResponse {
        log.info("Getting session configuration, sessionId: {}", sessionId)

        // Query session by sessionId (status enabled)
        val session = sessionMapper.selectBySessionIdAndStatus(sessionId, 1)
            ?: throw BizException("Session not found or disabled")

        return SessionConfigResponse(
            sessionId = session.sessionId,
            enableThink = session.enableThink == 1,
            enableSearch = session.enableSearch == 1,
            enablePlan = session.enablePlan == 1,
        )
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateSessionChatConfig(sessionId: String, request: SessionChatUpdateRequest) {
        log.info("Updating session configuration, sessionId: {}", sessionId)

        // Query session by sessionId (status enabled)
        val session = sessionMapper.selectBySessionIdAndStatus(sessionId, 1)
            ?: throw BizException("Session not found or disabled")

        // Update configuration fields (only update non-null fields)
        request.enableThink?.let { session.enableThink = if (it) 1 else 0 }
        request.enableSearch?.let { session.enableSearch = if (it) 1 else 0 }
        request.enablePlan?.let { session.enablePlan = if (it) 1 else 0 }

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

package com.vipamp.vipclaw.admin.service.impl

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.vipamp.vipclaw.admin.dto.SessionCreateRequest
import com.vipamp.vipclaw.admin.dto.SessionResponse
import com.vipamp.vipclaw.admin.entity.Session
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.mapper.SessionMapper
import com.vipamp.vipclaw.admin.service.*
import com.vipamp.vipclaw.admin.util.JwtUtil
import com.vipamp.vipclaw.admin.util.UserContextUtil
import com.vipamp.vipclaw.common.page.Page
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*
import kotlin.math.min

/**
 * 会话服务实现类
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
    private val sessionMapper: SessionMapper
) : SessionService {

    private val log = LoggerFactory.getLogger(SessionServiceImpl::class.java)
    private val objectMapper = ObjectMapper()

    override fun getSessionPage(
        keyword: String?,
        status: Int?,
        current: Int,
        size: Int
    ): Page<Session> {
        log.info("分页查询会话列表，current: {}, size: {}, keyword: {}, status: {}", current, size, keyword, status)

        // 获取当前用户
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)

        // 使用 MyBatis 原生查询
        val allSessions = sessionMapper.selectSessionList(keyword, status, currentUsername)

        // 手动分页
        val page = Page<Session>(current.toLong(), size.toLong())
        val fromIndex = (current - 1) * size
        val toIndex = min(fromIndex + size, allSessions.size)

        page.records = if (fromIndex < allSessions.size) {
            allSessions.subList(fromIndex, toIndex)
        } else {
            emptyList()
        }
        page.total = allSessions.size.toLong()

        return page
    }

    override fun getSessionById(id: Long): Session {
        log.info("查询会话详情，id: {}", id)
        val session = this.sessionMapper.selectById(id)
            ?: throw BizException("会话不存在")
        return session
    }

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

        // 查询模型名称
        session.modelId.let { modelId ->
            val model = modelService.getModelById(modelId)
            model?.let {
                response.modelName = it.modelName
                response.modelPrice = it.price
            }
        }

        response.owner = session.owner
        response.status = session.status
        response.isPublic = session.isPublic
        response.creator = session.creator
        response.createTime = session.createTime
        response.updateTime = session.updateTime

        // 解析 MCP 列表 (JSON 格式)
        if (!session.mcpList.isNullOrEmpty()) {
            try {
                val mcpConfigs: List<Map<String, Any>> = objectMapper.readValue(
                    session.mcpList,
                    object : TypeReference<List<Map<String, Any>>>() {}
                )

                val mcpItems = mutableListOf<SessionResponse.McpItem>()
                for (config in mcpConfigs) {
                    val mcpId = (config["id"] as Number).toLong()
                    val enableSkip = config["enable_skip"] as String?

                    val fullMcp = mcpServerService.getMcpServerById(mcpId)
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
                log.warn("解析 MCP 列表失败", e)
                response.mcpList = mutableListOf()
            }
        }

        // 解析技能列表（逗号分隔的字符串）
        if (!session.skillList.isNullOrEmpty()) {
            try {
                val skillIds = session.skillList!!.split(",")
                val skillItems = mutableListOf<SessionResponse.SkillItem>()

                for (skillIdStr in skillIds) {
                    try {
                        val skillId = skillIdStr.trim().toLong()
                        val skill = skillService.getSkillById(skillId)
                        skill?.let {
                            val item = SessionResponse.SkillItem()
                            item.skillId = it.id
                            item.skillName = it.name

                            val repository = skillRepositoryService.getRepositoryById(it.repositoryId)
                            repository?.let {
                                item.repositoryId = it.id
                                item.repositoryName = it.name
                            }

                            skillItems.add(item)
                        }
                    } catch (e: NumberFormatException) {
                        log.warn("无效的技能 ID: {}", skillIdStr)
                    }
                }

                response.skillList = skillItems
            } catch (e: Exception) {
                log.warn("解析技能列表失败", e)
                response.skillList = mutableListOf()
            }
        }

        return response
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun createSession(request: SessionCreateRequest): Boolean {
        return try {
            // 检查会话名称是否重复
            val count = sessionMapper.countByTitle(request.title!!)
            if (count > 0) {
                throw BizException("会话名称已存在，请使用其他名称")
            }

            // 根据智能体ID获取智能体信息
            val agent = agentService.getAgentById(request.agentId!!)
                ?: throw BizException("智能体不存在")

            val session = Session()
            session.title = request.title!!
            session.sessionDescription = request.sessionDescription!!
            session.sessionId = UUID.randomUUID().toString()
            session.agentId = request.agentId!!

            // 从智能体复制信息
            session.name = agent.name
            session.description = agent.description
            session.systemPrompt = agent.systemPrompt
            session.modelId = agent.modelId
            session.mcpList = agent.mcpList
            session.skillList = agent.skillList
            session.owner = agent.owner
            session.status = 1

            // 设置创建人
            val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
            session.creator = currentUsername!!

            // 默认不公开
            session.isPublic = 0

            val success = this.sessionMapper.insert(session) > 0
            log.info("会话创建{}，id: {}", if (success) "成功" else "失败", session.id)
            success
        } catch (e: Exception) {
            log.error("创建会话失败", e)
            throw RuntimeException("创建会话失败：${e.message}")
        }
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun toggleSessionStatus(id: Long, status: Int): Boolean {
        log.info("切换会话状态，id: {}, status: {}", id, status)

        val session = sessionMapper.selectActiveById(id)
            ?: throw BizException("会话不存在")

        return sessionMapper.updateStatus(id, status) > 0
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun deleteSession(id: Long): Boolean {
        log.info("删除会话，id: {}", id)

        val session = this.sessionMapper.selectById(id)
            ?: throw BizException("会话不存在")

        return this.sessionMapper.deleteById(id) > 0
    }

    override fun existsByTitle(title: String): Boolean {
        val count = sessionMapper.countByTitle(title)
        return count > 0
    }
}

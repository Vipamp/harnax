package com.vipamp.vipclaw.admin.service.impl

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.pagehelper.PageHelper
import com.vipamp.vipclaw.admin.dto.AgentCreateRequest
import com.vipamp.vipclaw.admin.dto.AgentResponse
import com.vipamp.vipclaw.admin.dto.AgentUpdateRequest
import com.vipamp.vipclaw.admin.entity.Agent
import com.vipamp.vipclaw.admin.mapper.AgentMapper
import com.vipamp.vipclaw.admin.mapper.SessionMapper
import com.vipamp.vipclaw.admin.service.*
import com.vipamp.vipclaw.admin.util.JwtUtil
import com.vipamp.vipclaw.admin.util.UserContextUtil
import com.vipamp.vipclaw.common.page.Page
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 智能体服务实现类
 *
 * @author vipamp
 * @since 2026-03-18
 */
@Service
class AgentServiceImpl(
    private val agentMapper: AgentMapper,
    private val mcpServerService: McpServerService,
    private val skillRepositoryService: SkillRepositoryService,
    private val skillService: SkillService,
    private val modelService: ModelService,
    private val sessionMapper: SessionMapper,
    private val jwtUtil: JwtUtil
) : AgentService {

    private val log = LoggerFactory.getLogger(AgentServiceImpl::class.java)
    private val objectMapper = ObjectMapper()

    override fun getAgentPage(
        name: String?,
        status: Int?,
        current: Int,
        size: Int
    ): Page<Agent> {
        // 获取当前用户
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil) ?: ""

        // 使用 PageHelper 分页
        PageHelper.startPage<Agent>(current, size)
        val agents = agentMapper.selectAgentList(name, status, currentUsername)

        // 转换为 PageInfo
        val pageInfo = com.github.pagehelper.PageInfo(agents)
        return Page.fromPageInfo(pageInfo)
    }

    override fun getAgentById(id: Long): Agent? {
        return agentMapper.selectById(id)
    }

    fun save(agent: Agent): Boolean {
        return agentMapper.insert(agent) > 0
    }

    fun updateById(agent: Agent): Boolean {
        agent.updateTime = LocalDateTime.now()
        return agentMapper.updateById(agent) > 0
    }

    fun removeById(id: Long): Boolean {
        return agentMapper.deleteById(id) > 0
    }

    /**
     * 将 Agent 实体转换为响应 DTO（包含完整的技能和 MCP 信息）
     */
    override fun convertToResponse(agent: Agent?): AgentResponse? {
        if (agent == null) {
            return null
        }

        val response = AgentResponse()
        response.id = agent.id
        response.name = agent.name
        response.description = agent.description
        response.systemPrompt = agent.systemPrompt
        response.modelId = agent.modelId

        // 查询模型名称和价格
        agent.modelId.let { modelId ->
            val model = modelService.getModelById(modelId)
            model?.let {
                response.modelName = it.modelName
                response.modelPrice = it.price
            }
        }

        response.owner = agent.owner
        response.status = agent.status
        response.isPublic = agent.isPublic
        response.creator = agent.creator
        response.createTime = agent.createTime
        response.updateTime = agent.updateTime

        // 查询关联会话列表
        val sessions = sessionMapper.selectByAgentId(agent.id)

        // 转换为 SessionItem 列表
        val sessionItems = sessions.map { session ->
            val item = AgentResponse.SessionItem()
            item.id = session.id
            item.title = session.title
            item.sessionDescription = session.sessionDescription
            item.sessionId = session.sessionId
            item
        }

        response.sessionList = sessionItems
        response.sessionCount = sessionItems.size

        // 解析 MCP 列表 (JSON 格式)
        if (agent.mcpList.isNotEmpty()) {
            try {
                // 先反序列化为 Map 获取 ID 和 enableSkip
                val mcpConfigs: List<Map<String, Any>> = objectMapper.readValue(
                    agent.mcpList,
                    object : TypeReference<List<Map<String, Any>>>() {}
                )

                // 从数据库查询完整的 MCP 信息
                val mcpItems = mutableListOf<AgentResponse.McpItem>()
                for (config in mcpConfigs) {
                    val mcpId = (config["id"] as Number).toLong()
                    val enableSkip = config["enable_skip"] as String?

                    val fullMcp = mcpServerService.getMcpServerById(mcpId)
                    fullMcp.let {
                        val item = AgentResponse.McpItem()
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
        if (!agent.skillList.isNullOrEmpty()) {
            try {
                val skillIds = agent.skillList!!.split(",")
                val skillItems = mutableListOf<AgentResponse.SkillItem>()

                for (skillIdStr in skillIds) {
                    try {
                        val skillId = skillIdStr.trim().toLong()
                        // 从数据库查询完整的技能信息
                        val skill = skillService.getSkillById(skillId)
                        skill?.let {
                            val item = AgentResponse.SkillItem()
                            item.skillId = it.id
                            item.skillName = it.name
                            item.skillDescription = it.skillmd

                            // 查询技能仓库信息
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
    override fun createAgent(request: AgentCreateRequest): Boolean {
        return try {
            val agent = Agent()
            agent.name = request.name!!
            agent.description = request.description!!
            agent.systemPrompt = request.systemPrompt!!
            agent.modelId = request.modelId!!
            agent.owner = request.owner!!
            agent.status = request.status ?: 1

            // 设置创建人
            val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
            agent.creator = currentUsername!!

            // 默认不公开
            if (agent.isPublic == null) {
                agent.isPublic = 0
            }

            // 转换 MCP 列表为 JSON 存储
            // 格式: [{"id":1, "enable_skip":"true"},{"id":2, "enable_skip":"false"}]
            if (!request.mcpList.isNullOrEmpty()) {
                try {
                    agent.mcpList = objectMapper.writeValueAsString(request.mcpList)
                } catch (e: JsonProcessingException) {
                    throw RuntimeException("MCP 列表 JSON 序列化失败", e)
                }
            }

            // 技能列表直接存储为字符串格式 "1,2,3"
            if (!request.skillList.isNullOrEmpty()) {
                agent.skillList = request.skillList
            }

            agent.createTime = LocalDateTime.now()
            agent.updateTime = LocalDateTime.now()
            agentMapper.insert(agent)
            true
        } catch (e: Exception) {
            log.error("创建智能体失败", e)
            throw RuntimeException("创建智能体失败：${e.message}")
        }
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateAgent(id: Long, request: AgentUpdateRequest): Boolean {
        return try {
            val agent = getAgentById(id)
                ?: throw RuntimeException("智能体不存在")

            request.name?.let { agent.name = it }
            request.description?.let { agent.description = it }
            request.systemPrompt?.let { agent.systemPrompt = it }
            request.modelId?.let { agent.modelId = it }
            request.owner?.let { agent.owner = it }
            request.status?.let { agent.status = it }
            request.isPublic?.let { agent.isPublic = it }

            // 更新 MCP 列表
            if (request.mcpList != null) {
                // 允许清空 MCP 列表
                if (request.mcpList.isEmpty()) {
                    agent.mcpList = ""
                } else {
                    // 直接存储 JSON 格式：[{"id":1, "enable_skip":"true"},{"id":2, "enable_skip":"false"}]
                    try {
                        agent.mcpList = objectMapper.writeValueAsString(request.mcpList)
                    } catch (e: JsonProcessingException) {
                        throw RuntimeException("MCP 列表 JSON 序列化失败", e)
                    }
                }
            }
            // 如果 request.mcpList == null，保持原有值不变

            // 更新技能列表
            if (request.skillList != null) {
                // skillList 是字符串格式 "1,2,3" 或空字符串 ""
                agent.skillList = (if (request.skillList.trim().isEmpty()) null else request.skillList).toString()
            }
            // 如果 request.skillList == null，保持原有值不变

            agent.updateTime = LocalDateTime.now()
            agentMapper.updateById(agent)
            true
        } catch (e: Exception) {
            log.error("更新智能体失败", e)
            throw RuntimeException("更新智能体失败：${e.message}")
        }
    }

    override fun toggleAgentStatus(id: Long, status: Int): Boolean {
        val agent = agentMapper.selectById(id)
            ?: throw RuntimeException("智能体不存在")
        agent.status = status
        agent.updateTime = LocalDateTime.now()
        return agentMapper.updateById(agent) > 0
    }

    override fun deleteAgent(id: Long): Boolean {
        return agentMapper.deleteById(id) > 0
    }
}

package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.AgentCreateRequest
import com.agnetix.harnax.admin.dto.AgentResponse
import com.agnetix.harnax.admin.dto.AgentUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.service.*
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.entity.Agent
import com.agnetix.harnax.mapper.AgentMapper
import com.agnetix.harnax.mapper.SessionMapper
import com.github.pagehelper.PageHelper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.JacksonException
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime

/**
 * Agent service implementation
 */
@Service
class AgentServiceImpl(
    private val agentMapper: AgentMapper,
    private val mcpServerService: McpServerService,
    private val skillRepositoryService: SkillRepositoryService,
    private val skillService: SkillService,
    private val agentToolService: AgentToolService,
    private val modelService: ModelService,
    private val sessionMapper: SessionMapper,
    private val jwtUtil: JwtUtil,
) : AgentService {

    private val log = LoggerFactory.getLogger(AgentServiceImpl::class.java)
    private val objectMapper = ObjectMapper()

    override fun page(
        name: String?,
        status: Int?,
        pageNum: Int,
        pageSize: Int,
    ): Page<Agent> {
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        PageHelper.startPage<Agent>(pageNum, pageSize)
        return Page.fromPageInfo(agentMapper.selectAgentList(name, status, currentUsername))
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun createAgent(request: AgentCreateRequest): Boolean = try {
        val agent = Agent()
        agent.name = request.name!!
        agent.description = request.description!!
        agent.systemPrompt = request.systemPrompt!!
        agent.modelId = request.modelId!!
        agent.owner = request.owner!!
        agent.status = request.status ?: 1

        // Set tenant ID
        agent.tenantId = TenantContext.getTenantId() ?: 1

        // Set creator
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        agent.creator = currentUsername!!

        // Default not public
        if (agent.isPublic == null) {
            agent.isPublic = 0
        }

        // Convert MCP list to JSON storage
        // Format: [{"id":1, "enable_skip":"true"},{"id":2, "enable_skip":"false"}]
        if (!request.mcpList.isNullOrEmpty()) {
            try {
                agent.mcpList = objectMapper.writeValueAsString(request.mcpList)
            } catch (e: JacksonException) {
                throw RuntimeException("Failed to serialize MCP list to JSON", e)
            }
        }

        // Skills list stored directly as string format "1,2,3"
        if (!request.skillList.isNullOrEmpty()) {
            agent.skillList = request.skillList
        }

        // Convert tool list to JSON storage
        // Format: [{"id":1,"enable_skip":"true","need_confirm":false}]
        if (!request.toolList.isNullOrEmpty()) {
            try {
                // Validate and apply needConfirm constraints
                val validatedToolList = request.toolList.map { config ->
                    val toolEntity = agentToolService.getAgentTool(config.id ?: 0)
                    val finalNeedConfirm = if (toolEntity != null && toolEntity.needConfirm == 0) {
                        false
                    } else {
                        config.needConfirm ?: false
                    }
                    mapOf(
                        "id" to config.id,
                        "enable_skip" to (config.enableSkip ?: "true"),
                        "need_confirm" to finalNeedConfirm,
                    )
                }
                agent.toolList = objectMapper.writeValueAsString(validatedToolList)
            } catch (e: JacksonException) {
                throw RuntimeException("Failed to serialize tool list to JSON", e)
            }
        }

        agent.createTime = LocalDateTime.now()
        agent.updateTime = LocalDateTime.now()
        agentMapper.insert(agent)
        true
    } catch (e: Exception) {
        log.error("Failed to create agent", e)
        throw RuntimeException("Failed to create agent: ${e.message}")
    }

    override fun getAgent(id: Long): Agent? = agentMapper.selectById(id)

    @Transactional(rollbackFor = [Exception::class])
    override fun updateAgent(id: Long, request: AgentUpdateRequest): Boolean = try {
        val agent = getAgent(id)
            ?: throw RuntimeException("Agent not found")

        request.name?.let { agent.name = it }
        request.description?.let { agent.description = it }
        request.systemPrompt?.let { agent.systemPrompt = it }
        request.modelId?.let { agent.modelId = it }
        request.owner?.let { agent.owner = it }
        request.isPublic?.let { agent.isPublic = it }

        // Update MCP list
        if (request.mcpList != null) {
            // Allow clearing MCP list
            if (request.mcpList.isEmpty()) {
                agent.mcpList = ""
            } else {
                // Store directly in JSON format: [{"id":1, "enable_skip":"true"},{"id":2, "enable_skip":"false"}]
                try {
                    agent.mcpList = objectMapper.writeValueAsString(request.mcpList)
                } catch (e: JacksonException) {
                    throw RuntimeException("Failed to serialize MCP list to JSON", e)
                }
            }
        }
        // If request.mcpList == null, keep original value unchanged

        // Update skill list
        if (request.skillList != null) {
            // skillList is string format "1,2,3" or empty string ""
            agent.skillList = (if (request.skillList.trim().isEmpty()) null else request.skillList).toString()
        }
        // If request.skillList == null, keep original value unchanged

        // Update tool list
        if (request.toolList != null) {
            if (request.toolList.isEmpty()) {
                agent.toolList = ""
            } else {
                try {
                    val validatedToolList = request.toolList.map { config ->
                        val toolEntity = agentToolService.getAgentTool(config.id ?: 0)
                        val finalNeedConfirm = if (toolEntity != null && toolEntity.needConfirm == 0) {
                            false
                        } else {
                            config.needConfirm ?: false
                        }
                        mapOf(
                            "id" to config.id,
                            "enable_skip" to (config.enableSkip ?: "true"),
                            "need_confirm" to finalNeedConfirm,
                        )
                    }
                    agent.toolList = objectMapper.writeValueAsString(validatedToolList)
                } catch (e: JacksonException) {
                    throw RuntimeException("Failed to serialize tool list to JSON", e)
                }
            }
        }

        agent.updateTime = LocalDateTime.now()
        agentMapper.updateById(agent)
        true
    } catch (e: Exception) {
        log.error("Failed to update agent", e)
        throw RuntimeException("Failed to update agent: ${e.message}")
    }

    override fun toggleAgentStatus(id: Long, status: Int): Boolean {
        val agent = agentMapper.selectById(id)
            ?: throw RuntimeException("Agent not found")
        return agentMapper.updateStatus(id, status) > 0
    }

    override fun deleteAgent(id: Long): Boolean = agentMapper.deleteById(id) > 0

    override fun getActiveAgents(): List<Agent> = agentMapper.selectAgentList(null, 1, "")

    /**
     * Convert Agent entity to response DTO (with complete skill and MCP information)
     */
    override fun convertToResponse(agent: Agent): AgentResponse {
        val response = AgentResponse()
        response.id = agent.id
        response.name = agent.name
        response.description = agent.description
        response.systemPrompt = agent.systemPrompt
        response.modelId = agent.modelId

        // Query model name and price
        agent.modelId.let { modelId ->
            val model = modelService.getModel(modelId)
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

        // Query associated session list
        val sessions = sessionMapper.selectByAgentId(agent.id)

        // Convert to SessionItem list
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

        // Parse MCP list (JSON format)
        if (agent.mcpList.isNotEmpty()) {
            try {
                // First deserialize to Map to get ID and enableSkip
                val mcpConfigs: List<Map<String, Any>> = objectMapper.readValue(
                    agent.mcpList,
                    object : TypeReference<List<Map<String, Any>>>() {},
                )

                // Query complete MCP information from database
                val mcpItems = mutableListOf<AgentResponse.McpItem>()
                for (config in mcpConfigs) {
                    val mcpId = (config["id"] as Number).toLong()
                    val enableSkip = config["enable_skip"] as String?

                    val fullMcp = mcpServerService.getMcpServer(mcpId)
                    fullMcp?.let {
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
                log.warn("Failed to parse MCP list", e)
                response.mcpList = mutableListOf()
            }
        }

        // Parse skill list (comma-separated string)
        if (agent.skillList.isNotEmpty()) {
            try {
                val skillIds = agent.skillList.split(",")
                val skillItems = mutableListOf<AgentResponse.SkillItem>()

                for (skillIdStr in skillIds) {
                    try {
                        val skillId = skillIdStr.trim().toLong()
                        // Query complete skill information from database
                        val skill = skillService.getSkill(skillId)
                        skill?.let { it ->
                            val item = AgentResponse.SkillItem()
                            item.skillId = it.id
                            item.skillName = it.name
                            item.skillDescription = it.description

                            // Query skill repository information
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

        // Parse tool list (JSON format)
        if (agent.toolList.isNotEmpty()) {
            try {
                val toolConfigs: List<Map<String, Any>> = objectMapper.readValue(
                    agent.toolList,
                    object : TypeReference<List<Map<String, Any>>>() {},
                )

                val toolItems = mutableListOf<AgentResponse.ToolItem>()
                for (config in toolConfigs) {
                    val toolId = (config["id"] as Number).toLong()
                    val enableSkip = config["enable_skip"] as? String
                    val needConfirm = config["need_confirm"] as? Boolean ?: false

                    val fullTool = agentToolService.getAgentTool(toolId)
                    fullTool?.let {
                        val item = AgentResponse.ToolItem(
                            toolId = it.id,
                            toolName = it.name,
                            toolDisplayName = it.displayName,
                            toolDescription = it.description,
                            toolType = it.type,
                            enableSkip = enableSkip,
                            needConfirm = needConfirm,
                        )
                        toolItems.add(item)
                    }
                }
                response.toolList = toolItems
            } catch (e: Exception) {
                log.warn("Failed to parse tool list", e)
                response.toolList = mutableListOf()
            }
        }

        return response
    }
}

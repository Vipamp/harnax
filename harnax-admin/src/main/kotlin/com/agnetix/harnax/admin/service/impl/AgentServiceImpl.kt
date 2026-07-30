package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.constant.BuiltinRepository
import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.AgentCreateRequest
import com.agnetix.harnax.admin.dto.AgentResponse
import com.agnetix.harnax.admin.dto.AgentUpdateRequest
import com.agnetix.harnax.admin.dto.EnvBinding
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.ToolConfig
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.*
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.entity.Agent
import com.agnetix.harnax.entity.AgentCliBinding
import com.agnetix.harnax.entity.AgentMcpBinding
import com.agnetix.harnax.entity.AgentSkillBinding
import com.agnetix.harnax.entity.AgentToolBinding
import com.agnetix.harnax.mapper.AgentCliBindingMapper
import com.agnetix.harnax.mapper.AgentMapper
import com.agnetix.harnax.mapper.AgentMcpBindingMapper
import com.agnetix.harnax.mapper.AgentSkillBindingMapper
import com.agnetix.harnax.mapper.AgentToolBindingMapper
import com.agnetix.harnax.mapper.CliMapper
import com.agnetix.harnax.mapper.CliSkillBindingMapper
import com.agnetix.harnax.mapper.SessionMapper
import com.agnetix.harnax.mapper.SkillMapper
import com.github.pagehelper.PageHelper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime

/**
 * Agent service implementation.
 * Uses normalized binding tables (agent_tool_binding, agent_mcp_binding, agent_skill_binding)
 * for storing tool/MCP/skill associations with environment variable binding snapshots.
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
    private val envVariableService: EnvVariableService,
    private val toolBindingMapper: AgentToolBindingMapper,
    private val mcpBindingMapper: AgentMcpBindingMapper,
    private val skillBindingMapper: AgentSkillBindingMapper,
    private val cliBindingMapper: AgentCliBindingMapper,
    private val cliMapper: CliMapper,
    private val cliSkillBindingMapper: CliSkillBindingMapper,
    private val skillMapper: SkillMapper,
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
        agent.isPublic = request.isPublic ?: 0
        agent.tenantId = TenantContext.getTenantId() ?: 1

        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        agent.creator = currentUsername!!

        agent.createTime = LocalDateTime.now()
        agent.updateTime = LocalDateTime.now()
        agentMapper.insert(agent)

        // Save to normalized binding tables
        saveToolBindings(agent.id, request.toolList)
        saveMcpBindings(agent.id, request.mcpList)
        saveSkillBindings(agent.id, request.skillList)
        saveCliBindings(agent.id, request.cliList)

        true
    } catch (e: BizException) {
        throw e
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

        agent.updateTime = LocalDateTime.now()
        agentMapper.updateById(agent)

        // Update binding tables (delete-then-insert pattern)
        if (request.toolList != null) {
            saveToolBindings(agent.id, request.toolList)
        }
        if (request.mcpList != null) {
            saveMcpBindings(agent.id, request.mcpList)
        }
        if (request.skillList != null) {
            saveSkillBindings(agent.id, request.skillList)
        }
        if (request.cliList != null) {
            saveCliBindings(agent.id, request.cliList)
        }

        true
    } catch (e: BizException) {
        throw e
    } catch (e: Exception) {
        log.error("Failed to update agent", e)
        throw RuntimeException("Failed to update agent: ${e.message}")
    }

    override fun toggleAgentStatus(id: Long, status: Int): Boolean {
        val agent = agentMapper.selectById(id)
            ?: throw RuntimeException("Agent not found")
        return agentMapper.updateStatus(id, status) > 0
    }

    override fun deleteAgent(id: Long): Boolean {
        // Clean up bindings before deleting agent
        toolBindingMapper.deleteByAgentId(id)
        mcpBindingMapper.deleteByAgentId(id)
        skillBindingMapper.deleteByAgentId(id)
        cliBindingMapper.deleteByAgentId(id)
        return agentMapper.deleteById(id) > 0
    }

    override fun getActiveAgents(): List<Agent> = agentMapper.selectAgentList(null, 1, "")

    /**
     * Convert Agent entity to response DTO.
     * Reads tool/mcp/skill associations from binding tables.
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

        // Read tool bindings from normalized table
        val toolBindings = toolBindingMapper.selectByAgentId(agent.id)
        if (toolBindings.isNotEmpty()) {
            val toolItems = mutableListOf<AgentResponse.ToolItem>()
            for (binding in toolBindings) {
                val fullTool = agentToolService.getAgentTool(binding.toolId) ?: continue
                toolItems.add(
                    AgentResponse.ToolItem(
                        toolId = fullTool.id,
                        toolName = fullTool.name,
                        toolDisplayName = fullTool.displayName,
                        toolDisplayNameZh = fullTool.displayNameZh,
                        toolDescription = fullTool.description,
                        toolType = fullTool.type,
                        enableSkip = binding.enableSkip,
                        needConfirm = binding.needConfirm == 1,
                        envBindings = parseEnvBindingsJson(binding.envBindings),
                    ),
                )
            }
            response.toolList = toolItems
        }

        // Read MCP bindings from normalized table
        val mcpBindings = mcpBindingMapper.selectByAgentId(agent.id)
        if (mcpBindings.isNotEmpty()) {
            val mcpItems = mutableListOf<AgentResponse.McpItem>()
            for (binding in mcpBindings) {
                val fullMcp = mcpServerService.getMcpServer(binding.mcpId) ?: continue
                val item = AgentResponse.McpItem()
                item.mcpId = fullMcp.id
                item.mcpName = fullMcp.name
                item.mcpDescription = fullMcp.description
                item.enableSkip = binding.enableSkip
                item.envBindings = parseEnvBindingsJson(binding.envBindings)
                mcpItems.add(item)
            }
            response.mcpList = mcpItems
        }

        // Read skill bindings from normalized table
        val skillBindings = skillBindingMapper.selectByAgentId(agent.id)
        if (skillBindings.isNotEmpty()) {
            val skillItems = mutableListOf<AgentResponse.SkillItem>()
            for (binding in skillBindings) {
                val skill = skillService.getSkill(binding.skillId) ?: continue
                val item = AgentResponse.SkillItem()
                item.skillId = skill.id
                item.skillName = skill.name
                item.skillDescription = skill.description
                val repository = skillRepositoryService.getSkillRepository(skill.repositoryId)
                repository?.let {
                    item.repositoryId = it.id
                    item.repositoryName = it.name
                }
                skillItems.add(item)
            }
            response.skillList = skillItems
        }

        // Read CLI bindings from normalized table
        val cliBindings = cliBindingMapper.selectByAgentId(agent.id)
        if (cliBindings.isNotEmpty()) {
            val cliIds = cliBindings.map { it.cliId }.distinct()
            val clisById = cliMapper.selectByIds(cliIds).associateBy { it.id }
            val skillBindingsByCli = cliSkillBindingMapper.selectByCliIds(cliIds).groupBy { it.cliId }
            val cliItems = mutableListOf<AgentResponse.CliItem>()
            for (binding in cliBindings) {
                val cli = clisById[binding.cliId] ?: continue
                val item = AgentResponse.CliItem()
                item.cliId = cli.id
                item.cliName = cli.name
                item.cliDescription = cli.description
                item.version = cli.version
                item.envBindings = parseEnvBindingsJson(binding.envBindings)
                val cliSkills = skillBindingsByCli[cli.id].orEmpty().mapNotNull { skillBinding ->
                    val skill = skillService.getSkill(skillBinding.skillId) ?: return@mapNotNull null
                    AgentResponse.SkillItem().apply {
                        skillId = skill.id
                        skillName = skill.name
                        skillDescription = skill.description
                    }
                }
                if (cliSkills.isNotEmpty()) {
                    item.skillList = cliSkills
                }
                cliItems.add(item)
            }
            response.cliList = cliItems
        }

        return response
    }

    // ========== Binding table save helpers ==========

    /**
     * Save tool bindings: delete old + insert new.
     * Builds env binding snapshots with envVarId + envVarName + envValue.
     */
    private fun saveToolBindings(agentId: Long, toolList: List<ToolConfig>?) {
        toolBindingMapper.deleteByAgentId(agentId)
        if (toolList.isNullOrEmpty()) return

        val now = LocalDateTime.now()
        val bindings = toolList.mapNotNull { config ->
            val toolId = config.id ?: return@mapNotNull null
            val toolEntity = agentToolService.getAgentTool(toolId)
            val finalNeedConfirm = if (toolEntity != null && toolEntity.needConfirm == 0) {
                0
            } else {
                if (config.needConfirm == true) 1 else 0
            }
            AgentToolBinding().apply {
                this.agentId = agentId
                this.toolId = toolId
                this.enableSkip = config.enableSkip ?: "false"
                this.needConfirm = finalNeedConfirm
                this.envBindings = serializeEnvBindings(config.envBindings)
                this.createTime = now
                this.updateTime = now
            }
        }
        if (bindings.isNotEmpty()) {
            toolBindingMapper.batchInsert(bindings)
        }
    }

    /**
     * Save MCP bindings: delete old + insert new.
     */
    private fun saveMcpBindings(agentId: Long, mcpList: List<AgentCreateRequest.McpConfig>?) {
        mcpBindingMapper.deleteByAgentId(agentId)
        if (mcpList.isNullOrEmpty()) return

        val now = LocalDateTime.now()
        val bindings = mcpList.mapNotNull { config ->
            val mcpId = config.id ?: return@mapNotNull null
            AgentMcpBinding().apply {
                this.agentId = agentId
                this.mcpId = mcpId
                this.enableSkip = config.enableSkip ?: "false"
                this.envBindings = serializeEnvBindings(config.envBindings)
                this.createTime = now
                this.updateTime = now
            }
        }
        if (bindings.isNotEmpty()) {
            mcpBindingMapper.batchInsert(bindings)
        }
    }

    /**
     * Save skill bindings: delete old + insert new.
     * Skills from the builtin CLI repository cannot be bound directly —
     * they are loaded automatically via the agent's CLI bindings.
     */
    private fun saveSkillBindings(agentId: Long, skillList: String?) {
        skillBindingMapper.deleteByAgentId(agentId)
        if (skillList.isNullOrBlank()) return

        val skillIds = skillList.split(",").mapNotNull { it.trim().toLongOrNull() }
        if (skillIds.isEmpty()) return

        val builtinRepo = skillRepositoryService.getByName(BuiltinRepository.CLI_SKILLS)
        if (builtinRepo == null) {
            log.warn("Builtin repository '{}' not found, skipping agent skill constraint", BuiltinRepository.CLI_SKILLS)
        } else {
            val invalid = skillMapper.selectByIds(skillIds).filter { it.repositoryId == builtinRepo.id }
            if (invalid.isNotEmpty()) {
                throw BizException(
                    "Skills from '${BuiltinRepository.CLI_SKILLS}' cannot be bound directly (auto-loaded via CLI): ${invalid.joinToString(",") { it.name }}",
                )
            }
        }

        val now = LocalDateTime.now()
        val bindings = skillIds.map { skillId ->
            AgentSkillBinding().apply {
                this.agentId = agentId
                this.skillId = skillId
                this.createTime = now
                this.updateTime = now
            }
        }
        if (bindings.isNotEmpty()) {
            skillBindingMapper.batchInsert(bindings)
        }
    }

    /**
     * Save CLI bindings: delete old + insert new.
     */
    private fun saveCliBindings(agentId: Long, cliList: List<AgentCreateRequest.CliConfig>?) {
        cliBindingMapper.deleteByAgentId(agentId)
        if (cliList.isNullOrEmpty()) return

        val cliIds = cliList.mapNotNull { it.id }.distinct()
        if (cliIds.isEmpty()) return

        val existingIds = cliMapper.selectByIds(cliIds).map { it.id }.toSet()
        val missing = cliIds - existingIds
        if (missing.isNotEmpty()) {
            throw BizException("CLI not found: $missing")
        }

        val now = LocalDateTime.now()
        val bindings = cliList.mapNotNull { config ->
            val cliId = config.id ?: return@mapNotNull null
            AgentCliBinding().apply {
                this.agentId = agentId
                this.cliId = cliId
                this.envBindings = serializeEnvBindings(config.envBindings)
                this.createTime = now
                this.updateTime = now
            }
        }
        if (bindings.isNotEmpty()) {
            cliBindingMapper.batchInsert(bindings)
        }
    }

    // ========== Env binding serialization helpers ==========

    /**
     * Serialize env bindings to JSON snapshot.
     * For bindings with envVarId, resolves envVarName and envValue from env_variable table.
     */
    private fun serializeEnvBindings(bindings: List<EnvBinding>?): String? {
        if (bindings.isNullOrEmpty()) return null

        val snapshots = bindings.map { binding ->
            val snapshot = mutableMapOf<String, Any?>("envKey" to binding.envKey)

            if (binding.envVarId != null) {
                snapshot["envVarId"] = binding.envVarId
                // Resolve envVarName and envValue from DB
                val envVar = envVariableService.getEnvVariable(binding.envVarId)
                snapshot["envVarName"] = binding.envVarName ?: envVar?.envKey
                snapshot["envValue"] = binding.envValue ?: envVariableService.getDecryptedValue(binding.envVarId)
            } else if (binding.customValue != null) {
                snapshot["customValue"] = binding.customValue
            } else if (binding.envValue != null) {
                // No envVarId reference — treat plain envValue as custom input
                snapshot["customValue"] = binding.envValue
            }

            snapshot
        }
        return objectMapper.writeValueAsString(snapshots)
    }

    /**
     * Parse env_bindings JSON string from binding table to List<EnvBinding>.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseEnvBindingsJson(json: String?): List<EnvBinding>? {
        if (json.isNullOrBlank()) return null
        return try {
            val list: List<Map<String, Any?>> = objectMapper.readValue(
                json,
                object : TypeReference<List<Map<String, Any?>>>() {},
            )
            list.map { entry ->
                val envVarId = (entry["envVarId"] as? Number)?.toLong()
                val snapshotValue = entry["envValue"]?.toString()
                val customValue = entry["customValue"]?.toString()
                // Legacy compat: if no envVarId but envValue exists, treat as custom input
                val effectiveCustomValue = customValue ?: if (envVarId == null && snapshotValue != null) snapshotValue else null

                // Display the effective runtime value: for env-var references,
                // resolve the LATEST value (runtime does the same via
                // resolveEnvBindingsJson); fall back to the stored snapshot.
                // Sensitive variables are masked for display.
                val displayValue = if (envVarId != null) {
                    val envVar = envVariableService.getEnvVariable(envVarId)
                    if (envVar != null && envVar.sensitive == 1) {
                        "******"
                    } else {
                        envVariableService.getDecryptedValue(envVarId) ?: snapshotValue
                    }
                } else {
                    snapshotValue
                }

                EnvBinding(
                    envKey = entry["envKey"]?.toString() ?: "",
                    envValue = if (effectiveCustomValue != null) null else displayValue,
                    envVarId = envVarId,
                    envVarName = entry["envVarName"]?.toString(),
                    customValue = effectiveCustomValue,
                )
            }
        } catch (e: Exception) {
            log.warn("Failed to parse env_bindings JSON: {}", json, e)
            null
        }
    }
}

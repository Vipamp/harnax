package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.AgentCreateRequest
import com.agnetix.harnax.admin.dto.AgentResponse
import com.agnetix.harnax.admin.dto.AgentUpdateRequest
import com.agnetix.harnax.admin.dto.EnvBinding
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.ToolConfig
import com.agnetix.harnax.admin.dto.ToolEnvParamEntry
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.*
import com.agnetix.harnax.admin.skill.SkillBindingResolver
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.SecretFieldEncryptor
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.entity.Agent
import com.agnetix.harnax.entity.AgentCliBinding
import com.agnetix.harnax.entity.AgentMcpBinding
import com.agnetix.harnax.entity.AgentSkillBinding
import com.agnetix.harnax.entity.AgentTool
import com.agnetix.harnax.entity.AgentToolBinding
import com.agnetix.harnax.entity.McpServer
import com.agnetix.harnax.entity.Team
import com.agnetix.harnax.mapper.AgentCliBindingMapper
import com.agnetix.harnax.mapper.AgentMapper
import com.agnetix.harnax.mapper.AgentMcpBindingMapper
import com.agnetix.harnax.mapper.AgentSkillBindingMapper
import com.agnetix.harnax.mapper.AgentToolBindingMapper
import com.agnetix.harnax.mapper.AgentToolEnvParamMapper
import com.agnetix.harnax.mapper.AgentToolMapper
import com.agnetix.harnax.mapper.ChannelMapper
import com.agnetix.harnax.mapper.CliMapper
import com.agnetix.harnax.mapper.McpServerMapper
import com.agnetix.harnax.mapper.SessionMapper
import com.agnetix.harnax.mapper.TeamMapper
import com.agnetix.harnax.mapper.TeamMemberMapper
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
    private val channelMapper: ChannelMapper,
    private val jwtUtil: JwtUtil,
    private val envVariableService: EnvVariableService,
    private val toolBindingMapper: AgentToolBindingMapper,
    private val mcpBindingMapper: AgentMcpBindingMapper,
    private val skillBindingMapper: AgentSkillBindingMapper,
    private val cliBindingMapper: AgentCliBindingMapper,
    private val cliMapper: CliMapper,
    private val mcpServerMapper: McpServerMapper,
    private val agentToolMapper: AgentToolMapper,
    private val agentToolEnvParamMapper: AgentToolEnvParamMapper,
    private val secretFieldEncryptor: SecretFieldEncryptor,
    private val teamMapper: TeamMapper,
    private val teamMemberMapper: TeamMemberMapper,
    private val skillBindingResolver: SkillBindingResolver,
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
        val safePageNum = pageNum.coerceAtLeast(1)
        val safePageSize = pageSize.coerceIn(1, 1000)
        PageHelper.startPage<Agent>(safePageNum, safePageSize)
        return Page.fromPageInfo(agentMapper.selectAgentList(name, status, currentUsername, currentTenantId()))
    }

    /**
     * The write-side twin of the read rule in [convertToResponse].
     *
     * A binding stores a bare id, so an unchecked write lets any tenant point an agent at a row it was
     * only ever allowed to *use* — or at one it cannot see at all, which the read side then spends the
     * turn hiding. Existence and visibility answer with the same message: which of the two failed is
     * not something a caller may ask.
     */
    private fun requireVisibleModel(modelId: Long) {
        if (modelService.getVisibleModel(modelId) == null) {
            throw BizException("Model not found or not visible to the current tenant: $modelId")
        }
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun createAgent(request: AgentCreateRequest): Boolean = try {
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)

        // Checked before the insert so a clash is a usable message rather than the SQL error the
        // unique key V43 adds would raise - the same two-layer shape as uk_mcp_server_tenant_active_name
        val tenantId = currentTenantId()
        if (agentMapper.selectByName(request.name!!, tenantId) != null) {
            throw BizException("Agent name already exists")
        }
        requireVisibleModel(request.modelId!!)

        val agent = Agent()
        agent.name = request.name!!
        agent.description = request.description!!
        agent.systemPrompt = request.systemPrompt!!
        agent.modelId = request.modelId!!
        agent.owner = currentUsername
        agent.status = request.status ?: 1
        agent.isPublic = request.isPublic ?: 0
        agent.tenantId = tenantId
        agent.creator = currentUsername

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

    /**
     * Single-row access to `agent`.
     *
     * The list query filters by tenant, so a by-id read that does not would make that filter
     * cosmetic: any row could be opened, edited or deleted by guessing its id. A row outside the
     * current tenant answers as a missing one.
     */
    override fun getAgent(id: Long): Agent? = agentMapper.selectById(id)?.takeIf { it.tenantId == currentTenantId() }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateAgent(id: Long, request: AgentUpdateRequest): Boolean = try {
        val agent = getAgent(id)
            ?: throw RuntimeException("Agent not found")

        request.name?.let { name ->
            // Only a rename can collide: keeping the stored name would match this very row.
            if (name != agent.name && agentMapper.selectByName(name, agent.tenantId) != null) {
                throw BizException("Agent name already exists")
            }
            agent.name = name
        }
        request.description?.let { agent.description = it }
        request.systemPrompt?.let { agent.systemPrompt = it }
        request.modelId?.let { modelId ->
            requireVisibleModel(modelId)
            agent.modelId = modelId
        }
        request.isPublic?.let { agent.isPublic = it }

        agent.updateTime = LocalDateTime.now()
        agentMapper.updateById(agent)

        // Start/stop goes through the statement the toggle endpoint uses, so an update that carries a
        // status cannot land somewhere the switch does not. getAgent above already cleared the tenant.
        request.status?.let { agentMapper.updateStatus(id, it) }

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
        // Read through getAgent, otherwise the tenant guard on single-row access is bypassed by this writer.
        getAgent(id) ?: throw RuntimeException("Agent not found")
        return agentMapper.updateStatus(id, status) > 0
    }

    override fun deleteAgent(id: Long): Boolean {
        val agent = agentMapper.selectById(id)
        if (agent != null && agent.tenantId != currentTenantId()) {
            throw RuntimeException("Agent not found")
        }
        // A team that delegates to a deleted agent refuses every later run, so the reference has to be
        // released here rather than discovered by whoever starts the conversation next. Membership is
        // the only team reference an agent can have: a team's lead is the team row itself.
        fun liveTeams(teams: List<Team>) = teams.filter { it.tenantId == currentTenantId() }
        val memberOf = liveTeams(
            teamMemberMapper.selectByMemberAgentId(id).mapNotNull { teamMapper.selectById(it.teamId) },
        )
        if (memberOf.isNotEmpty()) {
            throw BizException(
                "This agent is a member of team(s): ${memberOf.joinToString(", ") { it.name }}. " +
                    "Remove it from these teams before deleting it.",
            )
        }
        // A session or a channel resolves its agent by id at run time, so deleting this row would leave
        // them failing on the next message. Both references have to be released first.
        val sessions = sessionMapper.countByAgentId(id)
        val running = sessionMapper.countRunningByAgentId(id)
        val channels = channelMapper.selectByAgentId(id)
        val refs = buildList {
            if (sessions > 0) {
                val progress = if (running > 0) ", $running of them still in progress" else ""
                add("$sessions session(s)$progress")
            }
            if (channels.isNotEmpty()) add("channel(s): ${channels.joinToString(", ") { it.name }}")
        }
        if (refs.isNotEmpty()) {
            throw BizException(
                "This agent is still used by ${refs.joinToString(" and ")}. " +
                    "Remove these before deleting it.",
            )
        }
        // Clean up bindings before deleting agent
        toolBindingMapper.deleteByAgentId(id)
        mcpBindingMapper.deleteByAgentId(id)
        skillBindingMapper.deleteByAgentId(id)
        cliBindingMapper.deleteByAgentId(id)
        return agentMapper.deleteById(id) > 0
    }

    // `selectAgentList` always applies `(is_public = 1 OR creator = #{currentUsername})`, so passing
    // anything but the caller's name silently narrows the list to public agents — which is how a
    // private agent showed up on the agent page but never in the scheduled-task dropdown
    override fun getActiveAgents(): List<Agent> = agentMapper.selectAgentList(null, 1, UserContextUtil.getCurrentUsername(jwtUtil), currentTenantId())

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

        // Resolved through the model list's own visibility rule: a binding carries a bare id, and echoing
        // the name and price of a model this tenant may not see would leak the row it points at.
        agent.modelId.let { modelId ->
            val model = modelService.getVisibleModel(modelId)
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
            val cliItems = mutableListOf<AgentResponse.CliItem>()
            for (binding in cliBindings) {
                val cli = clisById[binding.cliId] ?: continue
                val item = AgentResponse.CliItem()
                item.cliId = cli.id
                item.cliName = cli.name
                item.cliDescription = cli.description
                item.version = cli.version
                item.envBindings = parseEnvBindingsJson(binding.envBindings)
                // One skill per CLI, the one its package shipped (design D3): the agent page shows it so
                // the checkbox reads as "this CLI and what it teaches the agent", not as an unrelated
                // skill that happens to share a name
                val shippedSkill = cli.skillId?.let { skillId -> skillService.getSkill(skillId) }?.let { skill ->
                    listOf(
                        AgentResponse.SkillItem().apply {
                            skillId = skill.id
                            skillName = skill.name
                            skillDescription = skill.description
                        },
                    )
                }
                if (!shippedSkill.isNullOrEmpty()) {
                    item.skillList = shippedSkill
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
        val toolIds = toolList.mapNotNull { it.id }.distinct()
        val toolsById = resolveBindableTools(toolIds).associateBy { it.id }
        val bindings = toolList.mapNotNull { config ->
            val toolId = config.id ?: return@mapNotNull null
            val tool = toolsById[toolId] ?: return@mapNotNull null
            assertEnvBindingsBindable(config.envBindings, "tool '${tool.name}'")
            assertRequiredEnvParamsFilled(
                "tool '${tool.name}'",
                // The tool's own default never reaches a builtin tool at runtime (only the binding
                // values are delivered into ToolEnvContext), so it cannot stand in for a required param.
                loadToolEnvParams(toolId),
                config.envBindings,
                defaultValueCounts = false,
            )
            AgentToolBinding().apply {
                this.agentId = agentId
                this.toolId = toolId
                // Runtime ORs agent_tool.needConfirm with this value, so the binding level can only
                // tighten confirmation, never lift a tool that the code declares as confirm-worthy.
                this.needConfirm = if (config.needConfirm == true) 1 else 0
                this.envBindings = serializeEnvBindings(config.envBindings)
                this.createTime = now
                this.updateTime = now
            }
        }.distinctBy { it.toolId }
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
        val mcpIds = mcpList.mapNotNull { it.id }.distinct()
        val serversById = resolveBindableMcpServers(mcpIds).associateBy { it.id }
        val bindings = mcpList.mapNotNull { config ->
            val mcpId = config.id ?: return@mapNotNull null
            val server = serversById[mcpId] ?: return@mapNotNull null
            assertEnvBindingsBindable(config.envBindings, "MCP server '${server.name}'")
            assertRequiredEnvParamsFilled(
                "MCP server '${server.name}'",
                secretFieldEncryptor.deserializeToolEnvEntries(server.envParams),
                config.envBindings,
                // Only a stdio row has anything in this column now, and for that row the claim holds:
                // delivery sends `env_params` and the runtime spawns the process with them, so a
                // declared default really does answer a required param. A network row yields no
                // declarations here at all, which is why it has nothing to fill.
                defaultValueCounts = true,
            )
            AgentMcpBinding().apply {
                this.agentId = agentId
                this.mcpId = mcpId
                this.envBindings = serializeEnvBindings(config.envBindings)
                this.createTime = now
                this.updateTime = now
            }
        }.distinctBy { it.mcpId }
        if (bindings.isNotEmpty()) {
            mcpBindingMapper.batchInsert(bindings)
        }
    }

    /**
     * Tool rows the agent may be bound to, out of [toolIds].
     *
     * Same reasoning as [resolveBindableMcpServers]: a binding whose tool row is gone is not an error
     * at write time, but delivery drops it with only a log line, so the operator loses a tool without
     * a signal. Unlike MCP servers, tools carry no tenant scope: every row is written by the startup
     * sync for the default tenant and offered to all tenants, so the check is existence only.
     */
    private fun resolveBindableTools(toolIds: List<Long>): List<AgentTool> {
        if (toolIds.isEmpty()) return emptyList()
        val resolvable = agentToolMapper.selectByIds(toolIds)
        val missing = toolIds - resolvable.map { it.id }.toSet()
        if (missing.isNotEmpty()) {
            throw BizException("Tool is missing or deleted: ${missing.joinToString(",")}")
        }
        return resolvable
    }

    /**
     * Server rows behind [mcpIds], rejecting the ones that cannot be delivered.
     *
     * A binding whose server is gone is not an error at write time any more, but it is one at
     * runtime: delivery resolves it with `?: continue`, so the agent simply stops seeing that tool
     * and nothing tells the operator. `selectByIds` already excludes `active = 0`, and the tenant
     * comparison mirrors `McpServerService.getMcpServer`, which is what the resolver goes through.
     * A disabled row is refused for the same reason skills refuse one: delivery holds it back too,
     * so binding it would only park a tool the agent can never reach.
     * Rows are returned rather than ids because the caller needs `env_params` to check required params.
     */
    private fun resolveBindableMcpServers(mcpIds: List<Long>): List<McpServer> {
        if (mcpIds.isEmpty()) return emptyList()
        val tenantId = currentTenantId()
        val resolvable = mcpServerMapper.selectByIds(mcpIds).filter { it.tenantId == tenantId }
        val missing = mcpIds - resolvable.map { it.id }.toSet()
        if (missing.isNotEmpty()) {
            throw BizException(
                "MCP server is missing, deleted, or outside your tenant: ${missing.joinToString(",")}",
            )
        }
        val disabled = resolvable.filter { it.status != 1 }
        if (disabled.isNotEmpty()) {
            throw BizException("MCP server is disabled, enable it before binding: ${disabled.joinToString(",") { it.name }}")
        }
        return resolvable
    }

    private fun currentTenantId(): Long = TenantContext.getTenantId() ?: 1

    /**
     * Save skill bindings: delete old + insert new.
     *
     * Skills carry no env: unlike [saveToolBindings] / [saveMcpBindings], whose bindings are resolved
     * on delivery, a skill binding has nothing beyond the pair of ids — the reserved `env_bindings`
     * column was dropped by V36 once it was clear no consumer would arrive.
     */
    private fun saveSkillBindings(agentId: Long, skillList: String?) {
        skillBindingMapper.deleteByAgentId(agentId)
        if (skillList.isNullOrBlank()) return

        val skillIds = skillList.split(",").mapNotNull { it.trim().toLongOrNull() }
        if (skillIds.isEmpty()) return

        val boundSkills = skillBindingResolver.resolveBindable(skillIds)
        if (boundSkills.isEmpty()) return

        val now = LocalDateTime.now()
        val bindings = boundSkills.map { skill ->
            AgentSkillBinding().apply {
                this.agentId = agentId
                this.skillId = skill.id
                this.createTime = now
                this.updateTime = now
            }
        }
        skillBindingMapper.batchInsert(bindings)
    }

    /**
     * Save CLI bindings: delete old + insert new.
     */
    private fun saveCliBindings(agentId: Long, cliList: List<AgentCreateRequest.CliConfig>?) {
        cliBindingMapper.deleteByAgentId(agentId)
        if (cliList.isNullOrEmpty()) return

        val cliIds = cliList.mapNotNull { it.id }.distinct()
        if (cliIds.isEmpty()) return

        val clisById = cliMapper.selectByIds(cliIds).associateBy { it.id }
        val missing = cliIds - clisById.keys
        if (missing.isNotEmpty()) {
            throw BizException("CLI not found: $missing")
        }

        val now = LocalDateTime.now()
        val bindings = cliList.mapNotNull { config ->
            val cliId = config.id ?: return@mapNotNull null
            val cli = clisById[cliId] ?: return@mapNotNull null
            // Same check tools and MCP servers run: this column is delivered into the sandbox env too
            // (`mergeCliEnvBindings`), so an unverified reference here resolves another tenant's secret
            assertEnvBindingsBindable(config.envBindings, "CLI '${cli.name}'")
            assertRequiredEnvParamsFilled(
                "CLI '${cli.name}'",
                secretFieldEncryptor.deserializeToolEnvEntries(cli.envParams),
                config.envBindings,
                // Unlike a builtin tool, a CLI's declared default does reach the sandbox: delivery merges
                // `cli.env_params` under the binding values, so a default answers a required param.
                defaultValueCounts = true,
            )
            AgentCliBinding().apply {
                this.agentId = agentId
                this.cliId = cliId
                this.envBindings = serializeEnvBindings(config.envBindings)
                this.createTime = now
                this.updateTime = now
            }
        }.distinctBy { it.cliId }
        if (bindings.isNotEmpty()) {
            cliBindingMapper.batchInsert(bindings)
        }
    }

    // ========== Env binding serialization helpers ==========

    /**
     * Serialize env bindings to JSON snapshot.
     * A reference stores the pointer only; [parseEnvBindingsJson] and delivery resolve its value live.
     */
    private fun serializeEnvBindings(bindings: List<EnvBinding>?): String? {
        if (bindings.isNullOrEmpty()) return null

        val snapshots = bindings.map { binding ->
            val snapshot = mutableMapOf<String, Any?>("envKey" to binding.envKey)

            if (binding.envVarId != null) {
                snapshot["envVarId"] = binding.envVarId
                // Resolve envVarName from DB
                snapshot["envVarName"] = binding.envVarName ?: envVariableService.getRowWithinTenant(binding.envVarId)?.envKey
                // No value is stored for a reference. What the client sends here is the read API's
                // display value — `******` for a sensitive variable — and snapshotting that turns the
                // stars into the fallback a tool receives once the variable is gone; resolving it
                // server-side instead would write a plaintext secret into this column. Delivery
                // follows the pointer live (`resolveEnvBindingsJson`).
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
     * Reject a save whose env bindings would not resolve the way the form shows them.
     *
     * Two distinct failures, both knowable at save time:
     *
     * - One key filled from two sources. Delivery emits one `{envKey, envValue}` pair per binding row
     *   and the runtime folds those pairs into a name-keyed map (`AgentSpecResolver.parseEnvBindings`,
     *   where the last row wins), so which credential actually reaches the tool is decided by ordering.
     *   Key uniqueness is scoped to one creator since V46, so this is reachable: two users can each hold
     *   an `OPENAI_KEY`, and an agent shared across them would otherwise pick one by accident.
     * - A reference that does not resolve. Only the pointer is stored, and delivery follows it with
     *   `getDecryptedValue(envVarId, agentTenantId)`, which answers null for a row that is gone, disabled,
     *   or outside the agent's tenant. The save has to run the same three checks, and it has to run them
     *   at that scope rather than the console's creator scope — [EnvVariableService.getRowWithinTenant]
     *   — or a collaborator editing a shared agent would be refused pointers that do arrive at runtime.
     */
    private fun assertEnvBindingsBindable(bindings: List<EnvBinding>?, target: String) {
        // One key with two sources is decided by ordering alone, so which credential reaches the tool
        // becomes an accident. Repeating a key with the same source is kept: a server whose declared
        // params list one name twice yields exactly those rows, and both resolve to the same thing.
        val sources = bindings.orEmpty().groupBy { it.envKey }.mapValues { (_, rows) -> rows.map { it.boundSource() }.distinct() }
        val ambiguous = sources.filterValues { it.size > 1 }.keys
        if (ambiguous.isNotEmpty()) {
            throw BizException(
                "$target binds one env key to several variables or values, keep one: ${ambiguous.joinToString(",")}",
            )
        }

        val ids = bindings?.mapNotNull { it.envVarId }?.distinct().orEmpty()
        if (ids.isEmpty()) return
        val tenantId = currentTenantId()
        val rows = ids.associateWith { envVariableService.getRowWithinTenant(it) }
        val unresolved = ids.filter { rows[it]?.tenantId != tenantId }
        if (unresolved.isNotEmpty()) {
            throw BizException(
                "$target references an env variable that is missing, deleted, or outside your tenant: ${unresolved.joinToString(",")}",
            )
        }
        // Disabled ones resolve to nothing at delivery now (`getDecryptedValue` answers null), so a
        // save that binds one would look filled in the form and arrive empty at runtime.
        val disabled = rows.values.filterNotNull().filter { it.enabled != 1 }.map { it.envKey }
        if (disabled.isNotEmpty()) {
            throw BizException(
                "$target references a disabled env variable, enable it before binding: ${disabled.joinToString(",")}",
            )
        }
    }

    /**
     * What one binding resolves to: a pointer to a variable, or the literal it carries.
     *
     * The branches follow [serializeEnvBindings] one for one, because that is what decides the stored
     * row - an unfilled form row (`envValue` empty) and a reference have to compare as the two distinct
     * sources they are, and two unfilled rows for the same key as the one source they share.
     */
    private fun EnvBinding.boundSource(): String = when {
        envVarId != null -> "var:$envVarId"
        customValue != null -> "value:$customValue"
        envValue != null -> "value:$envValue"
        else -> "unset"
    }

    /**
     * Reject a save that leaves a required env param with nothing to resolve at runtime.
     *
     * [defaultValueCounts] says whether the parameter's own stored default can answer the requirement;
     * each caller passes what follows from where that default actually goes at runtime.
     */
    private fun assertRequiredEnvParamsFilled(
        target: String,
        entries: List<ToolEnvParamEntry>,
        bindings: List<EnvBinding>?,
        defaultValueCounts: Boolean,
    ) {
        val byKey = bindings.orEmpty().associateBy { it.envKey }
        val missing = entries.filter { entry ->
            entry.required && !entry.hasRuntimeValue(byKey[entry.envParamName], defaultValueCounts)
        }.map { it.envParamName }
        if (missing.isNotEmpty()) {
            throw BizException(
                "$target requires env params that are left without a value: ${missing.joinToString(",")}",
            )
        }
    }

    private fun ToolEnvParamEntry.hasRuntimeValue(binding: EnvBinding?, defaultValueCounts: Boolean): Boolean {
        // A reference counts without seeing its value: delivery resolves it from the DB.
        if (binding?.envVarId != null) return true
        val typed = binding?.customValue ?: binding?.envValue
        // Masked text is a display artefact travelling back through the form, not a value.
        if (!typed.isNullOrBlank() && !typed.contains("****")) return true
        return defaultValueCounts && !defaultValue.isNullOrBlank()
    }

    /**
     * Parameter definitions of a tool, read from the table: [AgentToolService] hands out the masked
     * view meant for display, which cannot tell an absent default from a redacted one.
     */
    private fun loadToolEnvParams(toolId: Long): List<ToolEnvParamEntry> = agentToolEnvParamMapper.selectByToolId(toolId).map { entity ->
        ToolEnvParamEntry(
            id = entity.id,
            envParamName = entity.envParamName,
            description = entity.description,
            required = entity.required == 1,
            secret = entity.secret == 1,
            defaultValue = entity.defaultValue,
        )
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
                    val envVar = envVariableService.getRowWithinTenant(envVarId)
                    if (envVar != null && envVar.sensitive == 1) {
                        "******"
                    } else {
                        envVariableService.getDecryptedValue(envVarId, currentTenantId()) ?: snapshotValue
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

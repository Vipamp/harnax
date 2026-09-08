package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.constant.BuiltinRepository
import com.agnetix.harnax.admin.service.EnvVariableService
import com.agnetix.harnax.admin.util.AesUtil
import com.agnetix.harnax.admin.util.SecretFieldEncryptor
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.entity.Model
import com.agnetix.harnax.entity.dto.AgentSpecInfoResponse
import com.agnetix.harnax.entity.dto.CliDetailDto
import com.agnetix.harnax.entity.dto.McpDetailDto
import com.agnetix.harnax.entity.dto.ModelConfigDto
import com.agnetix.harnax.entity.dto.SkillDetailDto
import com.agnetix.harnax.entity.dto.TaskAgentSpecResponse
import com.agnetix.harnax.entity.dto.ToolDetailDto
import com.agnetix.harnax.mapper.AgentCliBindingMapper
import com.agnetix.harnax.mapper.AgentMapper
import com.agnetix.harnax.mapper.AgentMcpBindingMapper
import com.agnetix.harnax.mapper.AgentSkillBindingMapper
import com.agnetix.harnax.mapper.AgentTaskMapper
import com.agnetix.harnax.mapper.AgentToolBindingMapper
import com.agnetix.harnax.mapper.AgentToolMapper
import com.agnetix.harnax.mapper.ApiKeyMapper
import com.agnetix.harnax.mapper.ChannelMapper
import com.agnetix.harnax.mapper.CliMapper
import com.agnetix.harnax.mapper.CliSkillBindingMapper
import com.agnetix.harnax.mapper.McpServerMapper
import com.agnetix.harnax.mapper.ModelMapper
import com.agnetix.harnax.mapper.ModelProviderMapper
import com.agnetix.harnax.mapper.SessionMapper
import com.agnetix.harnax.mapper.SkillMapper
import com.agnetix.harnax.mapper.SkillRepositoryMapper
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

@RestController
@RequestMapping("/api/admin/internal")
class InternalApiController(
    private val apiKeyMapper: ApiKeyMapper,
    private val sessionMapper: SessionMapper,
    private val modelMapper: ModelMapper,
    private val modelProviderMapper: ModelProviderMapper,
    private val aesUtil: AesUtil,
    private val secretFieldEncryptor: SecretFieldEncryptor,
    private val agentTaskMapper: AgentTaskMapper,
    private val agentMapper: AgentMapper,
    private val channelMapper: ChannelMapper,
    private val toolBindingMapper: AgentToolBindingMapper,
    private val mcpBindingMapper: AgentMcpBindingMapper,
    private val skillBindingMapper: AgentSkillBindingMapper,
    private val envVariableService: EnvVariableService,
    private val agentToolMapper: AgentToolMapper,
    private val mcpServerMapper: McpServerMapper,
    private val skillMapper: SkillMapper,
    private val skillRepositoryMapper: SkillRepositoryMapper,
    private val cliBindingMapper: AgentCliBindingMapper,
    private val cliMapper: CliMapper,
    private val cliSkillBindingMapper: CliSkillBindingMapper,
) {

    private val log = LoggerFactory.getLogger(InternalApiController::class.java)
    private val objectMapper = ObjectMapper()

    data class ApiKeyValidateRequest(val keyHash: String)

    data class ApiKeyValidateResponse(
        val name: String,
        val keyHash: String,
        val scopes: String,
        val tenantId: Long?,
        val rateLimit: Int,
        val enabled: Boolean,
        val expiresAt: String?,
    )

    data class SessionInfoResponse(
        val sessionId: String,
        val agentId: Long?,
        val agentName: String?,
        val modelId: Long?,
        val modelName: String?,
        val tenantId: Long?,
    )

    data class SystemKeyRequest(val serviceName: String)

    data class SystemKeyResponse(val rawKey: String, val keyPrefix: String)

    /**
     * List all active skills from the built-in skill repository.
     * Used by agent-service to preload built-in skills at startup.
     *
     * The lookup is tenant-agnostic and ordered by id: this endpoint runs without a tenant
     * context, and the builtin repository is a single platform-wide row shared by every tenant.
     */
    @GetMapping("/builtin-skills")
    fun getBuiltinSkills(): ResultVo<List<SkillDetailDto>> {
        val repo = skillRepositoryMapper.selectBuiltinRepository(BuiltinRepository.CLI_SKILLS)
            ?: return ResultVo.success(emptyList())
        val skills = skillMapper.selectByRepositoryId(repo.id).filter { it.status == 1 }
        return ResultVo.success(
            skills.map { skill ->
                SkillDetailDto(
                    id = skill.id,
                    name = skill.name,
                    description = skill.description,
                    skillmd = skill.skillmd,
                    resources = skill.resources,
                    version = skill.version,
                )
            },
        )
    }

    @PostMapping("/api-keys/validate")
    fun validateApiKey(@RequestBody request: ApiKeyValidateRequest): ResultVo<ApiKeyValidateResponse?> {
        val entity = apiKeyMapper.selectByKeyHash(request.keyHash)
        if (entity == null) {
            log.debug("API key not found for hash: ${request.keyHash.take(16)}...")
            return ResultVo.success(null)
        }

        val response = ApiKeyValidateResponse(
            name = entity.name,
            keyHash = entity.keyHash,
            scopes = entity.scopes,
            tenantId = entity.tenantId,
            rateLimit = entity.rateLimit,
            enabled = entity.enabled == 1,
            expiresAt = entity.expiresAt?.toString(),
        )
        return ResultVo.success(response)
    }

    @GetMapping("/sessions/{sessionId}/info")
    fun getSessionInfo(@PathVariable sessionId: String): ResultVo<SessionInfoResponse?> {
        val session = sessionMapper.selectBySessionIdAndStatus(sessionId, 1)
        if (session == null) {
            log.debug("Session not found: $sessionId")
            return ResultVo.success(null)
        }

        val modelName = if (session.modelId > 0) {
            modelMapper.selectById(session.modelId)?.modelName
        } else {
            null
        }

        val response = SessionInfoResponse(
            sessionId = session.sessionId,
            agentId = session.agentId,
            agentName = session.name,
            modelId = session.modelId,
            modelName = modelName,
            tenantId = session.tenantId,
        )
        return ResultVo.success(response)
    }

    @PostMapping("/api-keys/system-key")
    fun getSystemKey(@RequestBody request: SystemKeyRequest): ResultVo<SystemKeyResponse?> {
        val entity = apiKeyMapper.selectSystemKeyByServiceName(request.serviceName)
        if (entity == null) {
            log.warn("System key not found for service: ${request.serviceName}")
            return ResultVo.success(null)
        }
        val rawKey = aesUtil.decrypt(entity.rawKeyEncrypted!!)
        return ResultVo.success(SystemKeyResponse(rawKey = rawKey, keyPrefix = entity.keyPrefix))
    }

    // ========================================
    // Agent Spec (unified, for agent-service)
    // ========================================

    /**
     * Unified endpoint: resolve agent spec by sessionId prefix.
     * - web-* / mp-*: session table → agent
     * - chn-*: channel table → agent
     * - task-{taskId}-*: agent_task table → agent
     */
    @GetMapping("/agent-spec/{sessionId}")
    fun getAgentSpec(@PathVariable sessionId: String): ResultVo<AgentSpecInfoResponse> = try {
        val spec = when {
            sessionId.startsWith("web-") || sessionId.startsWith("mp-") -> resolveFromSession(sessionId)
            sessionId.startsWith("chn-") -> resolveFromChannel(sessionId)
            sessionId.startsWith("task-") -> resolveFromTask(sessionId)
            else -> return ResultVo.error("Unknown sessionId prefix: $sessionId")
        }
        ResultVo.success(spec)
    } catch (e: Exception) {
        log.error("Failed to get agent spec: sessionId={}", sessionId, e)
        ResultVo.error("Failed to get agent spec: ${e.message}")
    }

    /** web/mp: session table → agent. */
    private fun resolveFromSession(sessionId: String): AgentSpecInfoResponse {
        val session = sessionMapper.selectBySessionIdAndStatus(sessionId, 1)
            ?: throw IllegalArgumentException("Session not found: $sessionId")
        val agent = agentMapper.selectById(session.agentId)
            ?: throw IllegalArgumentException("Agent not found: ${session.agentId}")
        log.info("[Admin] Resolved agent spec from session: sessionId={}, agentId={}", sessionId, agent.id)
        val model = modelMapper.selectById(agent.modelId)
        return buildAgentSpecResponse(
            agentId = agent.id,
            agentName = agent.name,
            description = agent.description,
            systemPrompt = agent.systemPrompt,
            modelId = agent.modelId,
            model = model,
            enableThink = session.enableThink,
            enableSearch = session.enableSearch,
            enablePlan = session.enablePlan,
            permissionMode = session.permissionMode,
        )
    }

    /** chn: channel table → agent. */
    private fun resolveFromChannel(sessionId: String): AgentSpecInfoResponse {
        val channel = channelMapper.selectBySessionId(sessionId)
            ?: throw IllegalArgumentException("Channel not found for sessionId: $sessionId")
        val agent = agentMapper.selectById(channel.agentId)
            ?: throw IllegalArgumentException("Agent not found: ${channel.agentId}")
        log.info("[Admin] Resolved agent spec from channel: sessionId={}, agentId={}", sessionId, agent.id)
        val model = modelMapper.selectById(agent.modelId)
        return buildAgentSpecResponse(
            agentId = agent.id,
            agentName = agent.name,
            description = agent.description,
            systemPrompt = agent.systemPrompt,
            modelId = agent.modelId,
            model = model,
            permissionMode = channel.permissionMode,
            enableThink = channel.enableThink,
            enableSearch = channel.enableSearch,
            enablePlan = channel.enablePlan,
        )
    }

    /** task: agent_task table → agent. */
    private fun resolveFromTask(sessionId: String): AgentSpecInfoResponse {
        val parts = sessionId.split("-", limit = 3)
        val taskId = parts[1].toLongOrNull()
            ?: throw IllegalArgumentException("Invalid task sessionId, cannot parse taskId: $sessionId")
        val task = agentTaskMapper.selectById(taskId)
            ?: throw IllegalArgumentException("Agent task not found: $taskId")
        val agent = agentMapper.selectById(task.agentId)
            ?: throw IllegalArgumentException("Agent not found: ${task.agentId}")
        log.info("[Admin] Resolved agent spec from task: sessionId={}, taskId={}, agentId={}", sessionId, taskId, agent.id)
        val model = modelMapper.selectById(agent.modelId)
        return buildAgentSpecResponse(
            agentId = agent.id,
            agentName = agent.name,
            description = agent.description,
            systemPrompt = agent.systemPrompt,
            modelId = agent.modelId,
            model = model,
            permissionMode = "BYPASS",
        )
    }

    // ========================================
    // Binding table helpers
    // ========================================

    /**
     * Build AgentSpecInfoResponse with binding table data serialized as JSON,
     * and full detail DTOs for model/tools/MCPs/skills.
     * Env bindings are resolved: envVarId → latest value, fallback to snapshot.
     */
    private fun buildAgentSpecResponse(
        agentId: Long,
        agentName: String,
        description: String,
        systemPrompt: String,
        modelId: Long,
        model: Model? = null,
        enableThink: Int = 0,
        enableSearch: Int = 0,
        enablePlan: Int = 0,
        permissionMode: String = "DEFAULT",
    ): AgentSpecInfoResponse {
        // ── Tool bindings (JSON for backward compat + full detail DTOs) ──
        val toolBindings = toolBindingMapper.selectByAgentId(agentId)
        val toolListJson = if (toolBindings.isEmpty()) {
            "[]"
        } else {
            val items = toolBindings.map { binding ->
                mapOf(
                    "id" to binding.toolId,
                    "need_confirm" to (binding.needConfirm == 1),
                    "env_bindings" to resolveEnvBindingsJson(binding.envBindings),
                )
            }
            objectMapper.writeValueAsString(items)
        }

        // Required builtin tools carry no binding row: they are appended at delivery time so that
        // no agent configuration can omit them. A stale binding on such a tool is still honoured.
        val bindingByToolId = toolBindings.associateBy { it.toolId }
        val boundToolIds = bindingByToolId.keys
        val toolIdsToDeliver = (
            toolBindings.map { it.toolId } +
                agentToolMapper.selectRequiredTools().map { it.id }.filter { it !in boundToolIds }
            ).distinct()
        val toolById = if (toolIdsToDeliver.isEmpty()) {
            emptyMap()
        } else {
            agentToolMapper.selectByIds(toolIdsToDeliver).associateBy { it.id }
        }
        val missingToolIds = toolIdsToDeliver - toolById.keys
        if (missingToolIds.isNotEmpty()) {
            log.warn("Tools not found (deleted?), skipped from spec: toolIds={}", missingToolIds)
        }

        val toolDetails = toolIdsToDeliver.mapNotNull { toolId ->
            val tool = toolById[toolId]
            if (tool == null) {
                null
            } else {
                ToolDetailDto(
                    id = tool.id,
                    name = tool.name,
                    displayName = tool.displayName,
                    displayNameZh = tool.displayNameZh,
                    description = tool.description,
                    type = tool.type,
                    beanName = tool.beanName,
                    methodName = tool.methodName,
                    httpUrl = tool.httpUrl,
                    httpMethod = tool.httpMethod,
                    // Delivered decrypted: agent-service holds no AES key. See plainConfigJson().
                    httpHeaders = plainConfigJson(tool.httpHeaders),
                    envParams = tool.envParams,
                    inputSchema = tool.inputSchema,
                    outputSchema = tool.outputSchema,
                    readOnly = tool.readOnly,
                    needConfirm = tool.needConfirm,
                    requiredEnvParamKeys = tool.requiredEnvParamKeys,
                    timeoutSeconds = tool.timeoutSeconds,
                    status = tool.status,
                    bindingNeedConfirm = bindingByToolId[toolId]?.needConfirm == 1,
                )
            }
        }

        // ── MCP bindings (JSON for backward compat + full detail DTOs) ──
        val mcpBindings = mcpBindingMapper.selectByAgentId(agentId)
        val mcpListJson = if (mcpBindings.isEmpty()) {
            "[]"
        } else {
            val items = mcpBindings.map { binding ->
                mapOf(
                    "id" to binding.mcpId,
                    "enable_skip" to binding.enableSkip,
                    "env_bindings" to resolveEnvBindingsJson(binding.envBindings),
                )
            }
            objectMapper.writeValueAsString(items)
        }

        val mcpDetails = mcpBindings.mapNotNull { binding ->
            val mcp = mcpServerMapper.selectById(binding.mcpId)
            if (mcp == null) {
                log.warn("MCP server not found: mcpId={}", binding.mcpId)
                null
            } else {
                McpDetailDto(
                    id = mcp.id,
                    name = mcp.name,
                    description = mcp.description,
                    type = mcp.type,
                    command = mcp.command,
                    url = mcp.url,
                    // Delivered decrypted: agent-service holds no AES key. See plainConfigJson().
                    headers = plainConfigJson(mcp.headers),
                    envParams = plainToolEnvJson(mcp.envParams),
                    enableSkip = binding.enableSkip,
                )
            }
        }

        // ── Skill bindings (comma-separated IDs + full detail DTOs) ──
        val skillBindings = skillBindingMapper.selectByAgentId(agentId)

        val skillDetails = skillBindings.mapNotNull { binding ->
            val skill = skillMapper.selectById(binding.skillId)
            if (skill == null) {
                log.warn("Skill not found: skillId={}", binding.skillId)
                null
            } else if (skill.status == 0) {
                // The gate `/builtin-skills` applies, and the one the CLI branch below applies to a
                // disabled CLI. A skill an operator switched off — or that a re-import stored
                // disabled because SkillContentScanner flagged it — must not reach the harness just
                // because a binding still points at it; honouring the flag is the whole point of it
                log.info("Skill '{}' (id={}) is disabled, skipping", skill.name, skill.id)
                null
            } else {
                SkillDetailDto(
                    id = skill.id,
                    name = skill.name,
                    description = skill.description,
                    skillmd = skill.skillmd,
                    resources = skill.resources,
                    version = skill.version,
                )
            }
        }.toMutableList()
        // Derived from what was actually resolved. Built from the bindings instead, it listed the ID
        // of every skill dropped just above, so the two halves of one answer disagreed
        val skillListStr = skillDetails.joinToString(",") { it.id.toString() }

        // ── CLI bindings (full detail DTOs + merge CLI skills into skillDetails) ──
        val cliBindings = cliBindingMapper.selectByAgentId(agentId)
        val cliDetails = if (cliBindings.isEmpty()) {
            emptyList()
        } else {
            val cliIds = cliBindings.map { it.cliId }.distinct()
            val clisById = cliMapper.selectByIds(cliIds).associateBy { it.id }
            val skillIdsByCli = cliSkillBindingMapper.selectByCliIds(cliIds)
                .groupBy({ it.cliId }, { it.skillId })
            cliBindings.mapNotNull { binding ->
                val cli = clisById[binding.cliId]
                if (cli == null) {
                    log.warn("CLI not found: cliId={}", binding.cliId)
                    null
                } else if (cli.status == 0) {
                    log.info("CLI '{}' (id={}) is disabled, skipping", cli.name, cli.id)
                    null
                } else {
                    CliDetailDto(
                        id = cli.id,
                        name = cli.name,
                        description = cli.description,
                        version = cli.version,
                        installScript = cli.installScript,
                        checkCommand = cli.checkCommand,
                        envBindings = resolveEnvBindingsJson(binding.envBindings),
                        skillIds = skillIdsByCli[cli.id].orEmpty(),
                    )
                }
            }
        }

        // Merge CLI-associated skills into skillDetails (dedup by skillId and by name)
        val boundSkillIds = skillDetails.map { it.id }.toHashSet()
        // Skill names are only unique per repository, and the harness keys skills by name
        // (`AgentSkill.getSkillId()` is `name + "_" + source`, `source` always being "custom" here), so
        // two entries sharing a name make the registry replace one with the other while the in-memory
        // repository still resolves the first. The agent's own binding wins over a CLI's copy.
        val boundSkillNames = skillDetails.map { it.name }.toMutableSet()
        val cliSkillIdsToAdd = cliDetails.flatMap { it.skillIds }.distinct().filter { it !in boundSkillIds }
        if (cliSkillIdsToAdd.isNotEmpty()) {
            val skillsById = skillMapper.selectByIds(cliSkillIdsToAdd).associateBy { it.id }
            for (skillId in cliSkillIdsToAdd) {
                val skill = skillsById[skillId]
                if (skill == null) {
                    log.warn("CLI-associated skill not found: skillId={}", skillId)
                    continue
                }
                if (skill.status == 0) {
                    // The same gate the direct bindings above and `/builtin-skills` apply. Without it a
                    // CLI binding smuggled a disabled skill — one an operator switched off, or one a
                    // re-import stored disabled because SkillContentScanner flagged it — into skillDetails,
                    // so the global injection path dropped it while the CLI path still loaded it
                    log.info("CLI-associated skill '{}' (id={}) is disabled, skipping", skill.name, skill.id)
                    continue
                }
                if (!boundSkillNames.add(skill.name)) {
                    log.info(
                        "CLI-associated skill '{}' (id={}) shares its name with an already bound skill, skipping",
                        skill.name,
                        skill.id,
                    )
                    continue
                }
                skillDetails.add(
                    SkillDetailDto(
                        id = skill.id,
                        name = skill.name,
                        description = skill.description,
                        skillmd = skill.skillmd,
                        resources = skill.resources,
                        version = skill.version,
                    ),
                )
            }
        }

        // ── Model + Provider full config (reuse already-queried model) ──
        val modelConfig = if (model != null) {
            val provider = modelProviderMapper.selectById(model.providerId)
            ModelConfigDto(
                modelId = model.id,
                modelName = model.modelName,
                modelType = model.modelType,
                providerType = provider?.type ?: "unknown",
                apiKey = provider?.apiKey,
                baseUrl = provider?.baseUrl,
                supportInternet = model.supportInternet,
                supportReasoning = model.supportReasoning,
                supportTool = model.supportTool,
                supportVision = model.supportVision,
                supportMcp = model.supportMcp,
            )
        } else {
            null
        }

        return AgentSpecInfoResponse(
            agentId = agentId,
            agentName = agentName,
            description = description,
            systemPrompt = systemPrompt,
            modelId = modelId,
            toolList = toolListJson,
            mcpList = mcpListJson,
            skillList = skillListStr,
            enableThink = enableThink,
            enableSearch = enableSearch,
            enablePlan = enablePlan,
            permissionMode = permissionMode,
            modelSupportInternet = model?.supportInternet ?: 0,
            modelSupportReasoning = model?.supportReasoning ?: 0,
            modelThinkingMode = model?.thinkingMode ?: 0,
            modelConfig = modelConfig,
            toolDetails = toolDetails,
            mcpDetails = mcpDetails,
            skillDetails = skillDetails,
            cliDetails = cliDetails,
        )
    }

    /**
     * Resolve env bindings JSON: for each binding with envVarId,
     * try to get latest value from env_variable table, fallback to stored snapshot.
     * For customValue, use directly.
     * Returns a list of {envKey, envValue} maps for runtime use.
     */
    @Suppress("UNCHECKED_CAST")
    private fun resolveEnvBindingsJson(storedJson: String?): List<Map<String, String>> {
        if (storedJson.isNullOrBlank()) return emptyList()
        return try {
            val list: List<Map<String, Any?>> = objectMapper.readValue(
                storedJson,
                object : TypeReference<List<Map<String, Any?>>>() {},
            )
            list.mapNotNull { entry ->
                val envKey = entry["envKey"]?.toString() ?: return@mapNotNull null
                val envVarId = (entry["envVarId"] as? Number)?.toLong()
                val customValue = entry["customValue"]?.toString()
                val snapshotValue = entry["envValue"]?.toString()

                val resolvedValue = when {
                    envVarId != null -> {
                        // Try latest value from env_variable table, fallback to snapshot
                        envVariableService.getDecryptedValue(envVarId) ?: snapshotValue
                    }
                    customValue != null -> customValue
                    else -> snapshotValue
                }

                if (resolvedValue != null) {
                    mapOf("envKey" to envKey, "envValue" to resolvedValue)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to resolve env bindings JSON: {}", e.message)
            emptyList()
        }
    }

    /**
     * Deliver a stored `McpConfigEntry` array as a flat plain-text JSON object.
     *
     * Entries marked `secret` are encrypted at rest with [AesUtil], and that key lives in this
     * service only — agent-service must not carry it. So decryption happens here, on the way out,
     * exactly the way [resolveEnvBindingsJson] already resolves binding-level env values to plain
     * text. The receiving end parses the object; see `PlaintextMcpConfigDecryptor` in harness-core.
     *
     * Blank stays null, so "nothing configured" remains distinguishable from "configured, empty".
     */
    private fun plainConfigJson(storedJson: String?): String? = storedJson?.takeIf { it.isNotBlank() }?.let {
        serializeDecrypted(secretFieldEncryptor.decryptToMap(it), it)
    }

    /** Same as [plainConfigJson], for the `ToolEnvParamEntry` array shape (envParamName / defaultValue). */
    private fun plainToolEnvJson(storedJson: String?): String? = storedJson?.takeIf { it.isNotBlank() }?.let {
        serializeDecrypted(secretFieldEncryptor.decryptToolEnvParamsToMap(it), it)
    }

    private fun serializeDecrypted(values: Map<String, String>, storedJson: String): String {
        // Both decrypt* helpers swallow parse and key failures into an empty map. Delivering "{}" is
        // still the right answer for the caller, but it must not look like a clean "no headers".
        if (values.isEmpty() && storedJson.trim() != "[]") {
            log.warn("Stored config payload could not be resolved to plain values; delivering an empty object")
        }
        return objectMapper.writeValueAsString(values)
    }

    // ========================================
    // Agent Task Spec (legacy, kept for backward compat)
    // ========================================

    @GetMapping("/agent-tasks/{taskId}/spec")
    fun getAgentTaskSpec(@PathVariable taskId: Long): ResultVo<TaskAgentSpecResponse> = try {
        val task = agentTaskMapper.selectById(taskId)
            ?: return ResultVo.error("Agent task not found: $taskId")
        val agent = agentMapper.selectById(task.agentId)
            ?: return ResultVo.error("Agent not found: ${task.agentId}")

        val spec = TaskAgentSpecResponse(
            agentId = agent.id,
            agentName = agent.name,
            description = agent.description,
            systemPrompt = agent.systemPrompt,
            modelId = agent.modelId,
            mcpList = agent.mcpList,
            skillList = agent.skillList,
        )
        ResultVo.success(spec)
    } catch (e: Exception) {
        log.error("Failed to get agent task spec: taskId={}", taskId, e)
        ResultVo.error("Failed to get agent task spec: ${e.message}")
    }

    // ========================================
    // Session capability / permission updates
    // (for agent-service to call instead of direct DB writes)
    // ========================================

    data class CapabilityToggleRequest(
        val capability: String, // "search" | "thinking" | "plan" | "bypass"
        val enable: Boolean,
    )

    data class PermissionModeRequest(
        val mode: String, // "DEFAULT" | "BYPASS" | "ACCEPT_EDITS" | "EXPLORE" | "DONT_ASK"
    )

    /**
     * Toggle a session/channel capability (search, thinking, plan, bypass).
     * Agent-service calls this instead of directly updating session/channel tables.
     */
    @PutMapping("/sessions/{sessionId}/capabilities")
    fun toggleCapability(
        @PathVariable sessionId: String,
        @RequestBody request: CapabilityToggleRequest,
    ): ResultVo<String> {
        return try {
            val flag = if (request.enable) 1 else 0
            val capability = request.capability.lowercase()
            val now = java.time.LocalDateTime.now()

            if (sessionId.startsWith("chn-")) {
                val channel = channelMapper.selectBySessionId(sessionId)
                    ?: return ResultVo.error("Channel not found: $sessionId")
                if (capability == "thinking" && flag == 0 && isThinkingRequired(agentMapper.selectById(channel.agentId)?.modelId)) {
                    return ResultVo.error("Current model requires Deep Thinking and it cannot be turned off.")
                }
                when (capability) {
                    "search" -> channel.enableSearch = flag
                    "thinking" -> channel.enableThink = flag
                    "plan" -> channel.enablePlan = flag
                    "bypass" -> channel.permissionMode = if (request.enable) "BYPASS" else "DEFAULT"
                    else -> return ResultVo.error("Unknown capability: $capability")
                }
                channel.updateTime = now
                channelMapper.updateById(channel)
            } else {
                val session = sessionMapper.selectBySessionIdAndStatus(sessionId, 1)
                    ?: return ResultVo.error("Session not found: $sessionId")
                if (capability == "thinking" && flag == 0 && isThinkingRequired(session.modelId)) {
                    return ResultVo.error("Current model requires Deep Thinking and it cannot be turned off.")
                }
                when (capability) {
                    "search" -> session.enableSearch = flag
                    "thinking" -> session.enableThink = flag
                    "plan" -> session.enablePlan = flag
                    "bypass" -> session.permissionMode = if (request.enable) "BYPASS" else "DEFAULT"
                    else -> return ResultVo.error("Unknown capability: $capability")
                }
                session.updateTime = now
                sessionMapper.updateById(session)
            }

            log.info("[Admin] Capability toggled: sessionId={}, capability={}, enable={}", sessionId, capability, request.enable)
            ResultVo.success("OK")
        } catch (e: Exception) {
            log.error("Failed to toggle capability: sessionId={}", sessionId, e)
            ResultVo.error("Failed to toggle capability: ${e.message}")
        }
    }

    private fun isThinkingRequired(modelId: Long?): Boolean {
        if (modelId == null) return false
        val model = modelMapper.selectById(modelId) ?: return false
        return model.thinkingMode == 2
    }

    /**
     * Change a session/channel permission mode.
     * Agent-service calls this instead of directly updating session/channel tables.
     */
    @PutMapping("/sessions/{sessionId}/permission-mode")
    fun updatePermissionMode(
        @PathVariable sessionId: String,
        @RequestBody request: PermissionModeRequest,
    ): ResultVo<String> {
        return try {
            val mode = request.mode.uppercase()
            if (mode !in VALID_PERMISSION_MODES) {
                return ResultVo.error("Invalid permission mode: '${request.mode}'. Valid: ${VALID_PERMISSION_MODES.joinToString(", ")}")
            }

            val now = java.time.LocalDateTime.now()

            if (sessionId.startsWith("chn-")) {
                val channel = channelMapper.selectBySessionId(sessionId)
                    ?: return ResultVo.error("Channel not found: $sessionId")
                channel.permissionMode = mode
                channel.updateTime = now
                channelMapper.updateById(channel)
            } else {
                val session = sessionMapper.selectBySessionIdAndStatus(sessionId, 1)
                    ?: return ResultVo.error("Session not found: $sessionId")
                session.permissionMode = mode
                session.updateTime = now
                sessionMapper.updateById(session)
            }

            log.info("[Admin] Permission mode updated: sessionId={}, mode={}", sessionId, mode)
            ResultVo.success("OK")
        } catch (e: Exception) {
            log.error("Failed to update permission mode: sessionId={}", sessionId, e)
            ResultVo.error("Failed to update permission mode: ${e.message}")
        }
    }

    companion object {
        private val VALID_PERMISSION_MODES = setOf("DEFAULT", "BYPASS", "ACCEPT_EDITS", "EXPLORE", "DONT_ASK")
    }
}

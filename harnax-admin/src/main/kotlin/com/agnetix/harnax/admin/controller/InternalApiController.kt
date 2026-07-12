package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.service.EnvVariableService
import com.agnetix.harnax.admin.util.AesUtil
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.entity.dto.AgentSpecInfoResponse
import com.agnetix.harnax.entity.dto.TaskAgentSpecResponse
import com.agnetix.harnax.mapper.AgentMapper
import com.agnetix.harnax.mapper.AgentMcpBindingMapper
import com.agnetix.harnax.mapper.AgentSkillBindingMapper
import com.agnetix.harnax.mapper.AgentTaskMapper
import com.agnetix.harnax.mapper.AgentToolBindingMapper
import com.agnetix.harnax.mapper.ApiKeyMapper
import com.agnetix.harnax.mapper.ChannelMapper
import com.agnetix.harnax.mapper.ModelMapper
import com.agnetix.harnax.mapper.SessionMapper
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
    private val aesUtil: AesUtil,
    private val agentTaskMapper: AgentTaskMapper,
    private val agentMapper: AgentMapper,
    private val channelMapper: ChannelMapper,
    private val toolBindingMapper: AgentToolBindingMapper,
    private val mcpBindingMapper: AgentMcpBindingMapper,
    private val skillBindingMapper: AgentSkillBindingMapper,
    private val envVariableService: EnvVariableService,
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
            enableThink = session.enableThink,
            enableSearch = session.enableSearch,
            enablePlan = session.enablePlan,
            permissionMode = session.permissionMode,
            modelSupportInternet = model?.supportInternet ?: 0,
            modelSupportReasoning = model?.supportReasoning ?: 0,
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
            permissionMode = channel.permissionMode,
            enableThink = channel.enableThink,
            enableSearch = channel.enableSearch,
            enablePlan = channel.enablePlan,
            modelSupportInternet = model?.supportInternet ?: 0,
            modelSupportReasoning = model?.supportReasoning ?: 0,
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
            permissionMode = "BYPASS",
            modelSupportInternet = model?.supportInternet ?: 0,
            modelSupportReasoning = model?.supportReasoning ?: 0,
        )
    }

    // ========================================
    // Binding table helpers
    // ========================================

    /**
     * Build AgentSpecInfoResponse with binding table data serialized as JSON.
     * Env bindings are resolved: envVarId → latest value, fallback to snapshot.
     */
    private fun buildAgentSpecResponse(
        agentId: Long,
        agentName: String,
        description: String,
        systemPrompt: String,
        modelId: Long,
        enableThink: Int = 0,
        enableSearch: Int = 0,
        enablePlan: Int = 0,
        permissionMode: String = "DEFAULT",
        modelSupportInternet: Int = 0,
        modelSupportReasoning: Int = 0,
    ): AgentSpecInfoResponse {
        val toolBindings = toolBindingMapper.selectByAgentId(agentId)
        val toolListJson = if (toolBindings.isEmpty()) {
            "[]"
        } else {
            val items = toolBindings.map { binding ->
                mapOf(
                    "id" to binding.toolId,
                    "enable_skip" to binding.enableSkip,
                    "need_confirm" to (binding.needConfirm == 1),
                    "env_bindings" to resolveEnvBindingsJson(binding.envBindings),
                )
            }
            objectMapper.writeValueAsString(items)
        }

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

        val skillBindings = skillBindingMapper.selectByAgentId(agentId)
        val skillListStr = skillBindings.joinToString(",") { it.skillId.toString() }

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
            modelSupportInternet = modelSupportInternet,
            modelSupportReasoning = modelSupportReasoning,
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
}

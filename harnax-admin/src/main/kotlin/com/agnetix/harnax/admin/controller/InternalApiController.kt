package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.util.AesUtil
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.entity.dto.AgentSpecInfoResponse
import com.agnetix.harnax.entity.dto.TaskAgentSpecResponse
import com.agnetix.harnax.mapper.AgentMapper
import com.agnetix.harnax.mapper.AgentTaskMapper
import com.agnetix.harnax.mapper.ApiKeyMapper
import com.agnetix.harnax.mapper.ChannelMapper
import com.agnetix.harnax.mapper.ModelMapper
import com.agnetix.harnax.mapper.SessionMapper
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

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
) {

    private val log = LoggerFactory.getLogger(InternalApiController::class.java)

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
        return AgentSpecInfoResponse(
            agentId = agent.id,
            agentName = agent.name,
            description = agent.description,
            systemPrompt = agent.systemPrompt,
            modelId = agent.modelId,
            mcpList = agent.mcpList,
            skillList = agent.skillList,
            toolList = agent.toolList,
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
        return AgentSpecInfoResponse(
            agentId = agent.id,
            agentName = agent.name,
            description = agent.description,
            systemPrompt = agent.systemPrompt,
            modelId = agent.modelId,
            mcpList = agent.mcpList,
            skillList = agent.skillList,
            toolList = agent.toolList,
            permissionMode = channel.permissionMode,
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
        return AgentSpecInfoResponse(
            agentId = agent.id,
            agentName = agent.name,
            description = agent.description,
            systemPrompt = agent.systemPrompt,
            modelId = agent.modelId,
            mcpList = agent.mcpList,
            skillList = agent.skillList,
            toolList = agent.toolList,
            permissionMode = "BYPASS",
        )
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

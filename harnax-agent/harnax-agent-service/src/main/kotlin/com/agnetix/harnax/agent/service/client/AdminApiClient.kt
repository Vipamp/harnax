package com.agnetix.harnax.agent.service.client

import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.entity.dto.AgentSpecInfoResponse
import com.agnetix.harnax.entity.dto.TaskAgentSpecResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestTemplate
import java.time.Duration

/**
 * HTTP client for all calls from agent-service to admin service.
 *
 * Uses raw shared secret for admin internal API authentication
 * (not JWT — admin's InternalApiAuthFilter only accepts raw secret).
 */
@Component
class AdminApiClient(
    @Value("\${admin.service.url:http://localhost:8080}") private val adminUrl: String,
    @Value("\${admin.internal-api.secret:}") private val adminSecret: String,
) {

    private val log = LoggerFactory.getLogger(AdminApiClient::class.java)
    private val restTemplate: RestTemplate

    init {
        // Configure timeouts to prevent thread blocking when admin is unreachable
        val factory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(3))
            setReadTimeout(Duration.ofSeconds(10))
        }
        restTemplate = RestTemplate(factory)
        restTemplate.interceptors.add { request, body, execution ->
            request.headers.setBearerAuth(adminSecret)
            request.headers.contentType = MediaType.APPLICATION_JSON
            execution.execute(request, body)
        }
    }

    /**
     * Unified: fetch AgentSpec by sessionId.
     *
     * Admin resolves agent configuration based on sessionId prefix:
     * - web-* / mp-*: session table → agent
     * - chn-*: channel table → agent
     * - task-{taskId}-*: agent_task table → agent
     *
     * @param sessionId the session ID (with prefix)
     * @return AgentSpecInfoResponse with agent configuration
     */
    fun getAgentSpec(sessionId: String): AgentSpecInfoResponse {
        val url = "$adminUrl/api/admin/internal/agent-spec/$sessionId"
        log.info("[Agent→Admin] GET {} - fetching agent spec", url)

        val responseType = object : ParameterizedTypeReference<ResultVo<AgentSpecInfoResponse>>() {}
        val response = try {
            restTemplate.exchange(url, HttpMethod.GET, null, responseType).body
        } catch (e: Exception) {
            log.error("[Agent←Admin] Failed to get agent spec for sessionId={}: {}", sessionId, e.message, e)
            throw RuntimeException("Failed to get agent spec from admin: ${e.message}", e)
        }

        if (response == null || response.code != 200 || response.data == null) {
            val errorMsg = response?.message ?: "No response from admin"
            log.error("[Agent←Admin] Error getting agent spec for sessionId={}: {}", sessionId, errorMsg)
            throw RuntimeException("Admin returned error: $errorMsg")
        }

        log.info(
            "[Agent←Admin] Got agent spec: sessionId={}, agentId={}, agentName={}",
            sessionId,
            response.data!!.agentId,
            response.data!!.agentName,
        )
        return response.data!!
    }

    /**
     * Toggle a session/channel capability (search, thinking, plan, bypass).
     * Delegates to admin's PUT /api/admin/internal/sessions/{sessionId}/capabilities.
     *
     * @param sessionId the session ID
     * @param capability one of: "search", "thinking", "plan", "bypass"
     * @param enable true to enable, false to disable
     * @return true if admin returned success
     */
    fun toggleCapability(sessionId: String, capability: String, enable: Boolean): Boolean {
        val url = "$adminUrl/api/admin/internal/sessions/$sessionId/capabilities"
        log.info("[Agent→Admin] PUT {} - toggling capability={}, enable={}", url, capability, enable)

        val body = mapOf("capability" to capability, "enable" to enable)
        val responseType = object : ParameterizedTypeReference<ResultVo<String>>() {}
        val response = try {
            restTemplate.exchange(url, HttpMethod.PUT, HttpEntity(body), responseType).body
        } catch (e: Exception) {
            log.error("[Agent←Admin] Failed to toggle capability: sessionId={}, capability={}: {}", sessionId, capability, e.message, e)
            return false
        }

        val success = response != null && response.code == 200
        if (!success) {
            log.warn("[Agent←Admin] Toggle capability failed: sessionId={}, capability={}, msg={}", sessionId, capability, response?.message)
        }
        return success
    }

    /**
     * Update session/channel permission mode.
     * Delegates to admin's PUT /api/admin/internal/sessions/{sessionId}/permission-mode.
     *
     * @param sessionId the session ID
     * @param mode one of: DEFAULT, BYPASS, ACCEPT_EDITS, EXPLORE, DONT_ASK
     * @return true if admin returned success
     */
    fun updatePermissionMode(sessionId: String, mode: String): Boolean {
        val url = "$adminUrl/api/admin/internal/sessions/$sessionId/permission-mode"
        log.info("[Agent→Admin] PUT {} - updating permission mode={}", url, mode)

        val body = mapOf("mode" to mode)
        val responseType = object : ParameterizedTypeReference<ResultVo<String>>() {}
        val response = try {
            restTemplate.exchange(url, HttpMethod.PUT, HttpEntity(body), responseType).body
        } catch (e: Exception) {
            log.error("[Agent←Admin] Failed to update permission mode: sessionId={}: {}", sessionId, e.message, e)
            return false
        }

        val success = response != null && response.code == 200
        if (!success) {
            log.warn("[Agent←Admin] Update permission mode failed: sessionId={}, mode={}, msg={}", sessionId, mode, response?.message)
        }
        return success
    }

    /**
     * @deprecated Use [getAgentSpec] instead. Kept for backward compatibility.
     */
    @Deprecated("Use getAgentSpec(sessionId) instead", ReplaceWith("getAgentSpec(sessionId)"))
    fun getTaskAgentSpec(taskId: Long): TaskAgentSpecResponse {
        val url = "$adminUrl/api/admin/internal/agent-tasks/$taskId/spec"
        log.info("[Agent→Admin] GET {} - fetching task agent spec for taskId={}", url, taskId)

        val responseType = object : ParameterizedTypeReference<ResultVo<TaskAgentSpecResponse>>() {}
        val response = try {
            restTemplate.exchange(url, HttpMethod.GET, null, responseType).body
        } catch (e: Exception) {
            log.error("[Agent←Admin] Failed to get task agent spec for taskId={}: {}", taskId, e.message, e)
            throw RuntimeException("Failed to get task agent spec from admin: ${e.message}", e)
        }

        if (response == null || response.code != 200 || response.data == null) {
            val errorMsg = response?.message ?: "No response from admin"
            log.error("[Agent←Admin] Error getting task agent spec for taskId={}: {}", taskId, errorMsg)
            throw RuntimeException("Admin returned error: $errorMsg")
        }

        log.info(
            "[Agent←Admin] Got task agent spec: taskId={}, agentId={}, agentName={}",
            taskId,
            response.data!!.agentId,
            response.data!!.agentName,
        )
        return response.data!!
    }
}

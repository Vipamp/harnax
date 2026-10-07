package com.agnetix.harnax.agent.service.client

import com.agnetix.harnax.agent.adaptor.SkillDraftIntake
import com.agnetix.harnax.agent.adaptor.SkillDraftProposal
import com.agnetix.harnax.agent.adaptor.mcp.McpAuthRequiredException
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.entity.dto.AgentSpecInfoResponse
import com.agnetix.harnax.entity.dto.CliPackageInventoryResponse
import com.agnetix.harnax.entity.dto.McpAccessTokenResponse
import com.agnetix.harnax.entity.dto.TeamSpecInfoResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
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
     * Ask admin whether this session runs as a team, so the caller knows which spec endpoint can resolve it.
     *
     * `/agent-spec` answers an agent session and refuses a team one, `/team-spec` the other way round, and
     * the session id does not say which is which. Only the presence of the returned team id is used: an
     * unknown session and a plain agent session both answer false, and the caller's own `/agent-spec` call
     * is what reports a genuinely missing session. Throwing when admin does not answer at all is the point
     * — an unanswered question would otherwise let a team session fall back to a single-agent chat.
     */
    fun isTeamSession(sessionId: String): Boolean {
        val url = "$adminUrl/api/admin/internal/sessions/$sessionId/team"
        log.info("[Agent→Admin] GET {} - asking whether the session runs as a team", url)

        val responseType = object : ParameterizedTypeReference<ResultVo<Long?>>() {}
        val response = try {
            restTemplate.exchange(url, HttpMethod.GET, null, responseType).body
        } catch (e: Exception) {
            log.error("[Agent←Admin] Failed to resolve session ownership for sessionId={}: {}", sessionId, e.message, e)
            throw RuntimeException("Failed to resolve session ownership from admin: ${e.message}", e)
        }

        if (response == null || response.code != 200) {
            val errorMsg = response?.message ?: "No response from admin"
            log.error("[Agent←Admin] Error resolving session ownership for sessionId={}: {}", sessionId, errorMsg)
            throw RuntimeException("Admin returned error: $errorMsg")
        }
        return response.data != null
    }

    /**
     * Fetch the team configuration of one team session: the lead's spec plus every member's full spec.
     *
     * Only the root session id is accepted. Throwing on an error is deliberate — a team session that
     * cannot resolve its roster must not fall back to an ordinary single-agent chat, because the user
     * would see a conversation that quietly stopped delegating.
     */
    fun getTeamSpec(sessionId: String): TeamSpecInfoResponse {
        val url = "$adminUrl/api/admin/internal/team-spec/$sessionId"
        log.info("[Agent→Admin] GET {} - fetching team spec", url)

        val responseType = object : ParameterizedTypeReference<ResultVo<TeamSpecInfoResponse>>() {}
        val response = try {
            restTemplate.exchange(url, HttpMethod.GET, null, responseType).body
        } catch (e: Exception) {
            log.error("[Agent←Admin] Failed to get team spec for sessionId={}: {}", sessionId, e.message, e)
            throw RuntimeException("Failed to get team spec from admin: ${e.message}", e)
        }

        if (response == null || response.code != 200 || response.data == null) {
            val errorMsg = response?.message ?: "No response from admin"
            log.error("[Agent←Admin] Error getting team spec for sessionId={}: {}", sessionId, errorMsg)
            throw RuntimeException("Admin returned error: $errorMsg")
        }

        log.info(
            "[Agent←Admin] Got team spec: sessionId={}, teamId={}, lead={} (skills={}), members={}",
            sessionId,
            response.data!!.teamId,
            response.data!!.lead.agentName,
            response.data!!.lead.skillDetails.size,
            response.data!!.members.map { it.memberAgentId },
        )
        return response.data!!
    }

    /**
     * Fetch which CLI packages are registered and which sets agents actually run, so this host can reclaim
     * the payload trees and images nothing names any more.
     *
     * Throwing when admin does not answer is the whole point of the shape: the answer is a deletion
     * whitelist, and a missing one reads as "everything is unused". A silent empty list would let an admin
     * outage delete every cached package on every host.
     */
    fun getCliPackageInventory(): CliPackageInventoryResponse {
        val url = "$adminUrl/api/admin/internal/cli/inventory"
        log.debug("[Agent→Admin] GET {} - fetching CLI package inventory", url)

        val responseType = object : ParameterizedTypeReference<ResultVo<CliPackageInventoryResponse>>() {}
        val response = try {
            restTemplate.exchange(url, HttpMethod.GET, null, responseType).body
        } catch (e: Exception) {
            log.error("[Agent←Admin] Failed to get CLI package inventory: {}", e.message, e)
            throw RuntimeException("Failed to get CLI package inventory from admin: ${e.message}", e)
        }

        if (response == null || response.code != 200 || response.data == null) {
            val errorMsg = response?.message ?: "No response from admin"
            log.error("[Agent←Admin] Error getting CLI package inventory: {}", errorMsg)
            throw RuntimeException("Admin returned error: $errorMsg")
        }
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
     * File what this session did with a set of Admin-delivered skills (design section 3.4).
     *
     * The session id is what decides the tenant: admin resolves it from that id rather than from anything
     * the runtime claims, and stamps receipt time itself. The user id is reported because the runtime now
     * knows it — the router authenticated the end user behind this run — and admin keeps the row with an
     * empty user column if the id does not belong to the tenant the session resolved to. Which event is
     * filed is the caller's decision: this client only carries the batch, so it asserts nothing about what
     * the model did with a skill.
     *
     * @param sessionId the runtime session that read the skills
     * @param skillIds Admin skill ids, never names
     * @param userId the end user this run is attributed to, null when the conversation has none
     * @param event the event word Admin records, `VIEW` or `USE`
     * @return true if admin accepted the batch
     */
    fun reportSkillUsage(
        sessionId: String,
        skillIds: List<Long>,
        userId: Long?,
        event: String,
    ): Boolean {
        val url = "$adminUrl/api/admin/internal/skills/usage"
        log.debug("[Agent→Admin] POST {} - reporting {} {} event(s)", url, skillIds.size, event)

        val body = mapOf(
            "sessionId" to sessionId,
            "userId" to userId,
            "events" to skillIds.map { mapOf("skillId" to it, "event" to event) },
        )
        val responseType = object : ParameterizedTypeReference<ResultVo<Int>>() {}
        val response = try {
            restTemplate.exchange(url, HttpMethod.POST, HttpEntity(body), responseType).body
        } catch (e: Exception) {
            log.warn("[Agent←Admin] Failed to report skill usage: sessionId={}, {} skill(s): {}", sessionId, skillIds.size, e.message)
            return false
        }

        val success = response != null && response.code == 200
        if (!success) {
            log.warn("[Agent←Admin] Report skill usage failed: sessionId={}, msg={}", sessionId, response?.message)
        }
        return success
    }

    /**
     * File one agent-authored skill draft with Admin's review queue.
     *
     * The session id decides the tenant, exactly as it does for [reportSkillUsage]: admin resolves the owner
     * from that id and refuses anything it cannot place, so a runtime cannot queue a draft on somebody
     * else's queue by naming a skill it likes.
     *
     * Answers with three outcomes because the caller has to pick between two opposite mistakes. A draft the
     * queue rejected for its content is finished — telling the model "deferred" would leave an agent
     * proposing a skill nobody will ever install. A draft that never reached the queue is not, and calling
     * that refused would delete a proposal over one network blip. Admin's business errors arrive as HTTP 200
     * with an envelope code, so the code is what separates the two, not the status.
     */
    fun submitSkillDraft(proposal: SkillDraftProposal): SkillDraftIntake {
        val url = "$adminUrl/api/admin/internal/skills/drafts"
        log.debug("[Agent→Admin] POST {} - queuing draft skill '{}'", url, proposal.name)

        val body = mapOf(
            "sessionId" to proposal.sessionId,
            "name" to proposal.name,
            "description" to proposal.description,
            "skillmd" to proposal.skillmd,
            "resources" to proposal.resources,
            "scanVerdict" to proposal.scanVerdict,
            "scanFindings" to proposal.scanFindings,
        )
        val responseType = object : ParameterizedTypeReference<ResultVo<Long>>() {}
        val response = try {
            restTemplate.exchange(url, HttpMethod.POST, HttpEntity(body), responseType).body
        } catch (e: Exception) {
            log.warn("[Agent←Admin] Failed to queue draft '{}': sessionId={}, {}", proposal.name, proposal.sessionId, e.message)
            return SkillDraftIntake.Unavailable(e.message ?: "admin internal API did not answer")
        }

        val code = response?.code
        val draftId = response?.data
        return when {
            code == 200 && draftId != null && draftId > 0L -> SkillDraftIntake.Queued(draftId)

            // 400 is a validation refusal, 404 a session admin cannot place in a tenant. Both answer the
            // same way on retry, which is the definition of a refusal rather than of an outage.
            code == 400 || code == 404 -> {
                val reason = response?.message ?: "draft rejected by admin"
                log.warn("[Agent←Admin] Draft '{}' refused: sessionId={}, {}", proposal.name, proposal.sessionId, reason)
                SkillDraftIntake.Refused(reason)
            }

            else -> {
                val reason = if (code == null) "no response from admin" else "admin returned code $code: ${response?.message}"
                log.warn("[Agent←Admin] Draft '{}' was not queued: sessionId={}, {}", proposal.name, proposal.sessionId, reason)
                SkillDraftIntake.Unavailable(reason)
            }
        }
    }

    /**
     * Mint (or renew) the access token that the owner of this session granted for one OAuth MCP server.
     *
     * The session id is the whole request: admin resolves who owns it and answers for that person, so
     * there is no way to ask for somebody else's token by naming a user id (design section 7.2).
     *
     * Failures become exceptions instead of a null, because the caller has to tell the two kinds apart
     * and they are fixed by different people: 401 means the grant is gone and only the user can get it
     * back, anything else means admin or the authorization server is unreachable and retrying is enough.
     */
    fun getMcpAccessToken(sessionId: String, mcpId: Long): McpAccessTokenResponse {
        val url = "$adminUrl/api/admin/internal/mcp/access-token"
        log.debug("[Agent→Admin] POST {} - fetching MCP access token for sessionId={}, mcpId={}", url, sessionId, mcpId)

        val body = mapOf("sessionId" to sessionId, "mcpId" to mcpId)
        val responseType = object : ParameterizedTypeReference<ResultVo<McpAccessTokenResponse>>() {}
        val response = try {
            restTemplate.exchange(url, HttpMethod.POST, HttpEntity(body), responseType).body
        } catch (e: Exception) {
            // Never log or forward the token itself; the message above is admin's own text.
            log.error("[Agent←Admin] Failed to get MCP access token: sessionId={}, mcpId={}: {}", sessionId, mcpId, e.message)
            throw RuntimeException("Failed to get MCP access token from admin: ${e.message}", e)
        }

        val data = response?.data
        if (data == null || response.code != 200 || data.accessToken.isBlank()) {
            val code = response?.code ?: 500
            val message = response?.message ?: "No response from admin"
            if (code == 401) {
                throw McpAuthRequiredException("MCP authorization is required for session $sessionId (mcpId=$mcpId): $message")
            }
            throw RuntimeException("Admin refused the MCP access token (code=$code): $message")
        }
        return data
    }
}

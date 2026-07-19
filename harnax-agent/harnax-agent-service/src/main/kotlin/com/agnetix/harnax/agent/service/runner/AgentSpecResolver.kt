package com.agnetix.harnax.agent.service.runner

import com.agnetix.harnax.agent.AgentSpec
import com.agnetix.harnax.agent.ChatSpec
import com.agnetix.harnax.agent.ChatSpecBuilder
import com.agnetix.harnax.agent.McpSpec
import com.agnetix.harnax.agent.SkillSpec
import com.agnetix.harnax.agent.service.client.AdminApiClient
import com.agnetix.harnax.agent.service.client.AgentSpecContextHolder
import com.agnetix.harnax.entity.dto.AgentSpecInfoResponse
import com.agnetix.harnax.tools.sdk.ToolEnvContext
import com.agnetix.harnax.tools.sdk.ToolSpec
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

/**
 * Resolves AgentSpec and ChatSpec for any sessionId by calling Admin's unified internal API.
 *
 * This is the single point of agent-spec resolution in agent-service.
 * DefaultAgentRunner delegates to this class instead of
 * querying session/channel/task tables directly.
 *
 * Flow:
 *   1. Call adminApiClient.getAgentSpec(sessionId) — admin resolves by prefix
 *   2. Store full spec in AgentSpecContextHolder (for adaptors to read during agent creation)
 *   3. Build AgentSpec from response (parse MCP list, tool list, skill details)
 *   4. Build ChatSpec from response (enableThink/Search/Plan flags)
 *   5. Return Pair(AgentSpec, ChatSpec)
 *
 * Since v2: skill names are resolved from the admin response's skillDetails,
 * no longer queries SkillMapper directly.
 */
@Component
class AgentSpecResolver(
    private val adminApiClient: AdminApiClient,
    private val specContextHolder: AgentSpecContextHolder,
    private val objectMapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(AgentSpecResolver::class.java)

    /**
     * Resolve agent and chat spec for the given sessionId.
     * Delegates to admin's unified `/api/admin/internal/agent-spec/{sessionId}` endpoint.
     * Also stores the full spec in ThreadLocal context for adaptor impls to read.
     */
    fun resolve(sessionId: String): Pair<AgentSpec, ChatSpec> {
        val specInfo = adminApiClient.getAgentSpec(sessionId)

        // Store full spec in context so adaptors can read during agent creation
        specContextHolder.set(specInfo)

        log.info(
            "Resolved agent spec from admin: sessionId={}, agentId={}, agentName={}",
            sessionId,
            specInfo.agentId,
            specInfo.agentName,
        )

        val agentSpec = buildAgentSpec(specInfo, sessionId)

        // Mask session-level enable flags with model capabilities.
        // If the model doesn't support a feature, force it off regardless of session config.
        val effectiveSearch = specInfo.enableSearch == 1 && specInfo.modelSupportInternet == 1
        val effectiveThinking = specInfo.enableThink == 1 && specInfo.modelSupportReasoning == 1
        val effectivePlan = specInfo.enablePlan == 1

        if (specInfo.enableSearch == 1 && specInfo.modelSupportInternet != 1) {
            log.warn("Session requests enableSearch but model does not support internet search: sessionId={}, modelId={}", sessionId, specInfo.modelId)
        }
        if (specInfo.enableThink == 1 && specInfo.modelSupportReasoning != 1) {
            log.warn("Session requests enableThink but model does not support reasoning: sessionId={}, modelId={}", sessionId, specInfo.modelId)
        }

        val chatSpec = ChatSpecBuilder()
            .enableThinking(effectiveThinking)
            .enableSearch(effectiveSearch)
            .enablePlan(effectivePlan)
            .permissionMode(specInfo.permissionMode)
            .build()

        return agentSpec to chatSpec
    }

    /**
     * Build AgentSpec from the unified admin response.
     * Uses full detail DTOs (toolDetails, mcpDetails, skillDetails) instead of
     * querying DB separately.
     */
    private fun buildAgentSpec(specInfo: AgentSpecInfoResponse, sessionId: String): AgentSpec {
        val builder = AgentSpec.builder()
            .id(specInfo.agentId)
            .name(specInfo.agentName)
            .description(specInfo.description)
            .systemPrompt(specInfo.systemPrompt)
            .chatModelId(specInfo.modelId)

        // Collect all env bindings for ToolEnvContext (flat map, merged across tools and MCPs)
        val allEnvBindings = mutableMapOf<String, String>()

        // ── MCP details (full config from admin) ──
        for (mcp in specInfo.mcpDetails) {
            builder.addMcpService(McpSpec(mcpId = mcp.id, skipIfMissing = mcp.enableSkip == "true"))
        }

        // ── Skill details (full config from admin, no SkillMapper needed) ──
        for (skill in specInfo.skillDetails) {
            builder.addSkill(SkillSpec(skillId = skill.id, skillName = skill.name))
        }

        // ── Tool details (full config from admin) ──
        for (tool in specInfo.toolDetails) {
            builder.addToolSpec(
                ToolSpec(
                    toolId = tool.id,
                    skipIfMissing = tool.enableSkip == "true",
                    needConfirm = tool.bindingNeedConfirm,
                ),
            )
        }

        // ── Env bindings: still parsed from legacy JSON (toolList/mcpList) for backward compat ──
        // Admin pre-resolves envVarId to actual values, so format is:
        // [{envKey: "SMTP_HOST", envValue: "smtp.gmail.com"}, ...]
        allEnvBindings.putAll(parseEnvBindingsFromLegacyJson(specInfo.toolList))
        allEnvBindings.putAll(parseEnvBindingsFromLegacyJson(specInfo.mcpList))

        log.info("[env-debug] Final allEnvBindings ({} entries): {}", allEnvBindings.size, allEnvBindings.keys)
        if (allEnvBindings.isNotEmpty()) {
            builder.addContextForTool(ToolEnvContext(bindings = allEnvBindings))
            log.info("[env-debug] ToolEnvContext registered with {} bindings", allEnvBindings.size)
        } else {
            log.warn("[env-debug] No env bindings found — ToolEnvContext NOT registered!")
        }

        return builder.build()
    }

    /**
     * Parse env_bindings from legacy tool/mcp JSON lists.
     * Admin pre-resolves envVarId to actual values, so format is:
     * [{envKey: "SMTP_HOST", envValue: "smtp.gmail.com"}, ...]
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseEnvBindingsFromLegacyJson(jsonList: String): Map<String, String> {
        if (jsonList.isBlank() || jsonList == "[]") return emptyMap()
        return try {
            val list: List<Map<String, Any>> = objectMapper.readValue(
                jsonList,
                object : TypeReference<List<Map<String, Any>>>() {},
            )
            list.flatMap { config ->
                parseEnvBindings(config["env_bindings"]).entries
            }.associate { it.key to it.value }
        } catch (e: Exception) {
            log.warn("Failed to parse legacy env bindings JSON: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Parse env_bindings from tool/mcp config JSON.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseEnvBindings(raw: Any?): Map<String, String> {
        if (raw == null) return emptyMap()
        return try {
            val list = raw as? List<Map<String, Any>> ?: return emptyMap()
            list.mapNotNull { binding ->
                val key = binding["envKey"] as? String ?: return@mapNotNull null
                val value = binding["envValue"] as? String ?: return@mapNotNull null
                key to value
            }.toMap()
        } catch (e: Exception) {
            log.warn("Failed to parse env_bindings: ${e.message}")
            emptyMap()
        }
    }
}

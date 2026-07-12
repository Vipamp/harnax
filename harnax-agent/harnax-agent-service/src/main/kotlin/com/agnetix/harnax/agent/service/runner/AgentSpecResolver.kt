package com.agnetix.harnax.agent.service.runner

import com.agnetix.harnax.agent.AgentSpec
import com.agnetix.harnax.agent.ChatSpec
import com.agnetix.harnax.agent.ChatSpecBuilder
import com.agnetix.harnax.agent.McpSpec
import com.agnetix.harnax.agent.SkillSpec
import com.agnetix.harnax.agent.service.client.AdminApiClient
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.entity.dto.AgentSpecInfoResponse
import com.agnetix.harnax.mapper.SkillMapper
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
 * Both DefaultAgentRunner and ChatService delegate to this class instead of
 * querying session/channel/task tables directly.
 *
 * Flow:
 *   1. Call adminApiClient.getAgentSpec(sessionId) — admin resolves by prefix
 *   2. Build AgentSpec from response (parse MCP list, resolve skill names)
 *   3. Build ChatSpec from response (enableThink/Search/Plan flags)
 *   4. Return Pair(AgentSpec, ChatSpec)
 */
@Component
class AgentSpecResolver(
    private val adminApiClient: AdminApiClient,
    private val skillMapper: SkillMapper,
    private val objectMapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(AgentSpecResolver::class.java)

    /**
     * Resolve agent and chat spec for the given sessionId.
     * Delegates to admin's unified `/api/admin/internal/agent-spec/{sessionId}` endpoint.
     */
    fun resolve(sessionId: String): Pair<AgentSpec, ChatSpec> {
        val specInfo = adminApiClient.getAgentSpec(sessionId)
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
     * Parses tool/MCP bindings (JSON from normalized tables) and resolves skill names.
     * Env bindings are pre-resolved by admin (envVarId → latest value, fallback to snapshot).
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

        log.info("[env-debug] Raw toolList from admin: {}", specInfo.toolList)
        log.info("[env-debug] Raw mcpList from admin: {}", specInfo.mcpList)

        // Parse MCP list (JSON from binding table)
        val mcpListStr = specInfo.mcpList
        if (mcpListStr.isNotEmpty() && mcpListStr != "[]") {
            try {
                val mcpConfigs: List<Map<String, Any>> = objectMapper.readValue(
                    mcpListStr,
                    object : TypeReference<List<Map<String, Any>>>() {},
                )
                for (config in mcpConfigs) {
                    val mcpId = (config["id"] as Number).toLong()
                    val enableSkip = config["enable_skip"] as? String
                    // Collect MCP env bindings too
                    val envBindings = parseEnvBindings(config["env_bindings"])
                    log.info("[env-debug] MCP id={} env_bindings parsed: {}", mcpId, envBindings)
                    allEnvBindings.putAll(envBindings)
                    builder.addMcpService(McpSpec(mcpId = mcpId, skipIfMissing = enableSkip == "true"))
                }
            } catch (e: Exception) {
                log.warn("Failed to parse MCP list for session=$sessionId: ${e.message}", e)
            }
        }

        // Parse skill list (comma-separated IDs) and resolve names from local DB
        val skillListStr = specInfo.skillList
        if (skillListStr.isNotEmpty() && skillListStr != "[]") {
            val skillIds = skillListStr.split(",")
            for (skillIdStr in skillIds) {
                try {
                    val skillId = skillIdStr.trim().toLong()
                    val skill: Skill? = skillMapper.selectById(skillId)
                    if (skill != null) {
                        builder.addSkill(SkillSpec(skillId = skill.id, skillName = skill.name))
                    } else {
                        log.warn("Skill not found: $skillId")
                    }
                } catch (e: NumberFormatException) {
                    log.warn("Invalid skill ID: $skillIdStr")
                }
            }
        }

        // Parse tool list (JSON from binding table)
        val toolListStr = specInfo.toolList
        if (toolListStr.isNotEmpty() && toolListStr != "[]") {
            try {
                val toolConfigs: List<Map<String, Any>> = objectMapper.readValue(
                    toolListStr,
                    object : TypeReference<List<Map<String, Any>>>() {},
                )
                for (config in toolConfigs) {
                    val idValue = config["id"] ?: continue
                    val toolId = (idValue as? Number)?.toLong() ?: continue
                    val enableSkip = config["enable_skip"] as? String
                    val needConfirm = config["need_confirm"] as? Boolean ?: false
                    val envBindings = parseEnvBindings(config["env_bindings"])
                    log.info("[env-debug] Tool id={} env_bindings parsed: {}", toolId, envBindings)
                    allEnvBindings.putAll(envBindings)
                    builder.addToolSpec(
                        ToolSpec(
                            toolId = toolId,
                            skipIfMissing = enableSkip == "true",
                            needConfirm = needConfirm,
                        ),
                    )
                }
            } catch (e: Exception) {
                log.warn("Failed to parse tool list for session=$sessionId: ${e.message}", e)
            }
        }

        // Register ToolEnvContext for per-agent tool env variable injection via ToolExecutionContext
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
     * Parse env_bindings from tool/mcp config JSON.
     * Admin pre-resolves envVarId to actual values, so format is:
     * [{envKey: "SMTP_HOST", envValue: "smtp.gmail.com"}, ...]
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

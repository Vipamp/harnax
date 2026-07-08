package com.agnetix.harnax.agent.service.runner

import com.agnetix.harnax.agent.AgentSpec
import com.agnetix.harnax.agent.ChatSpec
import com.agnetix.harnax.agent.ChatSpecBuilder
import com.agnetix.harnax.agent.McpSpec
import com.agnetix.harnax.agent.SkillSpec
import com.agnetix.harnax.agent.provider.tool.ToolSpec
import com.agnetix.harnax.agent.service.client.AdminApiClient
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.entity.dto.AgentSpecInfoResponse
import com.agnetix.harnax.mapper.SkillMapper
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
        val chatSpec = ChatSpecBuilder()
            .enableThinking(specInfo.enableThink == 1)
            .enableSearch(specInfo.enableSearch == 1)
            .enablePlan(specInfo.enablePlan == 1)
            .permissionMode(specInfo.permissionMode)
            .build()

        return agentSpec to chatSpec
    }

    /**
     * Build AgentSpec from the unified admin response.
     * Parses MCP list (JSON) and resolves skill names from local skill table.
     */
    private fun buildAgentSpec(specInfo: AgentSpecInfoResponse, sessionId: String): AgentSpec {
        val builder = AgentSpec.builder()
            .id(specInfo.agentId)
            .name(specInfo.agentName)
            .description(specInfo.description)
            .systemPrompt(specInfo.systemPrompt)
            .chatModelId(specInfo.modelId)

        // Parse MCP list (JSON format)
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

        // Parse tool list (JSON format)
        val toolListStr = specInfo.toolList
        if (toolListStr.isNotEmpty() && toolListStr != "[]") {
            try {
                val toolConfigs: List<Map<String, Any>> = objectMapper.readValue(
                    toolListStr,
                    object : TypeReference<List<Map<String, Any>>>() {},
                )
                for (config in toolConfigs) {
                    val toolId = (config["id"] as Number).toLong()
                    val enableSkip = config["enable_skip"] as? String
                    val needConfirm = config["need_confirm"] as? Boolean ?: false
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

        return builder.build()
    }
}

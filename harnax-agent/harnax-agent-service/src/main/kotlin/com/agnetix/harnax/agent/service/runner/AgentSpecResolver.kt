package com.agnetix.harnax.agent.service.runner

import com.agnetix.harnax.agent.AgentSpec
import com.agnetix.harnax.agent.ChatSpec
import com.agnetix.harnax.agent.ChatSpecBuilder
import com.agnetix.harnax.agent.CliSpec
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
    private val builtinSkillRegistry: BuiltinSkillRegistry,
) {

    private val log = LoggerFactory.getLogger(AgentSpecResolver::class.java)

    /**
     * Resolve agent and chat spec for the given sessionId.
     * Delegates to admin's unified `/api/admin/internal/agent-spec/{sessionId}` endpoint.
     * Also stores the full spec in ThreadLocal context for adaptor impls to read.
     */
    fun resolve(sessionId: String): Pair<AgentSpec, ChatSpec> {
        val specInfo = adminApiClient.getAgentSpec(sessionId)

        // Inject built-in skills: loaded first (before spec-defined skills), dedup by id and by name.
        val builtinSkills = builtinSkillRegistry.getSkills()
        val specSkillIds = specInfo.skillDetails.map { it.id }.toSet()
        // Skill names are only unique per repository, so a tenant's own repository can hold a skill
        // named like a built-in one. The harness keys skills by name (`AgentSkill.getSkillId()` is
        // `name + "_" + source`), so delivering both would let the registry and the in-memory
        // repository disagree on which copy is live; the operator's explicit binding wins.
        val specSkillNames = specInfo.skillDetails.map { it.name }.toSet()
        val (injectedSkills, shadowedSkills) = builtinSkills.filter { it.id !in specSkillIds }
            .partition { it.name !in specSkillNames }
        val mergedSkillDetails = injectedSkills + specInfo.skillDetails
        val effectiveSpecInfo = specInfo.copy(skillDetails = mergedSkillDetails)
        if (shadowedSkills.isNotEmpty()) {
            log.info(
                "Built-in skills not injected, a spec-defined skill already uses the name: sessionId={}, skills={}",
                sessionId,
                shadowedSkills.map { it.name },
            )
        }
        if (injectedSkills.isNotEmpty()) {
            log.info("Injected {} built-in skills into spec: sessionId={}, skills={}", injectedSkills.size, sessionId, injectedSkills.map { it.name })
        }

        // Store full spec in context so adaptors can read during agent creation
        specContextHolder.set(effectiveSpecInfo)

        log.info(
            "Resolved agent spec from admin: sessionId={}, agentId={}, agentName={}",
            sessionId,
            specInfo.agentId,
            specInfo.agentName,
        )

        val agentSpec = buildAgentSpec(effectiveSpecInfo, sessionId)

        // Mask session-level enable flags with model capabilities.
        // If the model doesn't support a feature, force it off regardless of session config.
        val effectiveSearch = specInfo.enableSearch == 1 && specInfo.modelSupportInternet == 1
        val effectiveThinking = when (specInfo.modelThinkingMode) {
            2 -> true // required: model only supports thinking mode, ignore session toggle
            1 -> specInfo.enableThink == 1 // optional: honor session toggle
            else -> specInfo.enableThink == 1 && specInfo.modelSupportReasoning == 1 // legacy fallback
        }
        val effectivePlan = specInfo.enablePlan == 1

        if (specInfo.enableSearch == 1 && specInfo.modelSupportInternet != 1) {
            log.warn("Session requests enableSearch but model does not support internet search: sessionId={}, modelId={}", sessionId, specInfo.modelId)
        }
        if (specInfo.enableThink == 1 && specInfo.modelThinkingMode != 2 && specInfo.modelSupportReasoning != 1) {
            log.warn("Session requests enableThink but model does not support reasoning: sessionId={}, modelId={}", sessionId, specInfo.modelId)
        }
        if (specInfo.enableThink == 0 && specInfo.modelThinkingMode == 2) {
            log.info("Model requires thinking mode, forcing enableThinking=true: sessionId={}, modelId={}", sessionId, specInfo.modelId)
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
            builder.addMcpService(McpSpec(mcpId = mcp.id))
        }

        // ── Skill details (full config from admin, no SkillMapper needed) ──
        // Note: admin already merges CLI-associated skills into skillDetails (dedup by id)
        for (skill in specInfo.skillDetails) {
            builder.addSkill(SkillSpec(skillId = skill.id, skillName = skill.name))
        }

        // ── CLI details (install scripts for sandbox image + env bindings) ──
        for (cli in specInfo.cliDetails) {
            builder.addCliSpec(
                CliSpec(
                    cliId = cli.id,
                    name = cli.name,
                    version = cli.version,
                    installScript = cli.installScript,
                    checkCommand = cli.checkCommand,
                    envBindings = cli.envBindings.mapNotNull { binding ->
                        val key = binding["envKey"] ?: return@mapNotNull null
                        val value = binding["envValue"] ?: return@mapNotNull null
                        key to value
                    }.toMap(),
                    skillIds = cli.skillIds,
                ),
            )
        }

        // ── Tool details (full config from admin) ──
        for (tool in specInfo.toolDetails) {
            builder.addToolSpec(
                ToolSpec(
                    toolId = tool.id,
                    needConfirm = tool.bindingNeedConfirm,
                ),
            )
        }

        // ── Env bindings: still parsed from legacy JSON (toolList/mcpList) for backward compat ──
        // Admin pre-resolves envVarId to actual values, so format is:
        // [{envKey: "SMTP_HOST", envValue: "smtp.gmail.com"}, ...]
        allEnvBindings.putAll(parseEnvBindingsFromLegacyJson(specInfo.toolList))
        allEnvBindings.putAll(parseEnvBindingsFromLegacyJson(specInfo.mcpList))

        log.info("Resolved {} env binding(s) for agent '{}': {}", allEnvBindings.size, specInfo.agentName, allEnvBindings.keys)
        // Registered even when empty: a tool method declaring a ToolEnvContext parameter needs a
        // container, so require() can report "not configured" instead of failing injection.
        builder.addContextForTool(ToolEnvContext(bindings = allEnvBindings))

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

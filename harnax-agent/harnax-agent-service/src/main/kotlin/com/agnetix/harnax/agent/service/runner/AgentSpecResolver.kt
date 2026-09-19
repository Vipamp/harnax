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
import com.agnetix.harnax.entity.dto.SkillDetailDto
import com.agnetix.harnax.harness.team.TeamMemberSpec
import com.agnetix.harnax.harness.team.TeamRuntimeSpec
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
 *
 * [resolveTeam] is the team counterpart: it turns admin's team spec into the lead's specs plus one
 * [TeamMemberSpec] per member. It does not touch the ThreadLocal context, because a member is assembled
 * later than the lead and each build needs its own spec in place — see `DefaultAgentRunner.buildTeamAgent`.
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
        val effectiveSpecInfo = withBuiltinSkills(specInfo, sessionId) { adminApiClient.getBuiltinSkills() }

        // Store full spec in context so adaptors can read during agent creation
        specContextHolder.set(effectiveSpecInfo)

        log.info(
            "Resolved agent spec from admin: sessionId={}, agentId={}, agentName={}",
            sessionId,
            specInfo.agentId,
            specInfo.agentName,
        )
        return buildSpecs(effectiveSpecInfo, sessionId)
    }

    /**
     * Resolve the team of one team session: the lead's spec plus every member's, all built the same way
     * an ordinary agent's is (design D5).
     *
     * The ThreadLocal spec context is deliberately left alone here. Adaptors read it while an agent is
     * being assembled, and these specs are assembled at different times — the lead now, each member on the
     * first delegation to it. Whoever builds an agent owns the context around that build.
     */
    fun resolveTeam(sessionId: String): TeamRuntimeSpec {
        val teamSpec = adminApiClient.getTeamSpec(sessionId)
        // One repository fetch for the whole roster: every agent's CLI bindings filter this same list.
        val builtinSkills by lazy { adminApiClient.getBuiltinSkills() }

        val leadSpecInfo = withBuiltinSkills(teamSpec.lead, sessionId) { builtinSkills }
        val (leadAgentSpec, leadChatSpec) = buildSpecs(leadSpecInfo, sessionId)
        // Plan mode writes its plan into the agent's workspace, and a lead has none.
        val leadSpec = ChatSpec.builder()
            .enableThinking(leadChatSpec.enableThinking)
            .enableSearch(leadChatSpec.enableSearch)
            .permissionMode(leadChatSpec.permissionMode)
            .build()

        val members = teamSpec.members.map { member ->
            val specInfo = withBuiltinSkills(member.spec, sessionId) { builtinSkills }
            val (agentSpec, chatSpec) = buildSpecs(specInfo, sessionId)
            TeamMemberSpec(
                memberAgentId = member.memberAgentId,
                agentName = member.agentName,
                description = member.spec.description,
                delegationDescription = member.delegationDescription,
                agentSpec = agentSpec,
                chatSpec = chatSpec,
                specInfo = specInfo,
            )
        }

        log.info(
            "Resolved team spec from admin: sessionId={}, teamId={}, lead={}, members={}",
            sessionId,
            teamSpec.teamId,
            teamSpec.lead.agentName,
            members.map { "${it.agentName}(${it.memberAgentId})" },
        )
        return TeamRuntimeSpec(
            teamId = teamSpec.teamId,
            tenantId = teamSpec.tenantId,
            teamName = teamSpec.teamName,
            instructions = teamSpec.instructions,
            rootSessionId = sessionId,
            leadAgentSpec = leadAgentSpec,
            leadChatSpec = leadSpec,
            leadSpecInfo = leadSpecInfo,
            members = members,
        )
    }

    /**
     * Adds the built-in skills the agent's selected CLIs need.
     *
     * Built-in skills reach an agent only through the CLIs it selected: they are neither listed nor
     * selectable on the agent page, so a selected CLI is the one switch that turns them on. Their content
     * is fetched per resolve rather than cached at startup, because both the operator's kill switch
     * (turning a built-in skill off directly in the database) and a migration rewriting a SKILL.md have to
     * take effect without restarting agent-service. `/builtin-skills` already filters disabled skills and
     * admin already drops a disabled CLI from `cliDetails`, so both gates are applied before this filter.
     */
    private fun withBuiltinSkills(
        specInfo: AgentSpecInfoResponse,
        sessionId: String,
        builtinSkills: () -> List<SkillDetailDto>,
    ): AgentSpecInfoResponse {
        val cliSkillIds = specInfo.cliDetails.flatMap { it.skillIds }.toSet()
        if (cliSkillIds.isEmpty()) return specInfo
        val matched = builtinSkills().filter { it.id in cliSkillIds }
        // A CLI can still point at a skill admin no longer delivers. Nothing else on this path
        // reports it, and the symptom is an agent that quietly forgot how to use its own CLI.
        val unresolvedSkillIds = cliSkillIds - matched.map { it.id }.toSet()
        if (unresolvedSkillIds.isNotEmpty()) {
            log.warn(
                "CLI-bound skills not delivered by admin (deleted, disabled, or outside the builtin repository): sessionId={}, skillIds={}",
                sessionId,
                unresolvedSkillIds,
            )
        }
        // Inject the selected built-in skills: loaded first (before spec-defined skills), dedup by id and by name.
        val specSkillIds = specInfo.skillDetails.map { it.id }.toSet()
        // Skill names are only unique per repository, so a tenant's own repository can hold a skill
        // named like a built-in one. The harness keys skills by name (`AgentSkill.getSkillId()` is
        // `name + "_" + source`), so delivering both would let the registry and the in-memory
        // repository disagree on which copy is live; the operator's explicit binding wins.
        val specSkillNames = specInfo.skillDetails.map { it.name }.toSet()
        val (injectedSkills, shadowedSkills) = matched.filter { it.id !in specSkillIds }
            .partition { it.name !in specSkillNames }
        if (shadowedSkills.isNotEmpty()) {
            log.info(
                "Built-in skills not injected, a spec-defined skill already uses the name: sessionId={}, skills={}",
                sessionId,
                shadowedSkills.map { it.name },
            )
        }
        if (injectedSkills.isEmpty()) return specInfo
        log.info("Injected {} built-in skills into spec: sessionId={}, skills={}", injectedSkills.size, sessionId, injectedSkills.map { it.name })
        return specInfo.copy(skillDetails = injectedSkills + specInfo.skillDetails)
    }

    /** Turns one resolved admin spec into the agent and chat spec the launcher consumes. */
    private fun buildSpecs(specInfo: AgentSpecInfoResponse, sessionId: String): Pair<AgentSpec, ChatSpec> {
        val agentSpec = buildAgentSpec(specInfo, sessionId)

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
        // Note: CLI-associated skills were already injected in `resolve`, filtered by the CLIs this
        // agent selected; admin only carries their ids on `cliDetails`.
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

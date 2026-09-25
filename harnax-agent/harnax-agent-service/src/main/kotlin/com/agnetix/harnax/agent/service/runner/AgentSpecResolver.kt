package com.agnetix.harnax.agent.service.runner

import com.agnetix.harnax.agent.AgentSpec
import com.agnetix.harnax.agent.ChatSpec
import com.agnetix.harnax.agent.ChatSpecBuilder
import com.agnetix.harnax.agent.McpSpec
import com.agnetix.harnax.agent.SkillSpec
import com.agnetix.harnax.agent.service.client.AdminApiClient
import com.agnetix.harnax.agent.service.client.AgentSpecContextHolder
import com.agnetix.harnax.agent.toCliSpec
import com.agnetix.harnax.entity.dto.AgentSpecInfoResponse
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
        val effectiveSpecInfo = withCliSkills(specInfo, sessionId)

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
     * Whether this session runs as a team, so the caller knows which spec endpoint resolves it.
     *
     * Admin answers `/agent-spec` for an agent session and refuses it for a team one: since design D1 the
     * lead is the `team` row and the session carries no `agent_id`, so there is no agent to resolve.
     * Nothing local can tell the two apart instead — a team chat opened from the web has an ordinary
     * `web-` id — hence this one question before either spec call.
     */
    fun isTeamSession(sessionId: String): Boolean = adminApiClient.isTeamSession(sessionId)

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

        // A team lead has no CLI section (design D8), so this injects nothing today; it runs through the
        // same call as a member so both halves of a team resolve skills one way. The lead's own skills are
        // a different matter — they load like any agent's, see `HarnessAgentLauncher`.
        val leadSpecInfo = withCliSkills(teamSpec.lead, sessionId)
        val (leadAgentSpec, leadChatSpec) = buildSpecs(leadSpecInfo, sessionId)
        // Plan mode writes its plan into the agent's workspace, and a lead has none.
        val leadSpec = ChatSpec.builder()
            .enableThinking(leadChatSpec.enableThinking)
            .enableSearch(leadChatSpec.enableSearch)
            .permissionMode(leadChatSpec.permissionMode)
            .build()

        val members = teamSpec.members.map { member ->
            val specInfo = withCliSkills(member.spec, sessionId)
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
            rootSessionId = sessionId,
            leadAgentSpec = leadAgentSpec,
            leadChatSpec = leadSpec,
            leadSpecInfo = leadSpecInfo,
            members = members,
        )
    }

    /**
     * Adds the skills the agent's selected CLIs ship.
     *
     * A CLI's skill is part of its package, so it arrives inline on `cliDetails` and is selectable only by
     * selecting the CLI — which is also why it needs no separate fetch, and why one round trip cannot
     * deliver a CLI and not the skill that explains it. Admin has already dropped a disabled CLI and a
     * disabled skill row, so a missing one here means the row the package registered is gone.
     *
     * Injected before the spec's own skills, deduped by id and then by name: skill names are only unique
     * per repository, so a tenant's own skill can be named like a CLI's. The harness keys skills by name
     * (`AgentSkill.getSkillId()` is `name + "_" + source`), so delivering both would let the registry and
     * the in-memory repository disagree on which copy is live — the operator's explicit binding wins.
     */
    private fun withCliSkills(
        specInfo: AgentSpecInfoResponse,
        sessionId: String,
    ): AgentSpecInfoResponse {
        val cliSkills = specInfo.cliDetails.mapNotNull { it.skill }
        val skillless = specInfo.cliDetails.filter { it.skill == null }.map { "${it.name}(id=${it.id})" }
        if (skillless.isNotEmpty()) {
            log.warn(
                "Selected CLI(s) ship no skill to load — its package registered none, or its skill row was " +
                    "deleted since: sessionId={}, clis={}",
                sessionId,
                skillless,
            )
        }
        if (cliSkills.isEmpty()) return specInfo
        val specSkillIds = specInfo.skillDetails.map { it.id }.toSet()
        val specSkillNames = specInfo.skillDetails.map { it.name }.toSet()
        val (injectedSkills, shadowedSkills) = cliSkills.filter { it.id !in specSkillIds }
            .partition { it.name !in specSkillNames }
        if (shadowedSkills.isNotEmpty()) {
            log.info(
                "CLI skills not injected, a spec-bound skill already uses the name: sessionId={}, skills={}",
                sessionId,
                shadowedSkills.map { it.name },
            )
        }
        if (injectedSkills.isEmpty()) return specInfo
        log.info("Injected {} CLI skill(s) into spec: sessionId={}, skills={}", injectedSkills.size, sessionId, injectedSkills.map { it.name })
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
        // Note: the skills the selected CLIs ship are already merged in by `withCliSkills`, which reads
        // them off `cliDetails[].skill` — a package's skill has no existence apart from its CLI.
        for (skill in specInfo.skillDetails) {
            builder.addSkill(SkillSpec(skillId = skill.id, skillName = skill.name))
        }

        // ── CLI details (package coordinates for the sandbox image + env bindings) ──
        for (cli in specInfo.cliDetails) {
            builder.addCliSpec(cli.toCliSpec())
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

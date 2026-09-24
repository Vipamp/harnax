package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.registrar.BuiltinToolAutoRegistrar
import com.agnetix.harnax.admin.service.EnvVariableService
import com.agnetix.harnax.admin.service.McpOAuthUserService
import com.agnetix.harnax.admin.service.McpStdioPolicy
import com.agnetix.harnax.admin.skill.SkillBindingResolver
import com.agnetix.harnax.admin.util.AesUtil
import com.agnetix.harnax.admin.util.SecretFieldEncryptor
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.common.session.TaskSessionId
import com.agnetix.harnax.entity.Agent
import com.agnetix.harnax.entity.AgentCliBinding
import com.agnetix.harnax.entity.AgentMcpBinding
import com.agnetix.harnax.entity.AgentToolBinding
import com.agnetix.harnax.entity.Model
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.entity.Team
import com.agnetix.harnax.entity.dto.AgentSpecInfoResponse
import com.agnetix.harnax.entity.dto.CliDetailDto
import com.agnetix.harnax.entity.dto.McpAccessTokenResponse
import com.agnetix.harnax.entity.dto.McpDetailDto
import com.agnetix.harnax.entity.dto.ModelConfigDto
import com.agnetix.harnax.entity.dto.SkillDetailDto
import com.agnetix.harnax.entity.dto.TeamMemberSpecDto
import com.agnetix.harnax.entity.dto.TeamSpecInfoResponse
import com.agnetix.harnax.entity.dto.ToolDetailDto
import com.agnetix.harnax.mapper.AgentCliBindingMapper
import com.agnetix.harnax.mapper.AgentMapper
import com.agnetix.harnax.mapper.AgentMcpBindingMapper
import com.agnetix.harnax.mapper.AgentSkillBindingMapper
import com.agnetix.harnax.mapper.AgentToolBindingMapper
import com.agnetix.harnax.mapper.AgentToolMapper
import com.agnetix.harnax.mapper.ApiKeyMapper
import com.agnetix.harnax.mapper.ChannelMapper
import com.agnetix.harnax.mapper.CliMapper
import com.agnetix.harnax.mapper.McpServerMapper
import com.agnetix.harnax.mapper.ModelMapper
import com.agnetix.harnax.mapper.ModelProviderMapper
import com.agnetix.harnax.mapper.SessionMapper
import com.agnetix.harnax.mapper.SkillMapper
import com.agnetix.harnax.mapper.TeamMapper
import com.agnetix.harnax.mapper.TeamMemberMapper
import com.agnetix.harnax.mapper.TeamSkillBindingMapper
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
    private val modelProviderMapper: ModelProviderMapper,
    private val aesUtil: AesUtil,
    private val secretFieldEncryptor: SecretFieldEncryptor,
    private val agentMapper: AgentMapper,
    private val channelMapper: ChannelMapper,
    private val toolBindingMapper: AgentToolBindingMapper,
    private val mcpBindingMapper: AgentMcpBindingMapper,
    private val skillBindingMapper: AgentSkillBindingMapper,
    private val envVariableService: EnvVariableService,
    private val agentToolMapper: AgentToolMapper,
    private val mcpServerMapper: McpServerMapper,
    private val skillMapper: SkillMapper,
    private val cliBindingMapper: AgentCliBindingMapper,
    private val cliMapper: CliMapper,
    private val mcpOAuthUserService: McpOAuthUserService,
    private val mcpStdioPolicy: McpStdioPolicy,
    private val teamMapper: TeamMapper,
    private val teamMemberMapper: TeamMemberMapper,
    private val teamSkillBindingMapper: TeamSkillBindingMapper,
    private val builtinToolAutoRegistrar: BuiltinToolAutoRegistrar,
    private val skillBindingResolver: SkillBindingResolver,
) {

    private val log = LoggerFactory.getLogger(InternalApiController::class.java)
    private val objectMapper = ObjectMapper()

    data class ApiKeyValidateRequest(val keyHash: String)

    data class ApiKeyValidateResponse(
        val name: String,
        /** Owner of the key; null for SYSTEM keys. */
        val userId: Long?,
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

    /**
     * Asks for one MCP access token on behalf of a runtime session. There is no user field on
     * purpose: the session is the only identity agent-service holds, and the admin resolves it back
     * to its owner, so a caller cannot name a user whose grant it wants to spend.
     */
    data class McpAccessTokenRequest(val sessionId: String, val mcpId: Long)

    @PostMapping("/api-keys/validate")
    fun validateApiKey(@RequestBody request: ApiKeyValidateRequest): ResultVo<ApiKeyValidateResponse?> {
        val entity = apiKeyMapper.selectByKeyHash(request.keyHash)
        if (entity == null) {
            log.debug("API key not found for hash: ${request.keyHash.take(16)}...")
            return ResultVo.success(null)
        }

        val response = ApiKeyValidateResponse(
            name = entity.name,
            userId = entity.userId,
            keyHash = entity.keyHash,
            scopes = entity.scopes,
            tenantId = entity.tenantId,
            rateLimit = entity.rateLimit,
            enabled = entity.enabled == 1,
            expiresAt = entity.expiresAt?.toString(),
        )
        return ResultVo.success(response)
    }

    /**
     * Which tenant owns this session — the answer the router's ownership guard compares its caller's
     * tenant against (see `SessionAccessGuard` on the router side).
     *
     * A `chn-` id does not live in the `session` table by design: it is minted at channel creation and
     * stored on the `channel` row. Reading only `session` here therefore answered "unknown" for every
     * channel session, and unknown is a pass — so the guard had nothing to compare against and any
     * logged-in user in any tenant could read another tenant's channel conversation, plans and sandbox
     * files by naming its sessionId. The `channel` lookup below is what makes that decision possible;
     * it changes no response shape, so the router needs no change to act on the answer.
     *
     * `task-` is deliberately still not answered here. A caller with an end user behind it is refused
     * that prefix by the router's own rule before this lookup is ever reached, and its owner belongs to
     * the scheduler domain: release 2 moves task execution out of admin, and the answer will come from
     * the scheduler's owner endpoint rather than being duplicated here.
     */
    @GetMapping("/sessions/{sessionId}/info")
    fun getSessionInfo(@PathVariable sessionId: String): ResultVo<SessionInfoResponse?> {
        if (sessionId.startsWith("chn-")) {
            return channelSessionInfo(sessionId)
        }

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

    /**
     * Ownership of a `chn-` id, read from the table that holds it — whatever that row's `active` flag
     * says, which is why this is a dedicated read and not [com.agnetix.harnax.mapper.ChannelMapper]'s
     * `selectBySessionId`: that one answers `null` for a deleted channel, and the callers of it want the
     * channel's configuration, where "deleted, so gone" is the right answer. Here it would not be —
     * `deleteById` is a soft delete, the tenant stays on the row, and deleting a channel cleans up
     * neither its session nor its sandbox, so an active-filtered ownership answer would let anyone make
     * a still-readable conversation unattributable by deleting the channel that owns it.
     *
     * `agentName`/`modelId`/`modelName` stay null: this endpoint's only consumer of those fields is the
     * router's call-log enrichment, which treats a missing value as "costs the enrichment columns and
     * nothing else", and resolving the agent here would put a second query on every proxy call that
     * misses the router's cache.
     *
     * A miss is now the narrow thing it was never before: no `channel` row exists for the id at all.
     * `chn-` ids are minted when the channel is created and inserted with its row
     * (`ChannelServiceImpl.generateSessionId`), so there is no first-contact case to protect — a `chn-`
     * id with no row is one this admin never issued. It is still answered "unknown" rather than refused,
     * because unknown is this endpoint's existing not-found answer for every prefix, and an id admin
     * never minted has nothing bound to it for the router to reach.
     *
     * `tenantId` is reported as the row holds it, including a non-positive value. That is the whole
     * point of the field, so collapsing "no tenant stamped" into null would hand the caller the very
     * free pass this lookup exists to remove — null is what the router reads as "cannot be judged",
     * whereas the row's own value makes every tenant-bearing caller a cross-tenant one. Such a row is
     * unreadable through the router by design until an operator fixes its tenant, and the warning below
     * is what makes that diagnosable instead of silent.
     */
    private fun channelSessionInfo(sessionId: String): ResultVo<SessionInfoResponse?> {
        val channel = channelMapper.selectOwnerBySessionId(sessionId)
        if (channel == null) {
            log.debug("No channel row for session id at all (never minted): $sessionId")
            return ResultVo.success(null)
        }
        if (channel.tenantId <= 0) {
            log.warn(
                "Channel session {} carries tenant {}, which is no tenant at all; reporting it as the " +
                    "router's ownership check does, so every tenant-bearing caller is refused it",
                sessionId,
                channel.tenantId,
            )
        }
        return ResultVo.success(
            SessionInfoResponse(
                sessionId = channel.sessionId,
                agentId = channel.agentId,
                agentName = null,
                modelId = null,
                modelName = null,
                tenantId = channel.tenantId,
            ),
        )
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

    /**
     * One OAuth access token for the user who owns a runtime session, presented to one MCP server
     * (design section 7.2).
     *
     * Behind [com.agnetix.harnax.admin.config.InternalApiAuthFilter] like every route under this
     * prefix, which is what keeps it unreachable from a browser. The codes matter: 401 means the user
     * has no usable grant and needs to authorize, 503 means the authorization server did not answer
     * and the call is worth retrying - conflating them would send users to a consent page because of
     * a network problem.
     */
    @PostMapping("/mcp/access-token")
    fun getMcpAccessToken(@RequestBody request: McpAccessTokenRequest): ResultVo<McpAccessTokenResponse> {
        if (request.sessionId.isBlank()) {
            return ResultVo.error(400, "sessionId is required")
        }
        return try {
            ResultVo.success(mcpOAuthUserService.accessToken(request.sessionId.trim(), request.mcpId))
        } catch (e: BizException) {
            ResultVo.error(e.code, e.message ?: "MCP token issuance failed")
        } catch (e: Exception) {
            log.error("MCP token issuance failed for session {}", request.sessionId, e)
            ResultVo.error(500, "MCP token issuance failed")
        }
    }

    // ========================================
    // Agent Spec (unified, for agent-service)
    // ========================================

    /**
     * Unified endpoint: resolve agent spec by sessionId prefix.
     * - web-* / mp-*: session table → agent
     * - chn-*: channel table → agent
     * - task-*: both ids come out of the session id itself (`task-{taskId}-{agentId}-{uuid}`, contract C1)
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

    /**
     * Which team a session runs on, null when it runs on an agent.
     *
     * agent-service has to ask this before it can pick an endpoint: `/agent-spec` refuses a team
     * session and `/team-spec` refuses every other one, and guessing from the sessionId prefix is not
     * possible — a team conversation is an ordinary `web-` id. Deliberately its own read rather than a
     * field on [getSessionInfo]: that one serves the router's call-log enrichment and costs a model
     * lookup this caller has no use for.
     *
     * Only a web/mp session can be a team one, so a `chn-` or `task-` id has no session row and
     * answers null, and so does an id this admin never issued.
     */
    @GetMapping("/sessions/{sessionId}/team")
    fun getSessionTeam(@PathVariable sessionId: String): ResultVo<Long?> = ResultVo.success(
        sessionMapper.selectBySessionIdAndStatus(sessionId, 1)?.teamId,
    )

    /**
     * Team runtime configuration for one team session.
     *
     * A team's lead is the `team` row itself (design D1), so the lead's prompt, model and skills are read
     * from there — and a team session has no `agent_id` to fall back on, which makes this the only
     * endpoint that can resolve one. Child session ids are never accepted here — they carry no row of
     * their own, and resolving one by prefix would hand agent-service an unrelated agent's configuration.
     */
    @GetMapping("/team-spec/{sessionId}")
    fun getTeamSpec(@PathVariable sessionId: String): ResultVo<TeamSpecInfoResponse> = try {
        ResultVo.success(resolveTeamSpec(sessionId))
    } catch (e: Exception) {
        log.error("Failed to get team spec: sessionId={}", sessionId, e)
        ResultVo.error("Failed to get team spec: ${e.message}")
    }

    /** web/mp: session table → agent. */
    private fun resolveFromSession(sessionId: String): AgentSpecInfoResponse {
        val session = sessionMapper.selectBySessionIdAndStatus(sessionId, 1)
            ?: throw IllegalArgumentException("Session not found: $sessionId")
        if (session.teamId != null) {
            // There is no agent to resolve: since V34 a team's lead is the `team` row and the session
            // carries no agent_id. Answering here would either say "Agent not found: 0" or, if a lead
            // agent row were ever reintroduced, hand back a configuration nobody configured.
            throw IllegalArgumentException("Session $sessionId runs as a team, resolve it with /team-spec/{sessionId}")
        }
        val agentId = session.agentId
            ?: throw IllegalArgumentException("Session $sessionId has no agent and no team")
        val agent = agentMapper.selectById(agentId)
            ?: throw IllegalArgumentException("Agent not found: $agentId")
        log.info("[Admin] Resolved agent spec from session: sessionId={}, agentId={}", sessionId, agent.id)
        val model = modelMapper.selectById(agent.modelId)
        return buildAgentSpecResponse(
            agentId = agent.id,
            agentName = agent.name,
            description = agent.description,
            systemPrompt = agent.systemPrompt,
            modelId = agent.modelId,
            model = model,
            agentTenantId = agent.tenantId,
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
        val model = modelMapper.selectById(agent.modelId)
        return buildAgentSpecResponse(
            agentId = agent.id,
            agentName = agent.name,
            description = agent.description,
            systemPrompt = agent.systemPrompt,
            modelId = agent.modelId,
            model = model,
            agentTenantId = agent.tenantId,
            permissionMode = channel.permissionMode,
            enableThink = channel.enableThink,
            enableSearch = channel.enableSearch,
            enablePlan = channel.enablePlan,
        )
    }

    /**
     * task: the two ids in the session id → agent (contract C1).
     *
     * No table read: the scheduled-task domain is moving to `harnax-scheduler`, so the agent this run
     * belongs to is the one the scheduler wrote into the id when it created the row.
     *
     * [com.agnetix.harnax.common.session.TaskSessionId.parse] answers for one shape only, so a null here —
     * the pre-C1 `task-{taskId}-{uuid}` spelling included — is refused by format, with the expected shape and
     * the offending string both named. Nothing real takes that branch: release 2 carries no rows over, so a
     * refusal means a producer out of step with this parser. Guessing an agent instead of refusing would
     * hand agent-service some other agent's configuration.
     */
    private fun resolveFromTask(sessionId: String): AgentSpecInfoResponse {
        val parsed = TaskSessionId.parse(sessionId)
            ?: throw IllegalArgumentException("Invalid task sessionId: expected ${TaskSessionId.FORMAT}, got $sessionId")
        val agent = agentMapper.selectById(parsed.agentId)
            ?: throw IllegalArgumentException("Agent not found: ${parsed.agentId}")
        log.info(
            "[Admin] Resolved agent spec from task: sessionId={}, taskId={}, agentId={}",
            sessionId,
            parsed.taskId,
            agent.id,
        )
        val model = modelMapper.selectById(agent.modelId)
        return buildAgentSpecResponse(
            agentId = agent.id,
            agentName = agent.name,
            description = agent.description,
            systemPrompt = agent.systemPrompt,
            modelId = agent.modelId,
            model = model,
            agentTenantId = agent.tenantId,
            permissionMode = "BYPASS",
        )
    }

    // ========================================
    // Team spec
    // ========================================

    private fun resolveTeamSpec(sessionId: String): TeamSpecInfoResponse {
        val session = sessionMapper.selectBySessionIdAndStatus(sessionId, 1)
            ?: throw IllegalArgumentException("Session not found: $sessionId")
        val teamId = session.teamId
            ?: throw IllegalArgumentException("Session is not a team session: $sessionId")
        val team = teamMapper.selectById(teamId)
            ?: throw IllegalArgumentException("Team not found: $teamId")
        if (team.status != 1) {
            throw IllegalArgumentException("Team '${team.name}' is disabled")
        }
        if (team.tenantId != session.tenantId) {
            throw IllegalArgumentException("Team $teamId and session $sessionId belong to different tenants")
        }

        val leadSpec = specForTeam(
            team = team,
            enableThink = session.enableThink,
            enableSearch = session.enableSearch,
            enablePlan = session.enablePlan,
            permissionMode = session.permissionMode,
        )

        val bindings = teamMemberMapper.selectByTeamId(teamId)
        if (bindings.isEmpty()) {
            throw IllegalArgumentException("Team '${team.name}' has no members")
        }
        val members = bindings.map { binding ->
            val agent = agentOrThrow(binding.memberAgentId, "Member")
            TeamMemberSpecDto(
                memberAgentId = agent.id,
                agentName = agent.name,
                delegationDescription = binding.delegationDescription,
                // A member keeps its own model and capabilities; only the confirmation policy follows
                // the root session, because that is the switch the user actually set for this chat.
                spec = specForAgent(
                    agent = agent,
                    permissionMode = session.permissionMode,
                ),
            )
        }

        log.info(
            "[Admin] Resolved team spec: sessionId={}, teamId={}, members={}",
            sessionId,
            teamId,
            members.map { it.memberAgentId },
        )
        return TeamSpecInfoResponse(
            teamId = teamId,
            tenantId = team.tenantId,
            teamName = team.name,
            lead = leadSpec,
            members = members,
        )
    }

    /**
     * A member that no longer resolves is a refusal, not a shorter list: silently dropping it would
     * leave the lead delegating to a roster the operator never configured (design section 3.3).
     */
    private fun agentOrThrow(agentId: Long, role: String): Agent {
        val agent = agentMapper.selectById(agentId)
            ?: throw IllegalArgumentException("$role agent not found: $agentId")
        if (agent.status != 1) {
            throw IllegalArgumentException("$role agent '${agent.name}' ($agentId) is disabled")
        }
        return agent
    }

    private fun specForAgent(
        agent: Agent,
        enableThink: Int? = null,
        enableSearch: Int = 0,
        enablePlan: Int = 0,
        permissionMode: String = "DEFAULT",
    ): AgentSpecInfoResponse {
        val model = modelMapper.selectById(agent.modelId)
        return buildAgentSpecResponse(
            agentId = agent.id,
            agentName = agent.name,
            description = agent.description,
            systemPrompt = agent.systemPrompt,
            modelId = agent.modelId,
            model = model,
            agentTenantId = agent.tenantId,
            enableThink = enableThink ?: if ((model?.thinkingMode ?: 0) >= 1) 1 else 0,
            enableSearch = enableSearch,
            enablePlan = enablePlan,
            permissionMode = permissionMode,
        )
    }

    /**
     * The lead's configuration, read from the team row: there is no agent row standing in for it, so
     * its prompt, model and tenant are the team's own and its skills hang off `team_skill_binding`.
     *
     * The three empty lists are the product decision, not a shortcut (design D5): a team is given no
     * tool, MCP or CLI configuration to resolve, and [requiredToolIds] stays empty for the same reason
     * — a platform-required tool exists to be attached to an agent, and the lead has no shell or
     * sandbox to run one in anyway.
     */
    private fun specForTeam(
        team: Team,
        enableThink: Int? = null,
        enableSearch: Int = 0,
        enablePlan: Int = 0,
        permissionMode: String = "DEFAULT",
    ): AgentSpecInfoResponse {
        val model = modelMapper.selectById(team.modelId)
        return buildAgentSpecResponse(
            agentId = 0,
            agentName = team.name,
            description = team.description,
            systemPrompt = team.systemPrompt,
            modelId = team.modelId,
            model = model,
            agentTenantId = team.tenantId,
            enableThink = enableThink ?: if ((model?.thinkingMode ?: 0) >= 1) 1 else 0,
            enableSearch = enableSearch,
            enablePlan = enablePlan,
            permissionMode = permissionMode,
            toolBindings = emptyList(),
            mcpBindings = emptyList(),
            skillIds = teamSkillBindingMapper.selectByTeamId(team.id).map { it.skillId },
            cliBindings = emptyList(),
            requiredToolIds = emptyList(),
        )
    }

    // ========================================
    // Binding table helpers
    // ========================================

    /**
     * Build AgentSpecInfoResponse with binding table data serialized as JSON,
     * and full detail DTOs for model/tools/MCPs/skills.
     * Env bindings are resolved: envVarId → latest value, fallback to snapshot.
     *
     * The four binding reads are parameters rather than lookups (design D4) because a team's lead has
     * the same delivery needs with a different holder: its skills hang off the team row and it has no
     * tool, MCP or CLI configuration at all. The defaults keep every agent call site reading the agent
     * tables as before.
     */
    private fun buildAgentSpecResponse(
        agentId: Long,
        agentName: String,
        description: String,
        systemPrompt: String,
        modelId: Long,
        model: Model? = null,
        agentTenantId: Long,
        enableThink: Int = 0,
        enableSearch: Int = 0,
        enablePlan: Int = 0,
        permissionMode: String = "DEFAULT",
        toolBindings: List<AgentToolBinding> = toolBindingMapper.selectByAgentId(agentId),
        mcpBindings: List<AgentMcpBinding> = mcpBindingMapper.selectByAgentId(agentId),
        skillIds: List<Long> = skillBindingMapper.selectByAgentId(agentId).map { it.skillId },
        cliBindings: List<AgentCliBinding> = cliBindingMapper.selectByAgentId(agentId),
        requiredToolIds: List<Long> = agentToolMapper.selectRequiredTools().map { it.id },
    ): AgentSpecInfoResponse {
        // ── Tool bindings (JSON for backward compat + full detail DTOs) ──
        val toolListJson = if (toolBindings.isEmpty()) {
            "[]"
        } else {
            val items = toolBindings.map { binding ->
                mapOf(
                    "id" to binding.toolId,
                    "need_confirm" to (binding.needConfirm == 1),
                    "env_bindings" to resolveEnvBindingsJson(binding.envBindings, agentTenantId),
                )
            }
            objectMapper.writeValueAsString(items)
        }

        // Required builtin tools carry no binding row: they are appended at delivery time so that
        // no agent configuration can omit them. A stale binding on such a tool is still honoured.
        // A holder that takes no tool configuration at all passes none in, so nothing is appended.
        val bindingByToolId = toolBindings.associateBy { it.toolId }
        val boundToolIds = bindingByToolId.keys
        val toolIdsToDeliver = (
            toolBindings.map { it.toolId } +
                requiredToolIds.filter { it !in boundToolIds }
            ).distinct()
        val toolById = if (toolIdsToDeliver.isEmpty()) {
            emptyMap()
        } else {
            // The startup sync never deletes, so the table can hold a row whose declaration left the
            // classpath: its method no longer exists and the runtime could not assemble it. Held back
            // here by comparing against what the last sync declared — the row stays for the operator,
            // it just stops travelling. An empty declared set means the sync did not run, not that no
            // tool exists, so it disables this filter rather than blocking every tool.
            val declaredNames = builtinToolAutoRegistrar.registeredToolNames()
            agentToolMapper.selectByIds(toolIdsToDeliver)
                .filter { declaredNames.isEmpty() || it.name in declaredNames }
                .associateBy { it.id }
        }
        val missingToolIds = toolIdsToDeliver - toolById.keys
        if (missingToolIds.isNotEmpty()) {
            log.warn(
                "Tools not deliverable (deleted, or no longer declared by the code), skipped from spec: toolIds={}",
                missingToolIds,
            )
        }

        val toolDetails = toolIdsToDeliver.mapNotNull { toolId ->
            val tool = toolById[toolId]
            if (tool == null) {
                null
            } else {
                ToolDetailDto(
                    id = tool.id,
                    name = tool.name,
                    displayName = tool.displayName,
                    displayNameZh = tool.displayNameZh,
                    description = tool.description,
                    beanName = tool.beanName,
                    methodName = tool.methodName,
                    readOnly = tool.readOnly,
                    needConfirm = tool.needConfirm,
                    requiredEnvParamKeys = tool.requiredEnvParamKeys,
                    status = tool.status,
                    bindingNeedConfirm = bindingByToolId[toolId]?.needConfirm == 1,
                )
            }
        }

        // ── MCP bindings (JSON for backward compat + full detail DTOs) ──
        val mcpIdsToDeliver = mcpBindings.map { it.mcpId }.distinct()
        val resolvedMcp = if (mcpIdsToDeliver.isEmpty()) {
            emptyMap()
        } else {
            // selectByIds has no tenant condition and an internal call carries no trustworthy tenant
            // header, so the agent's own tenant is the only comparable basis: without it a cross-tenant
            // binding stored before the save-time check still hands over another tenant's headers.
            mcpServerMapper.selectByIds(mcpIdsToDeliver)
                .filter { it.tenantId == agentTenantId }
                .associateBy { it.id }
        }
        // A stdio row is a process for agent-service to start, and that runtime is not isolated for
        // it (see McpStdioPolicy), so while the switch is off such rows are simply not delivered -
        // they stay editable, and nothing spawns them. Held back here rather than at the agent so the
        // rule lives where the row does; harness-core refuses one too, in case an older admin sends it.
        val heldStdio = if (mcpStdioPolicy.enabled) emptyMap() else resolvedMcp.filterValues { mcpStdioPolicy.isStdio(it.type) }
        // The same gate `skill.status == 0` and `cli.status == 0` apply below, and the mirror of the
        // one the runtime already enforces (`HarnessAgentLauncher`). Without it a disabled server is
        // still on the wire: its decrypted headers travel for nothing, and because `mcpList` feeds
        // `ToolEnvContext` its resolved env values reach every tool of the agent, where a same-named
        // key silently overrides what an enabled tool binds.
        val heldDisabled = resolvedMcp.filterValues { it.status == 0 }
        val mcpById = resolvedMcp - heldStdio.keys - heldDisabled.keys
        val missingMcpIds = mcpIdsToDeliver - mcpById.keys
        if (missingMcpIds.isNotEmpty()) {
            log.warn(
                "MCP servers not resolved (deleted, or outside agent tenant {}), skipped from spec: mcpIds={}",
                agentTenantId,
                missingMcpIds - heldStdio.keys - heldDisabled.keys,
            )
        }
        if (heldStdio.isNotEmpty()) {
            log.warn(
                "MCP server(s) {} are stdio and stdio is disabled on this deployment, so they were skipped from the spec",
                heldStdio.values.map { "${it.id}(${it.name})" },
            )
        }
        if (heldDisabled.isNotEmpty()) {
            log.info("MCP server(s) {} are disabled, skipping", heldDisabled.values.map { "${it.name} (id=${it.id})" })
        }
        // Derived from what was actually resolved, like `skillDetails` below: a binding whose server row is
        // gone would otherwise still contribute its env bindings to the other half of the answer
        val mcpListJson = serializeMcpBindings(mcpBindings.filter { it.mcpId in mcpById }, agentTenantId)

        val mcpDetails = mcpIdsToDeliver.mapNotNull { mcpId ->
            val mcp = mcpById[mcpId]
            if (mcp == null) {
                null
            } else {
                McpDetailDto(
                    id = mcp.id,
                    name = mcp.name,
                    description = mcp.description,
                    type = mcp.type,
                    command = mcp.command,
                    url = mcp.url,
                    // Tells the runtime *how* to authenticate. Without it an OAuth server looks like a
                    // header one, and the per-user token has nowhere to go.
                    authType = mcp.authType,
                    // Delivered decrypted: agent-service holds no AES key. See plainConfigJson().
                    headers = plainConfigJson(mcp.headers),
                    envParams = plainToolEnvJson(mcp.envParams),
                    status = mcp.status,
                )
            }
        }

        // ── Skill bindings (full detail DTOs) ──
        val skillIdsToDeliver = skillIds.distinct()
        // Tenant-guarded exactly as a binding is at save time, and for the same reason: `selectByIds`
        // has no tenant condition and an internal call carries no trustworthy tenant header, so a
        // cross-tenant binding row would otherwise hand over another tenant's SKILL.md and resources.
        val skillById = skillBindingResolver.deliverable(skillIdsToDeliver, agentTenantId).associateBy { it.id }

        val skillDetails = skillIdsToDeliver.mapNotNull { skillId ->
            val skill = skillById[skillId]
            if (skill == null) {
                log.warn("Skill not resolved (deleted, or outside agent tenant {}), skipped from spec: skillId={}", agentTenantId, skillId)
                null
            } else if (skill.status == 0) {
                // The same gate the CLI branch below applies to a disabled CLI — and, since the package
                // model, the one `cliDetails[].skill` applies to a CLI's own skill. A skill an operator
                // switched off — or that a re-import stored disabled because SkillContentScanner flagged
                // it — must not reach the harness just because a binding still points at it; honouring
                // the flag is the whole point of it
                log.info("Skill '{}' (id={}) is disabled, skipping", skill.name, skill.id)
                null
            } else {
                skillDetail(skill)
            }
        }
        // ── CLI bindings (full detail DTOs, each carrying the skill it ships) ──
        val cliDetails = if (cliBindings.isEmpty()) {
            emptyList()
        } else {
            val cliIds = cliBindings.map { it.cliId }.distinct()
            val clisById = cliMapper.selectByIds(cliIds).associateBy { it.id }
            val skillIds = clisById.values.mapNotNull { it.skillId }.distinct()
            val skillById = if (skillIds.isEmpty()) emptyMap() else skillMapper.selectByIds(skillIds).associateBy { it.id }
            cliBindings.mapNotNull { binding ->
                val cli = clisById[binding.cliId]
                if (cli == null) {
                    log.warn("CLI not found: cliId={}", binding.cliId)
                    null
                } else if (cli.status == 0) {
                    log.info("CLI '{}' (id={}) is disabled, skipping", cli.name, cli.id)
                    null
                } else {
                    CliDetailDto(
                        id = cli.id,
                        name = cli.name,
                        version = cli.version,
                        checkCommand = cli.checkCommand,
                        packageObject = cli.packageObject,
                        packageDigest = cli.packageDigest,
                        payloadDigest = cli.payloadDigest,
                        depsApt = readStringList(cli.depsApt),
                        runtimeEnv = readStringMap(cli.runtimeEnv),
                        envBindings = mergeCliEnvBindings(binding.envBindings, cli.envParams, agentTenantId),
                        skill = cli.skillId?.let { skillId ->
                            val skill = skillById[skillId]
                            when {
                                skill == null -> {
                                    log.warn("CLI '{}' (id={}) points at skill {} which is gone", cli.name, cli.id, skillId)
                                    null
                                }
                                // The package registrar keeps this row in step with `cli.status` (I5),
                                // so this only catches a skill switched off on its own — e.g. one the
                                // content scanner stored disabled — which must not reach the harness
                                skill.status == 0 -> {
                                    log.info("Skill '{}' (id={}) of CLI '{}' is disabled, skipping it", skill.name, skill.id, cli.name)
                                    null
                                }
                                else -> skillDetail(skill)
                            }
                        },
                    )
                }
            }
        }

        // ── Model + Provider full config (reuse already-queried model) ──
        val modelConfig = if (model != null) {
            val provider = modelProviderMapper.selectById(model.providerId)
            ModelConfigDto(
                modelId = model.id,
                modelName = model.modelName,
                modelType = model.modelType,
                providerType = provider?.type ?: "unknown",
                apiKey = provider?.apiKey,
                baseUrl = provider?.baseUrl,
                supportInternet = model.supportInternet,
                supportReasoning = model.supportReasoning,
                supportTool = model.supportTool,
                supportVision = model.supportVision,
                supportMcp = model.supportMcp,
            )
        } else {
            null
        }

        return AgentSpecInfoResponse(
            agentId = agentId,
            agentName = agentName,
            description = description,
            systemPrompt = systemPrompt,
            modelId = modelId,
            toolList = toolListJson,
            mcpList = mcpListJson,
            enableThink = enableThink,
            enableSearch = enableSearch,
            enablePlan = enablePlan,
            permissionMode = permissionMode,
            modelSupportInternet = model?.supportInternet ?: 0,
            modelSupportReasoning = model?.supportReasoning ?: 0,
            modelThinkingMode = model?.thinkingMode ?: 0,
            modelConfig = modelConfig,
            toolDetails = toolDetails,
            mcpDetails = mcpDetails,
            skillDetails = skillDetails,
            cliDetails = cliDetails,
        )
    }

    /**
     * Resolve env bindings JSON: for each binding with envVarId,
     * try to get latest value from env_variable table, fallback to stored snapshot.
     * For customValue, use directly.
     * Returns a list of {envKey, envValue} maps for runtime use.
     *
     * [tenantId] is the tenant of the holder being delivered to (its agent or team row), taken from
     * the database rather than the request: an internal call carries no tenant header, and this is the
     * one place a binding's `envVarId` is followed. Without it a stale cross-tenant reference resolved
     * another tenant's secret — the asymmetry the MCP row filter above already closes.
     */
    @Suppress("UNCHECKED_CAST")
    private fun resolveEnvBindingsJson(
        storedJson: String?,
        tenantId: Long,
    ): List<Map<String, String>> {
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
                        val latest = envVariableService.getDecryptedValue(envVarId, tenantId)
                        if (latest == null && snapshotValue == null) {
                            // A reference carries no value of its own, so a variable that is gone
                            // leaves nothing to deliver: the tool sees the parameter as unconfigured.
                            log.warn(
                                "Env binding '{}' references env variable {} that no longer resolves in tenant {}; " +
                                    "delivering nothing for it",
                                envKey,
                                envVarId,
                                tenantId,
                            )
                        }
                        latest ?: snapshotValue
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

    /**
     * Env values one CLI binding delivers: the per-agent values from `agent_cli_binding`, topped up
     * with the defaults the CLI itself declares in `cli.env_params`.
     *
     * Without the top-up an installed CLI reaches the sandbox with no credentials at all — the
     * payload lands, `check_command` passes, and every invocation then fails on "not logged
     * in", which is a silent failure no gate reports. The `ToolEnvParamEntry` entries marked
     * `secret` are stored encrypted and only this service holds the AES key, so decryption happens
     * here on the way out, the same way [plainToolEnvJson] does it.
     *
     * Merged key by key rather than "one set or the other": an agent that overrides a single
     * parameter must not lose the defaults of the CLI's remaining ones. A declaration with no value
     * is dropped instead of delivered empty — an absent variable and `GH_TOKEN=` are different
     * states to a CLI that decides whether it is configured by looking for the name.
     */
    private fun mergeCliEnvBindings(
        storedJson: String?,
        declaredEnvJson: String?,
        tenantId: Long,
    ): List<Map<String, String>> {
        val perAgent = resolveEnvBindingsJson(storedJson, tenantId)
        if (declaredEnvJson.isNullOrBlank()) return perAgent
        val overridden = perAgent.mapNotNull { it["envKey"] }.toSet()
        val defaults = secretFieldEncryptor
            .decryptToolEnvParamsToMap(declaredEnvJson)
            .filter { (key, value) -> key.isNotBlank() && key !in overridden && value.isNotBlank() }
            .map { (key, value) -> mapOf("envKey" to key, "envValue" to value) }
        return perAgent + defaults
    }

    private fun skillDetail(skill: Skill): SkillDetailDto = SkillDetailDto(
        id = skill.id,
        name = skill.name,
        description = skill.description,
        skillmd = skill.skillmd,
        resources = skill.resources,
        version = skill.version,
    )

    /** Registrar-owned JSON columns: a malformed value delivers nothing rather than failing the call. */
    private fun readStringList(json: String?): List<String> = readJson(json) {
        objectMapper.readValue(it, objectMapper.typeFactory.constructCollectionType(List::class.java, String::class.java)) as List<String>
    } ?: emptyList()

    private fun readStringMap(json: String?): Map<String, String> = readJson(json) {
        @Suppress("UNCHECKED_CAST")
        objectMapper.readValue(it, Map::class.java) as Map<String, String>
    } ?: emptyMap()

    private fun <T : Any> readJson(
        json: String?,
        parse: (String) -> T,
    ): T? {
        if (json.isNullOrBlank()) return null
        return try {
            parse(json)
        } catch (e: Exception) {
            log.warn("Could not read a stored CLI column as JSON: {}", e.message)
            null
        }
    }

    /**
     * Legacy JSON shape of an agent's MCP bindings: `[{id, env_bindings:[{envKey, envValue}]}]`.
     *
     * agent-service reads `env_bindings` from here (see `AgentSpecResolver`), while the server
     * configuration itself arrives in `mcpDetails`.
     */
    private fun serializeMcpBindings(
        bindings: List<AgentMcpBinding>,
        tenantId: Long,
    ): String = objectMapper.writeValueAsString(
        bindings.map { binding ->
            mapOf(
                "id" to binding.mcpId,
                "env_bindings" to resolveEnvBindingsJson(binding.envBindings, tenantId),
            )
        },
    )

    /**
     * Deliver a stored `McpConfigEntry` array as a flat plain-text JSON object.
     *
     * Entries marked `secret` are encrypted at rest with [AesUtil], and that key lives in this
     * service only — agent-service must not carry it. So decryption happens here, on the way out,
     * exactly the way [resolveEnvBindingsJson] already resolves binding-level env values to plain
     * text. The receiving end parses the object; see `PlaintextMcpConfigDecryptor` in harness-core.
     *
     * Blank stays null, so "nothing configured" remains distinguishable from "configured, empty".
     */
    private fun plainConfigJson(storedJson: String?): String? = storedJson?.takeIf { it.isNotBlank() }?.let {
        serializeDecrypted(secretFieldEncryptor.decryptToMap(it), it)
    }

    /** Same as [plainConfigJson], for the `ToolEnvParamEntry` array shape (envParamName / defaultValue). */
    private fun plainToolEnvJson(storedJson: String?): String? = storedJson?.takeIf { it.isNotBlank() }?.let {
        serializeDecrypted(secretFieldEncryptor.decryptToolEnvParamsToMap(it), it)
    }

    private fun serializeDecrypted(values: Map<String, String>, storedJson: String): String {
        // Both decrypt* helpers swallow parse and key failures into an empty map. Delivering "{}" is
        // still the right answer for the caller, but it must not look like a clean "no headers".
        if (values.isEmpty() && storedJson.trim() != "[]") {
            log.warn("Stored config payload could not be resolved to plain values; delivering an empty object")
        }
        return objectMapper.writeValueAsString(values)
    }

    // ========================================
    // Session capability / permission updates
    // (for agent-service to call instead of direct DB writes)
    // ========================================

    data class CapabilityToggleRequest(
        val capability: String, // "search" | "thinking" | "plan" | "bypass"
        val enable: Boolean,
    )

    data class PermissionModeRequest(
        val mode: String, // "DEFAULT" | "BYPASS" | "ACCEPT_EDITS" | "EXPLORE" | "DONT_ASK"
    )

    /**
     * Toggle a session/channel capability (search, thinking, plan, bypass).
     * Agent-service calls this instead of directly updating session/channel tables.
     */
    @PutMapping("/sessions/{sessionId}/capabilities")
    fun toggleCapability(
        @PathVariable sessionId: String,
        @RequestBody request: CapabilityToggleRequest,
    ): ResultVo<String> {
        return try {
            val flag = if (request.enable) 1 else 0
            val capability = request.capability.lowercase()
            val now = java.time.LocalDateTime.now()

            if (sessionId.startsWith("chn-")) {
                val channel = channelMapper.selectBySessionId(sessionId)
                    ?: return ResultVo.error("Channel not found: $sessionId")
                if (capability == "thinking" && flag == 0 && isThinkingRequired(agentMapper.selectById(channel.agentId)?.modelId)) {
                    return ResultVo.error("Current model requires Deep Thinking and it cannot be turned off.")
                }
                when (capability) {
                    "search" -> channel.enableSearch = flag
                    "thinking" -> channel.enableThink = flag
                    "plan" -> channel.enablePlan = flag
                    "bypass" -> channel.permissionMode = if (request.enable) "BYPASS" else "DEFAULT"
                    else -> return ResultVo.error("Unknown capability: $capability")
                }
                channel.updateTime = now
                channelMapper.updateById(channel)
            } else {
                val session = sessionMapper.selectBySessionIdAndStatus(sessionId, 1)
                    ?: return ResultVo.error("Session not found: $sessionId")
                if (capability == "thinking" && flag == 0 && isThinkingRequired(session.modelId)) {
                    return ResultVo.error("Current model requires Deep Thinking and it cannot be turned off.")
                }
                when (capability) {
                    "search" -> session.enableSearch = flag
                    "thinking" -> session.enableThink = flag
                    "plan" -> session.enablePlan = flag
                    "bypass" -> session.permissionMode = if (request.enable) "BYPASS" else "DEFAULT"
                    else -> return ResultVo.error("Unknown capability: $capability")
                }
                session.updateTime = now
                sessionMapper.updateById(session)
            }

            log.info("[Admin] Capability toggled: sessionId={}, capability={}, enable={}", sessionId, capability, request.enable)
            ResultVo.success("OK")
        } catch (e: Exception) {
            log.error("Failed to toggle capability: sessionId={}", sessionId, e)
            ResultVo.error("Failed to toggle capability: ${e.message}")
        }
    }

    private fun isThinkingRequired(modelId: Long?): Boolean {
        if (modelId == null) return false
        val model = modelMapper.selectById(modelId) ?: return false
        return model.thinkingMode == 2
    }

    /**
     * Change a session/channel permission mode.
     * Agent-service calls this instead of directly updating session/channel tables.
     */
    @PutMapping("/sessions/{sessionId}/permission-mode")
    fun updatePermissionMode(
        @PathVariable sessionId: String,
        @RequestBody request: PermissionModeRequest,
    ): ResultVo<String> {
        return try {
            val mode = request.mode.uppercase()
            if (mode !in VALID_PERMISSION_MODES) {
                return ResultVo.error("Invalid permission mode: '${request.mode}'. Valid: ${VALID_PERMISSION_MODES.joinToString(", ")}")
            }

            val now = java.time.LocalDateTime.now()

            if (sessionId.startsWith("chn-")) {
                val channel = channelMapper.selectBySessionId(sessionId)
                    ?: return ResultVo.error("Channel not found: $sessionId")
                channel.permissionMode = mode
                channel.updateTime = now
                channelMapper.updateById(channel)
            } else {
                val session = sessionMapper.selectBySessionIdAndStatus(sessionId, 1)
                    ?: return ResultVo.error("Session not found: $sessionId")
                session.permissionMode = mode
                session.updateTime = now
                sessionMapper.updateById(session)
            }

            log.info("[Admin] Permission mode updated: sessionId={}, mode={}", sessionId, mode)
            ResultVo.success("OK")
        } catch (e: Exception) {
            log.error("Failed to update permission mode: sessionId={}", sessionId, e)
            ResultVo.error("Failed to update permission mode: ${e.message}")
        }
    }

    companion object {
        private val VALID_PERMISSION_MODES = setOf("DEFAULT", "BYPASS", "ACCEPT_EDITS", "EXPLORE", "DONT_ASK")
    }
}

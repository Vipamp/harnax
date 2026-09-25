package com.agnetix.harnax.harness

import com.agnetix.harnax.agent.AgentSpec
import com.agnetix.harnax.agent.ChatSpec
import com.agnetix.harnax.agent.CliSpec
import com.agnetix.harnax.agent.adaptor.ChatModelConfigAdaptor
import com.agnetix.harnax.agent.adaptor.McpAccessTokenSourceFactory
import com.agnetix.harnax.agent.adaptor.McpConfigAdaptor
import com.agnetix.harnax.agent.adaptor.PlanNote
import com.agnetix.harnax.agent.adaptor.PlanNoteAdaptor
import com.agnetix.harnax.agent.adaptor.ProcessLogAdaptor
import com.agnetix.harnax.agent.adaptor.SkillAdaptor
import com.agnetix.harnax.agent.adaptor.TokenStatAdaptor
import com.agnetix.harnax.agent.adaptor.mcp.McpHelper
import com.agnetix.harnax.agent.adaptor.model.ModelErrorCode
import com.agnetix.harnax.agent.adaptor.model.ModelHelper
import com.agnetix.harnax.agent.adaptor.token.TokenStatBuilder
import com.agnetix.harnax.agent.provider.MIDDLEWARE_SET
import com.agnetix.harnax.agent.provider.middleware.ProcessLogMiddleware
import com.agnetix.harnax.agent.session.SessionConfig
import com.agnetix.harnax.agent.session.SessionLoader
import com.agnetix.harnax.common.mcp.McpConfigDecryptor
import com.agnetix.harnax.entity.McpAuthTypes
import com.agnetix.harnax.harness.config.HarnessConfig
import com.agnetix.harnax.harness.config.MinioConfig
import com.agnetix.harnax.harness.minio.MinioBaseStore
import com.agnetix.harnax.harness.minio.MinioSnapshotClient
import com.agnetix.harnax.harness.output.OutputFileDetector
import com.agnetix.harnax.harness.output.OutputFileStore
import com.agnetix.harnax.harness.sandbox.CliImageBuilder
import com.agnetix.harnax.harness.sandbox.CliPackageStore
import com.agnetix.harnax.harness.sandbox.KeepAliveSandboxManager
import com.agnetix.harnax.harness.team.TeamLeadToolBox
import com.agnetix.harnax.harness.team.TeamMemberSpec
import com.agnetix.harnax.harness.team.TeamMemberToolBox
import com.agnetix.harnax.harness.team.TeamOrchestrator
import com.agnetix.harnax.harness.team.TeamRole
import com.agnetix.harnax.harness.team.TeamSessions
import com.agnetix.harnax.harness.team.leadOrchestrationPrompt
import com.agnetix.harnax.tools.sdk.SessionMetaContext
import com.agnetix.harnax.tools.sdk.ToolMeta
import com.agnetix.harnax.tools.sdk.UserIdentifier
import com.agnetix.harnax.tools.sdk.adaptor.ToolCallLogAdaptor
import com.agnetix.harnax.tools.sdk.adaptor.ToolConfigAdaptor
import com.agnetix.harnax.tools.sdk.registry.ToolRegistry
import io.agentscope.core.message.Msg
import io.agentscope.core.permission.PermissionBehavior
import io.agentscope.core.permission.PermissionContextState
import io.agentscope.core.permission.PermissionRule
import io.agentscope.core.state.AgentState
import io.agentscope.core.state.AgentStateStore
import io.agentscope.core.tool.mcp.McpClientWrapper
import io.agentscope.harness.agent.DistributedStore
import io.agentscope.harness.agent.IsolationScope
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec
import io.agentscope.harness.agent.sandbox.snapshot.LocalSnapshotSpec
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Harness-based agent launcher — the distributed counterpart of
 * [com.agnetix.harnax.agent.AscopeAgentLauncher].
 *
 * Key changes in agentscope 2.0.0:
 * - `Session` → `AgentStateStore` (loaded via [SessionLoader])
 * - `Hook` → `MiddlewareBase`
 * - `SandboxDistributedOptions` → `DistributedStore`
 * - `PlanNotebook` → `enablePlanMode()` (v2 plan mode is markdown-based)
 * - `structuredOutputReminder` → removed (model layer handles natively)
 * - `DockerFilesystemSpec` moved to `io.agentscope.harness.agent.sandbox.impl.docker`
 *
 * @param chatModelConfigAdaptor adaptor for chat model configuration
 * @param mcpConfigAdaptor adaptor for MCP service configuration
 * @param stateStore distributed [AgentStateStore] backend (replaces Session)
 * @param skillAdaptor adaptor for skill loading
 * @param tokenStatAdaptor adaptor for token stat persistence
 * @param processLogAdaptor adaptor for process logging
 * @param toolCallLogAdaptor adaptor for tool call logging (optional)
 * @param planNoteAdaptor adaptor for plan note persistence
 * @param workspaceRoot local workspace root directory
 * @param harnessConfig harness runtime configuration
 * @param mcpTokenSourceFactory binds the per-user OAuth token source to one agent instance; absent
 * means an OAuth MCP server cannot be connected at all (design section 7.3)
 * @param minioConfig optional MinIO configuration for distributed storage
 */
class HarnessAgentLauncher(
    val chatModelConfigAdaptor: ChatModelConfigAdaptor,
    val mcpConfigAdaptor: McpConfigAdaptor,
    val stateStore: AgentStateStore,
    val skillAdaptor: SkillAdaptor,
    val tokenStatAdaptor: TokenStatAdaptor,
    val processLogAdaptor: ProcessLogAdaptor,
    val toolCallLogAdaptor: ToolCallLogAdaptor,
    val planNoteAdaptor: PlanNoteAdaptor,
    val workspaceRoot: Path,
    val harnessConfig: HarnessConfig = HarnessConfig(),
    val minioConfig: MinioConfig? = null,
    val keepAliveSandboxManager: KeepAliveSandboxManager? = null,
    val snapshotSpec: SandboxSnapshotSpec? = null,
    val mcpConfigDecryptor: McpConfigDecryptor? = null,
    val toolConfigAdaptor: ToolConfigAdaptor? = null,
    val toolRegistry: ToolRegistry? = null,
    val cliImageBuilder: CliImageBuilder? = null,
    val outputFileDetector: OutputFileDetector? = null,
    val outputFileStore: OutputFileStore? = null,
    val mcpTokenSourceFactory: McpAccessTokenSourceFactory? = null,
) {

    private val log = LoggerFactory.getLogger(HarnessAgentLauncher::class.java)

    /**
     * Graceful shutdown hook — called by Spring when the application context closes.
     *
     * Persists workspace snapshots for all managed sandboxes before the process exits,
     * ensuring workspace state survives service restarts.
     */
    fun shutdown() {
        log.info("[harness] Shutdown hook triggered, persisting all sandbox snapshots...")
        keepAliveSandboxManager?.persistAll()
        log.info("[harness] Shutdown hook complete")
    }

    /**
     * Creates a single [HarnessAgentWrapper] for the given session.
     */
    fun createSingleAgent(
        agentSpec: AgentSpec,
        sessionId: String = UUID.randomUUID().toString(),
        stateless: Boolean = false,
        chatSpec: ChatSpec = ChatSpec.builder().build(),
        userIdentifier: UserIdentifier,
    ): HarnessAgentWrapper = createAgentBase(
        agentSpec,
        sessionId,
        stateless,
        chatSpec,
        userIdentifier,
    )

    /**
     * Creates the lead of a team: the session's own agent, assembled so that delegating is the only thing
     * it can do (design section 5).
     *
     * The caller holds the [orchestrator], because building a member needs the per-thread spec context
     * this service maintains for its adaptors — see `DefaultAgentRunner.buildTeamAgent`.
     */
    fun createTeamLead(
        orchestrator: TeamOrchestrator,
        agentSpec: AgentSpec,
        sessionId: String,
        chatSpec: ChatSpec,
        userIdentifier: UserIdentifier,
    ): HarnessAgentWrapper = createAgentBase(
        agentSpec = agentSpec,
        sessionId = sessionId,
        chatSpec = chatSpec,
        userIdentifier = userIdentifier,
        teamRole = TeamRole.Lead(orchestrator),
    )

    /**
     * Creates one member as an agent in its own right: its own model, tools, MCP, skills, sandbox and
     * child session, plus the artifact tools that let it hand work back (design D5).
     */
    fun createTeamMember(
        orchestrator: TeamOrchestrator,
        member: TeamMemberSpec,
        childSessionId: String,
        rootSessionId: String,
        userIdentifier: UserIdentifier,
    ): HarnessAgentWrapper = createAgentBase(
        agentSpec = member.agentSpec,
        sessionId = childSessionId,
        chatSpec = member.chatSpec,
        userIdentifier = userIdentifier,
        // An OAuth MCP grant belongs to whoever opened the root session, and admin resolves it from that
        // session id. A child session is an internal key admin has never heard of.
        authSessionId = rootSessionId,
        teamRole = TeamRole.Member(orchestrator, member),
    )

    private fun createAgentBase(
        agentSpec: AgentSpec,
        sessionId: String,
        stateless: Boolean = false,
        chatSpec: ChatSpec = ChatSpec.builder().build(),
        userIdentifier: UserIdentifier,
        authSessionId: String = sessionId,
        teamRole: TeamRole? = null,
    ): HarnessAgentWrapper {
        val isLead = teamRole is TeamRole.Lead
        val needConfirmedTools = mutableSetOf<String>()
        val dangerousInputTools = mutableSetOf<String>()

        val effectivePrompt = agentSpec.systemPrompt +
            (if (chatSpec.enableSearch) "\n\n$SEARCH_CAPABILITY_HINT" else "") +
            "\n\n" +
            when (teamRole) {
                // A lead has no workspace and nothing to execute: what it has to know is who is on the
                // team and that the work belongs to someone else.
                is TeamRole.Lead -> leadOrchestrationPrompt(teamRole.orchestrator.spec)
                // Auto-detected output files are the ordinary path's contract with the WebUI. A member's
                // files reach the team only as artifacts, so it gets that rule instead (design 8.2).
                is TeamRole.Member -> TEAM_MEMBER_FILE_INSTRUCTION
                null -> OUTPUT_FILE_INSTRUCTION
            }

        val agentBuilder = HarnessAgentBuilder()
            .name(agentSpec.name)
            .description(agentSpec.description)
            .maxIters(agentSpec.maxIterNum)
            .systemPrompt(effectivePrompt)
            .workspace(workspaceRoot.resolve(agentSpec.name).resolve(sessionId))
            .stateStore(stateStore)

        val chatModelConfig = chatModelConfigAdaptor.getConfig(agentSpec.chatModelId)
            ?: throw ModelErrorCode.MODEL_CONFIG_NOT_FOUND.format(agentSpec.chatModelId)
        val chatModel = ModelHelper.createChatModel(
            chatModelConfig,
            chatSpec.enableThinking,
            chatSpec.enableSearch,
        )
        agentBuilder.model(chatModel)

        // ----- MCP -----
        // Collected so the clients can be closed when this agent is dropped: `HarnessAgent.close()`
        // only unbinds the state saver and clears the state cache, it does not touch the toolkit's MCP
        // clients, and a stdio client is an OS process. See `mcpClients` on HarnessAgentWrapper.
        val mcpClients = mutableListOf<McpClientWrapper>()
        // A lead has no business tools to reach: MCP is a member concern (design section 5).
        val mcpServices = if (isLead) emptyList() else agentSpec.mcpServices
        mcpServices.forEach { mcpSpec ->
            val mcpConfig = mcpConfigAdaptor.getConfig(mcpSpec.mcpId)
            if (mcpConfig == null) {
                log.warn("Mcp config with id `${mcpSpec.mcpId}` not found.")
                return@forEach
            }
            // Skip disabled servers (status=0). Disabling is deliberate, so it must not be
            // reported as a missing config either way.
            if (mcpConfig.status == 0) {
                log.info("MCP server '{}' (id={}) is disabled, skipping", mcpConfig.name, mcpConfig.id)
                return@forEach
            }
            // Second line of defence against stdio, which is a process started here rather than a
            // connection made: the admin holds such rows back from delivery while its own switch is
            // off, so arriving here means the two services are configured differently. Refuse anyway -
            // this is the container that runs without isolation against the host.
            if (mcpConfig.type.equals(STDIO_TYPE, ignoreCase = true) && !harnessConfig.mcpStdioEnabled) {
                log.warn(
                    "MCP server '{}' (id={}) is stdio and stdio is disabled on this runtime, skipping",
                    mcpConfig.name,
                    mcpConfig.id,
                )
                return@forEach
            }
            // OAuth belongs to a person, and this instance serves one person: the source is bound here
            // so no later request through this client can present anyone else's token. A session with
            // no user behind it (a channel conversation, a service key) has no grant to spend, and an
            // OAuth server is left out of the toolkit rather than connected unauthenticated.
            val tokenSource = if (mcpConfig.authType == McpAuthTypes.OAUTH2) {
                mcpTokenSourceFactory?.forUser(authSessionId, userIdentifier.userId) ?: run {
                    log.warn(
                        "MCP server '{}' (id={}) authorizes per user (${McpAuthTypes.OAUTH2}) but session {} " +
                            "has no user identity to authorize as, skipping",
                        mcpConfig.name,
                        mcpConfig.id,
                        authSessionId,
                    )
                    return@forEach
                }
            } else {
                null
            }
            // Warm it here, on the thread that is already building this agent. The customizer reads the
            // same source from whichever pool the MCP client sends on - the async handshake runs on the
            // common fork-join pool - and a cold mint there parks one of its few threads for a whole
            // admin round trip. A failure is not fatal and is not reported: the user may authorize
            // mid-session, and the per-request callback is what surfaces that to them.
            tokenSource?.let { source ->
                runCatching { source.accessToken(mcpConfig.id) }.exceptionOrNull()?.let {
                    log.debug("MCP server '{}' (id={}) has no token yet: {}", mcpConfig.name, mcpConfig.id, it.message)
                }
            }
            // One unreachable server must not cost the agent every tool it has: same shape as Admin
            // dropping an unresolvable binding from the spec. `client` is closed on the way out of a
            // failed registration because `addMcp` gives up on a timer while its registration thread
            // keeps going — the client may well be live and simply unacknowledged.
            var client: McpClientWrapper? = null
            try {
                val created = McpHelper.createMcpClient(
                    mcpConfig,
                    mcpSpec.isAsync,
                    mcpConfigDecryptor?.let { d -> d::decryptToMap },
                    mcpConfigDecryptor?.let { d -> d::decryptToolEnvParamsToMap },
                    tokenSource,
                )
                client = created
                agentBuilder.addMcp(created)
                mcpClients += created
            } catch (e: Exception) {
                log.warn(
                    "MCP server '{}' (id={}) failed to load, the agent is built without it: {}",
                    mcpConfig.name,
                    mcpConfig.id,
                    e.message,
                )
                client?.let { c -> McpHelper.closeQuietly(c, mcpConfig.name) }
            }
        }

        // Say it once in aggregate as well. Every skip above has its own line, but the symptom someone
        // reports is "the agent has no MCP tools", and that is only visible here.
        if (mcpClients.size < mcpServices.size) {
            log.warn(
                "Agent '{}' for session {} was built with {} of {} bound MCP servers",
                agentSpec.name,
                sessionId,
                mcpClients.size,
                mcpServices.size,
            )
        }

        // ----- Tools -----
        // A lead is assembled with the team tools only (design section 5). The meta tool is off for it as
        // well: that one lets an agent acquire tools at runtime, which would hand back what assembly withheld.
        if (!isLead) {
            agentSpec.enableMetaTool?.let { agentBuilder.enableMetaTool(it) }
        }

        if (!isLead && agentSpec.toolSpecs.isNotEmpty() && toolConfigAdaptor != null) {
            // Dynamic tool assembly from agentSpec.toolSpecs
            // Deduplicate ToolBox additions by beanName (multiple agent_tool records may share the same beanName)
            val addedToolBoxBeans = mutableSetOf<String>()
            // Tool names this agent is actually granted, per beanName. addTool registers ALL @Tool
            // methods of a ToolBox, so anything outside this set is removed afterwards.
            val allowedToolNamesByBean = mutableMapOf<String, MutableSet<String>>()
            // Track already-scanned ToolBox classes to avoid redundant reflection
            val scannedDangerousInputClasses = mutableSetOf<Class<*>>()

            agentSpec.toolSpecs.forEach { toolSpec ->
                val toolConfig = toolConfigAdaptor.getToolConfig(toolSpec.toolId)
                if (toolConfig != null) {
                    // Skip disabled tools (status=0) — they should not be available to the agent.
                    // They are absent from allowedToolNamesByBean, so a sibling ToolBox registration
                    // cannot leak them back in; the final sweep removes them from the toolkit.
                    if (toolConfig.status == 0) {
                        log.info("Tool '{}' (id={}) is disabled, skipping", toolConfig.name, toolConfig.id)
                        return@forEach
                    }

                    val beanName = toolConfig.beanName ?: ""
                    val resolvedTool: Any? = if (beanName.isNotEmpty() && beanName !in addedToolBoxBeans) {
                        val toolBox = toolRegistry?.createToolBoxInstance(beanName)
                        if (toolBox != null) {
                            toolBox.init(
                                toolCallLogAdaptor,
                                SessionMetaContext(agentSpec.attributableAgentId, sessionId),
                                userIdentifier,
                            )
                            agentBuilder.addTool(toolBox)
                            addedToolBoxBeans.add(beanName)
                        }
                        toolBox
                    } else {
                        // Already added this ToolBox, just return it for needConfirm handling
                        // Note: for needConfirm we only need the name, so singleton is fine here
                        toolRegistry?.getToolBox(beanName)
                    }

                    if (resolvedTool != null) {
                        // Per-method needConfirm from DB record, or from toolSpec override
                        val shouldConfirm = toolConfig.needConfirm == 1 || toolSpec.needConfirm
                        if (shouldConfirm) {
                            // Use the framework tool name (@Tool.name) so PermissionEngine can match
                            needConfirmedTools.add(toolConfig.name)
                        }

                        // Scan ToolBox methods for @ToolMeta(dangerousInput=true) — deduplicated by class
                        if (beanName.isNotEmpty()) {
                            allowedToolNamesByBean.getOrPut(beanName) { mutableSetOf() }.add(toolConfig.name)
                            val toolBoxClass = toolRegistry?.getToolBox(beanName)?.let { it::class.java }
                            if (toolBoxClass != null && toolBoxClass !in scannedDangerousInputClasses) {
                                scannedDangerousInputClasses.add(toolBoxClass)
                                collectDangerousInputTools(toolBoxClass, dangerousInputTools)
                            }
                        }
                    } else {
                        log.warn("Tool with id `${toolSpec.toolId}` not found, skipping.")
                    }
                } else {
                    log.warn("Tool config with id `${toolSpec.toolId}` not found, skipping.")
                }
            }

            // addTool registers every @Tool method of a ToolBox. Drop the methods this agent was not
            // granted: unselected siblings of a required tool, and admin-disabled methods. The toolkit
            // keys tools by name, so names granted through any ToolBox are kept.
            val grantedNames = allowedToolNamesByBean.values.flatten().toSet()
            addedToolBoxBeans.forEach { bean ->
                toolRegistry?.getToolMeta(bean)?.methods
                    ?.map { it.toolName }
                    ?.filterNot { it in grantedNames }
                    ?.forEach { name ->
                        log.debug("Tool '{}' of ToolBox '{}' is not granted to agent '{}', removing", name, bean, agentSpec.name)
                        agentBuilder.removeTool(name)
                    }
            }
        } else if (!isLead && agentSpec.toolSpecs.isNotEmpty()) {
            log.warn("ToolConfigAdaptor is not configured, {} tool spec(s) ignored.", agentSpec.toolSpecs.size)
        }

        if (agentSpec.contextForTools.isNotEmpty()) {
            log.info("[env-debug] Registering {} context(s) into ToolExecutionContext", agentSpec.contextForTools.size)
            val ctxBuilder = io.agentscope.core.tool.ToolExecutionContext.builder()
            agentSpec.contextForTools.forEach {
                log.info("[env-debug] Registering context type: {}", it::class.java.name)
                ctxBuilder.register(it)
            }
            agentBuilder.addToolContext(ctxBuilder.build())
            log.info("[env-debug] ToolExecutionContext registered with agent builder")
        } else {
            log.warn("[env-debug] agentSpec.contextForTools is EMPTY — no ToolExecutionContext set!")
        }

        // ----- Skills -----
        // A lead loads its skills the same way a member does: they are part of the team's own
        // configuration (design D5), and their text is what a skill mostly is. What a lead cannot do is
        // carry a skill's files — it has no sandbox to project them into — so a skill that ships any is
        // loaded for its instructions and reported for the rest, rather than dropped. The absent files
        // cannot strand the model on a `<files-root>` path the way a member's can: `disableShellTool()`
        // above makes the harness resolve to ShellPathPolicy.noShell(), which never renders that prefix.
        agentSpec.skills.forEach {
            // A miss means the row was deleted between delivery and build, or it holds something
            // `AgentSkill` refuses (see SkillAdaptorImpl). Either way the loader has already logged
            // which, and one unusable skill must not cost the agent every other capability it has.
            val skill = skillAdaptor.getSkill(it.skillId)
            if (skill != null) {
                agentBuilder.addSkill(skill)
                if (isLead && skill.resources.isNotEmpty()) {
                    log.warn(
                        "Skill '{}' (id={}) is loaded for lead '{}' without its {} file(s) {}: a lead has no filesystem or shell tool to reach them",
                        it.skillName,
                        it.skillId,
                        agentSpec.name,
                        skill.resources.size,
                        skill.resources.keys,
                    )
                }
            } else {
                log.warn("Skill '{}' (id={}) is not loaded.", it.skillName, it.skillId)
            }
        }

        // ----- Team tools -----
        // Registered after the tool sweep, so nothing on the ordinary path removes them: that sweep only
        // walks ToolBoxes known to the registry, and these are built here.
        val teamToolNames: Set<String> = when (teamRole) {
            is TeamRole.Lead -> {
                val toolBox = TeamLeadToolBox(teamRole.orchestrator)
                toolBox.init(toolCallLogAdaptor, SessionMetaContext(agentSpec.attributableAgentId, sessionId), userIdentifier)
                agentBuilder.addTool(toolBox)
                TeamLeadToolBox.TOOL_NAMES
            }

            is TeamRole.Member -> {
                val toolBox = TeamMemberToolBox(teamRole.orchestrator, teamRole.member.memberAgentId)
                toolBox.init(toolCallLogAdaptor, SessionMetaContext(agentSpec.attributableAgentId, sessionId), userIdentifier)
                agentBuilder.addTool(toolBox)
                TeamMemberToolBox.TOOL_NAMES
            }

            null -> emptySet()
        }

        // ----- Middleware (replaces Hooks in 2.0.0) -----
        // Dangerous-tool interception is fully handled by the built-in PermissionEngine
        // (ASK/ALLOW/DENY rules configured below via PermissionContextState).
        // No custom ConfirmToolsMiddleware needed.
        MIDDLEWARE_SET.forEach { middleware ->
            if (middleware is ProcessLogMiddleware) {
                middleware.initial(processLogAdaptor, agentSpec.attributableAgentId, agentSpec.name, sessionId)
            }
            agentBuilder.addMiddleware(middleware)
        }

        // ----- Memory -----
        // HarnessAgent always uses InMemoryMemory internally; no explicit memory configuration needed.
        if (stateless) {
            log.info("Stateless mode: HarnessAgent will use fresh InMemoryMemory for session={}", sessionId)
        }

        // ----- Plan (2.0.0: enablePlanMode replaces PlanNotebook) -----
        if (chatSpec.enablePlan) {
            agentBuilder.enablePlan(true)
        }

        // ----- CLI sandbox image (one per payload combination) and the env those packages ask for -----
        val cliEnv: Map<String, String> = cliEnvironment(agentSpec.cliSpecs)
        val resolvedSandboxImage: String = when {
            isLead || agentSpec.cliSpecs.isEmpty() -> harnessConfig.sandbox.image
            cliImageBuilder == null -> throw IllegalStateException(
                "Agent '${agentSpec.name}' selects CLI packages " +
                    "${agentSpec.cliSpecs.joinToString(",") { "${it.name} (${it.packageDigest.take(12)})" }} " +
                    "but this runtime has no MinIO to fetch them from — set harness.minio.enabled=true",
            )

            else -> {
                val image = cliImageBuilder.resolveImage(agentSpec.cliSpecs)
                log.info(
                    "Resolved CLI sandbox image '{}' for agent '{}' (CLIs: {})",
                    image,
                    agentSpec.name,
                    agentSpec.cliSpecs.joinToString(",") { it.name },
                )
                image
            }
        }

        // ----- Docker Sandbox + Snapshot (snapshotSpec pre-created in initLauncher) -----
        if (!isLead && harnessConfig.sandbox.enabled && snapshotSpec != null) {
            // DockerFilesystemSpec moved to io.agentscope.harness.agent.sandbox.impl.docker in 2.0.0
            // .sandboxStateStore() is removed — sandbox state is now managed via DistributedStore
            val dockerSpec = DockerFilesystemSpec()
                .image(resolvedSandboxImage)
                .workspaceRoot(harnessConfig.sandbox.workspaceRoot)
            if (cliEnv.isNotEmpty()) {
                dockerSpec.environment(cliEnv)
            }
            dockerSpec
                .isolationScope(harnessConfig.sandbox.isolationScope)
                .snapshotSpec(snapshotSpec)

            agentBuilder.filesystem(dockerSpec)

            // Build DistributedStore (replaces SandboxDistributedOptions in 2.0.0)
            val distributedStoreBuilder = DistributedStore.builder()
                .agentStateStore(stateStore)
            if (minioConfig != null) {
                distributedStoreBuilder.baseStore(
                    MinioBaseStore(
                        minioConfig.createMinioClient(),
                        minioConfig.storeBucket,
                        minioConfig.storePrefix,
                    ),
                )
            } else {
                // Use a no-op base store when MinIO is not configured
                distributedStoreBuilder.baseStore(
                    io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore(),
                )
            }
            distributedStoreBuilder.sandboxSnapshotSpec(snapshotSpec)
            agentBuilder.distributedStore(distributedStoreBuilder.build())
        } else if (!isLead && minioConfig != null) {
            // ----- MinIO distributed filesystem (non-sandbox mode) -----
            val minioStore = MinioBaseStore(
                minioConfig.createMinioClient(),
                minioConfig.storeBucket,
                minioConfig.storePrefix,
            )
            val remoteFsSpec = RemoteFilesystemSpec(minioStore)
                .isolationScope(IsolationScope.SESSION)
            agentBuilder.filesystem(remoteFsSpec)
        }

        // ----- Disable built-in features that conflict with Harnax custom middleware -----
        if (!harnessConfig.enableWorkspaceContext) {
            agentBuilder.disableWorkspaceContext()
        }
        if (!harnessConfig.enableMemoryHooks) {
            agentBuilder.disableMemoryHooks()
        }
        if (!harnessConfig.enableSessionPersistence) {
            agentBuilder.disableSessionPersistence()
        }
        if (isLead) {
            // A lead has nothing to read, run or reimplement: it has no workspace of its own, and the
            // framework's own subagents would be a second, unmanaged delegation path.
            agentBuilder.disableFilesystemTools()
            agentBuilder.disableShellTool()
            agentBuilder.disableSubagents()
        }

        // ----- Permission Context (ASK rules for dangerous tools + ALLOW rules for framework tools) -----
        // Framework tools (plan mode, todo, subagent) must always be allowed — without explicit ALLOW
        // rules, PermissionEngine defaults to ASK in DEFAULT mode, which blocks these internal tools.
        val frameworkAllowTools = setOf(
            "plan_enter", "plan_write", "plan_exit",
            "todo_write",
            "agent_spawn", "agent_send", "agent_list",
            "task_output", "task_list",
        ) + teamToolNames
        val permCtxBuilder = PermissionContextState.builder()
        frameworkAllowTools.forEach { toolName ->
            permCtxBuilder.addAllowRule(
                toolName,
                PermissionRule(toolName, "Framework tool — always allowed", PermissionBehavior.ALLOW, "harnax"),
            )
        }
        needConfirmedTools.forEach { toolName ->
            permCtxBuilder.addAskRule(
                toolName,
                PermissionRule(toolName, "Tool requires user confirmation", PermissionBehavior.ASK, "harnax"),
            )
        }
        val builtPermCtx = permCtxBuilder.build()
        agentBuilder.permissionContext(builtPermCtx)
        log.info(
            "PermissionContext configured: {} ALLOW rules (framework), {} ASK rules (user tools: {}), {} dangerous-input wrapped tools",
            frameworkAllowTools.size,
            needConfirmedTools.size,
            needConfirmedTools,
            dangerousInputTools.size,
        )

        // ----- Dangerous Input Wrapping -----
        // Wrap tools whose @Tool methods are annotated with @ToolMeta(dangerousInput=true).
        // This replaces the ReflectiveFunctionTool with a DangerousInputCheckingTool that
        // scans string inputs for dangerous commands/paths in checkPermissions() — bypass-immune.
        //
        // Skip wrapping if the tool already has a needConfirm ASK rule — the ASK rule fires at
        // PermissionEngine step ② (before step ③ checkPermissions), making the input scan redundant.
        dangerousInputTools.forEach { toolName ->
            if (toolName in needConfirmedTools) {
                log.debug("Skipping dangerousInput wrap for '{}': already has needConfirm ASK rule", toolName)
                return@forEach
            }
            try {
                agentBuilder.wrapWithDangerousInputCheck(toolName)
                log.info("Wrapped tool '{}' with DangerousInputCheckingTool", toolName)
            } catch (e: IllegalArgumentException) {
                log.warn("Could not wrap tool '{}' with dangerous input check: {}", toolName, e.message)
            }
        }

        // ----- Build -----
        val agent = agentBuilder.build()
        log.info(
            "HarnessAgent '{}' built for session={} [sandbox={}, minio={}]",
            agentSpec.name,
            sessionId,
            harnessConfig.sandbox.enabled,
            minioConfig != null,
        )

        return HarnessAgentWrapper(
            harnessAgent = agent,
            mcpClients = mcpClients,
            dangerousTools = needConfirmedTools + dangerousInputTools,
            tokenStatBuilder = TokenStatBuilder()
                .agentId(agentSpec.attributableAgentId)
                .sessionId(sessionId)
                .modelId(agentSpec.chatModelId),
            tokenStatAdaptor = tokenStatAdaptor,
            sessionId = sessionId,
            keepAliveSandboxManager = keepAliveSandboxManager,
            keepAliveSnapshotSpec = snapshotSpec,
            sandboxImage = resolvedSandboxImage,
            sandboxEnv = cliEnv,
            sandboxWorkspaceRoot = harnessConfig.sandbox.workspaceRoot,
            sandboxNetwork = harnessConfig.sandbox.network,
            permissionMode = chatSpec.permissionMode,
            configuredPermissionContext = builtPermCtx,
            outputFileDetector = if (teamRole is TeamRole.Member) null else outputFileDetector,
            outputFileStore = if (teamRole is TeamRole.Member) null else outputFileStore,
            teamOrchestrator = (teamRole as? TeamRole.Lead)?.orchestrator,
            // A team wrapper gets no turn budget of its own: the team layer's own limits wrap this
            // stream from the outside, and inside a team a long silence is normal (a member running a
            // long tool, a lead parked on a confirmation) rather than a stuck turn.
            turnTimeoutSeconds = if (teamRole == null) harnessConfig.turnTimeoutSeconds else 0L,
        )
    }

    /**
     * Environment the selected CLI packages ask the platform to inject, plus the agent's own bindings.
     *
     * admin guarantees a `runtimeEnv` value is either a literal or exactly one supported slot, so an
     * unresolved placeholder here means this deployment was never configured for it.
     */
    internal fun cliEnvironment(cliSpecs: List<CliSpec>): Map<String, String> {
        if (cliSpecs.isEmpty()) return emptyMap()
        val slots = mapOf(
            "platform.adminUrl" to harnessConfig.sandbox.platformAdminUrl,
            "platform.internalToken" to harnessConfig.sandbox.platformInternalToken,
        )
        val env = LinkedHashMap<String, String>()
        for (cli in cliSpecs) {
            for ((name, value) in cli.runtimeEnv) {
                val slot = RUNTIME_ENV_SLOT.matchEntire(value)?.groupValues?.get(1)
                val resolved = slot?.let { slots[it] }
                when {
                    slot == null -> env[name] = value
                    resolved.isNullOrBlank() -> log.warn(
                        "CLI '{}' asks for runtimeEnv {} = {} but this deployment publishes no {} — " +
                            "the variable is left unset so the CLI reports why it cannot work",
                        cli.name,
                        name,
                        value,
                        slot,
                    )

                    else -> env[name] = resolved
                }
            }
        }
        for (cli in cliSpecs) {
            env.putAll(cli.envBindings)
        }
        return env
    }

    /**
     * Clears chat history and plan notes for the given session.
     *
     * The sandbox container is destroyed (after persisting its workspace snapshot),
     * but the snapshot itself is preserved so that workspace files are restored
     * when the session is resumed.
     *
     * In 2.0.0, Session.delete(SimpleSessionKey) → AgentStateStore.delete(userId, sessionId).
     */
    fun clearSession(sessionId: String) {
        // Delete all state for this session (userId="" covers the default anonymous user)
        stateStore.delete("", sessionId)
        planNoteAdaptor.deletePlan(sessionId)
        // Destroy the sandbox container; destroy() persists the workspace snapshot first,
        // so workspace state survives and will be restored on next container creation.
        keepAliveSandboxManager?.destroy(sessionId)
    }

    /**
     * Loads session messages from the AgentStateStore.
     *
     * In agentscope 2.0.0, messages are stored in AgentState.context under key "agent_state".
     * Falls back to legacy "memory_messages" key for backward compatibility.
     */
    fun loadSessionMessages(sessionId: String): List<Msg> {
        // Try loading from agent_state (2.0.0 format) first
        val agentState = stateStore.get("", sessionId, "agent_state", AgentState::class.java)
        if (agentState.isPresent) {
            return agentState.get().context
        }
        // Fall back to legacy memory_messages key
        return stateStore.getList("", sessionId, "memory_messages", Msg::class.java)
    }

    /**
     * The child sessions of one root session that hold team member conversations.
     *
     * A member runs on `team-<root>-m<memberAgentId>` (TeamOrchestrator), and its full conversation is
     * persisted there under the same anonymous bucket as the lead's. Chat history has to replay them,
     * otherwise member bubbles vanish on reload and only the lead's `team_delegate` result survives.
     * Returns empty for an ordinary session, which is why the caller can afford to always ask.
     */
    fun memberSessionIds(rootSessionId: String): List<String> {
        val prefix = TeamSessions.prefix(rootSessionId)
        return runCatching { stateStore.listSessionIds("").filter { it.startsWith(prefix) } }
            .onFailure { log.warn("Failed to list member sessions for {}: {}", rootSessionId, it.message) }
            .getOrDefault(emptyList())
    }

    /** The child session one member of this root session runs on. */
    fun memberSessionId(
        rootSessionId: String,
        memberAgentId: Long,
    ): String = TeamSessions.childSessionId(rootSessionId, memberAgentId)

    fun loadSessionHistoryPlan(sessionId: String): List<PlanNote> = planNoteAdaptor.getPlanNotes(sessionId)

    /**
     * Loads the current plan note.
     *
     * In 2.0.0, PlanNotebook was replaced by enablePlanMode() (markdown-based).
     * This method reads from AgentStateStore using the legacy key for backward compatibility.
     * May return null if no plan state exists.
     */
    fun loadSessionCurrentPlanNote(sessionId: String): PlanNote? {
        // PlanNotebookState was removed in 2.0.0; plan mode now uses markdown files.
        // Return null for now; plan notes are managed via the plan mode workspace files.
        return null
    }

    companion object {
        /**
         * Appended when the model has internet search, so it answers real-time questions itself rather
         * than scraping pages for them.
         */
        private const val SEARCH_CAPABILITY_HINT =
            "[能力提示] 你已具备联网搜索能力，可以直接回答实时信息相关问题（如新闻、价格、天气等），" +
                "无需通过工具抓取网页或派遣子智能体。请优先利用自身联网知识直接作答。"

        /**
         * Output-file rule for an ordinary agent: the detector behind this contract is what makes the
         * files downloadable in the WebUI.
         */
        private const val OUTPUT_FILE_INSTRUCTION =
            "[文件输出规范] 当你生成结果文件（如报告、图表、数据文件等）时，必须将最终交付文件保存到 /workspace/output/ 目录下。" +
                "系统会自动检测该目录中的新文件并提供给用户下载。中间过程文件请勿放在此目录。"

        /**
         * Replaces [OUTPUT_FILE_INSTRUCTION] for a team member, whose workspace nothing scans: a file
         * that is not published as an artifact never leaves that member's sandbox (design 8.2).
         */
        private const val TEAM_MEMBER_FILE_INSTRUCTION =
            "[产物交接] 你的工作区不对用户开放。需要交付的文件（报告、图表、数据等）先写入工作区，" +
                "再调用 team_artifact_publish 登记为团队产物，并在回复中给出返回的 fileId 和文件路径。" +
                "接收方给出的 fileId 要先用 team_artifact_fetch 取到工作区才能读取。不要把文件内容整段粘贴进回复。"

        /**
         * Scans a ToolBox class for methods annotated with `@ToolMeta(dangerousInput=true)`.
         * Collects the framework tool names (@Tool.name or method name) into the target set.
         */
        private fun collectDangerousInputTools(clazz: Class<*>, target: MutableSet<String>) {
            clazz.methods.forEach { method ->
                val toolMeta = method.getAnnotation(ToolMeta::class.java)
                if (toolMeta?.dangerousInput == true) {
                    val toolAnnotation = method.getAnnotation(io.agentscope.core.tool.Tool::class.java)
                    val frameworkName = toolAnnotation?.name?.takeIf { it.isNotBlank() } ?: method.name
                    target.add(frameworkName)
                }
            }
        }

        /**
         * Factory method mirroring [com.agnetix.harnax.agent.AscopeAgentLauncher.initLauncher].
         */
        fun initLauncher(
            chatModelConfigAdaptor: ChatModelConfigAdaptor,
            mcpConfigAdaptor: McpConfigAdaptor,
            skillAdaptor: SkillAdaptor,
            tokenStatAdaptor: TokenStatAdaptor,
            sessionConfig: SessionConfig?,
            processLogAdaptor: ProcessLogAdaptor,
            toolCallLogAdaptor: ToolCallLogAdaptor,
            planNoteAdaptor: PlanNoteAdaptor,
            workspaceRoot: Path = Files.createTempDirectory("harness-workspace"),
            harnessConfig: HarnessConfig = HarnessConfig(),
            minioConfig: MinioConfig? = null,
            mcpConfigDecryptor: McpConfigDecryptor? = null,
            toolConfigAdaptor: ToolConfigAdaptor? = null,
            toolRegistry: ToolRegistry? = null,
            outputFileDetector: OutputFileDetector? = null,
            outputFileStore: OutputFileStore? = null,
            mcpTokenSourceFactory: McpAccessTokenSourceFactory? = null,
        ): HarnessAgentLauncher {
            minioConfig?.ensureBuckets()

            // Pre-create snapshotSpec so it can be shared between KeepAliveSandboxManager
            // and HarnessAgentWrapper. For LocalSnapshotSpec, ensure the directory exists.
            val snapshotSpec: SandboxSnapshotSpec? = if (harnessConfig.sandbox.enabled) {
                if (minioConfig != null) {
                    RemoteSnapshotSpec(
                        MinioSnapshotClient(
                            minioConfig.createMinioClient(),
                            minioConfig.snapshotBucket,
                            minioConfig.snapshotPrefix,
                        ),
                    )
                } else {
                    val snapshotsDir = workspaceRoot.resolve("snapshots")
                    Files.createDirectories(snapshotsDir)
                    LocalSnapshotSpec(snapshotsDir)
                }
            } else {
                null
            }

            val keepAliveManager = if (harnessConfig.sandbox.enabled && harnessConfig.sandbox.keepAlive) {
                KeepAliveSandboxManager(
                    image = harnessConfig.sandbox.image,
                    workspaceRoot = harnessConfig.sandbox.workspaceRoot,
                    maxIdleTimeMs = harnessConfig.sandbox.keepAliveMaxIdleTimeMs,
                    snapshotSpec = snapshotSpec,
                    network = harnessConfig.sandbox.network,
                )
            } else {
                null
            }
            // A CLI's payload lives in MinIO because that is where admin stored the package it was
            // registered from. With no MinIO there is nothing to build an image from, so the builder
            // stays absent and an agent that selected a CLI is refused with that reason — rather than
            // starting without the binaries its own shipped skill tells it to run.
            val cliImageBuilder = if (harnessConfig.sandbox.enabled && minioConfig != null) {
                CliImageBuilder(
                    baseImage = harnessConfig.sandbox.image,
                    packageStore = CliPackageStore(
                        minioClient = minioConfig.createMinioClient(),
                        bucket = minioConfig.cliPackageBucket,
                        cacheDir = Path.of(harnessConfig.sandbox.cliPackageCacheDir),
                    ),
                )
            } else {
                null
            }
            return HarnessAgentLauncher(
                chatModelConfigAdaptor = chatModelConfigAdaptor,
                mcpConfigAdaptor = mcpConfigAdaptor,
                stateStore = SessionLoader.load(sessionConfig),
                skillAdaptor = skillAdaptor,
                tokenStatAdaptor = tokenStatAdaptor,
                processLogAdaptor = processLogAdaptor,
                toolCallLogAdaptor = toolCallLogAdaptor,
                planNoteAdaptor = planNoteAdaptor,
                workspaceRoot = workspaceRoot,
                harnessConfig = harnessConfig,
                minioConfig = minioConfig,
                keepAliveSandboxManager = keepAliveManager,
                snapshotSpec = snapshotSpec,
                mcpConfigDecryptor = mcpConfigDecryptor,
                toolConfigAdaptor = toolConfigAdaptor,
                toolRegistry = toolRegistry,
                cliImageBuilder = cliImageBuilder,
                outputFileDetector = outputFileDetector,
                outputFileStore = outputFileStore,
                mcpTokenSourceFactory = mcpTokenSourceFactory,
            )
        }

        /** The one MCP type that is a local process rather than a connection; see [HarnessConfig.mcpStdioEnabled]. */
        private const val STDIO_TYPE = "stdio"

        /** A `runtimeEnv` value that is a platform slot rather than a literal; see [cliEnvironment]. */
        private val RUNTIME_ENV_SLOT = Regex("^\\$\\{([A-Za-z0-9._-]+)\\}$")
    }
}

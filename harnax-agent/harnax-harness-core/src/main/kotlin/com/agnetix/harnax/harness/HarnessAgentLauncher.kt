package com.agnetix.harnax.harness

import com.agnetix.harnax.agent.AgentSpec
import com.agnetix.harnax.agent.ChatSpec
import com.agnetix.harnax.agent.Permission
import com.agnetix.harnax.agent.adaptor.ChatModelConfigAdaptor
import com.agnetix.harnax.agent.adaptor.McpConfigAdaptor
import com.agnetix.harnax.agent.adaptor.PlanNote
import com.agnetix.harnax.agent.adaptor.PlanNoteAdaptor
import com.agnetix.harnax.agent.adaptor.ProcessLogAdaptor
import com.agnetix.harnax.agent.adaptor.SkillAdaptor
import com.agnetix.harnax.agent.adaptor.TokenStatAdaptor
import com.agnetix.harnax.agent.adaptor.ToolCallLogAdaptor
import com.agnetix.harnax.agent.adaptor.mcp.McpHelper
import com.agnetix.harnax.agent.adaptor.model.DashScopeChatModelConfig
import com.agnetix.harnax.agent.adaptor.model.ModelHelper
import com.agnetix.harnax.agent.adaptor.token.TokenStatBuilder
import com.agnetix.harnax.agent.provider.MIDDLEWARE_SET
import com.agnetix.harnax.agent.provider.TOOL_SET
import com.agnetix.harnax.agent.provider.middleware.ConfirmToolsMiddleware
import com.agnetix.harnax.agent.provider.middleware.ProcessLogMiddleware
import com.agnetix.harnax.agent.provider.tool.SessionMetaContext
import com.agnetix.harnax.agent.provider.tool.UserIdentifier
import com.agnetix.harnax.agent.session.SessionConfig
import com.agnetix.harnax.agent.session.SessionLoader
import com.agnetix.harnax.common.error.HarnaxErrorCode
import com.agnetix.harnax.harness.config.HarnessConfig
import com.agnetix.harnax.harness.config.MinioConfig
import com.agnetix.harnax.harness.minio.MinioBaseStore
import com.agnetix.harnax.harness.minio.MinioSnapshotClient
import com.agnetix.harnax.harness.sandbox.KeepAliveSandboxManager
import io.agentscope.core.message.Msg
import io.agentscope.core.state.AgentStateStore
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
) {

    private val log = LoggerFactory.getLogger(HarnessAgentLauncher::class.java)
    private val needConfirmedTools: MutableSet<String> = mutableSetOf()

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

    private fun createAgentBase(
        agentSpec: AgentSpec,
        sessionId: String,
        stateless: Boolean = false,
        chatSpec: ChatSpec = ChatSpec.builder().build(),
        userIdentifier: UserIdentifier,
    ): HarnessAgentWrapper {
        val agentBuilder = HarnessAgentBuilder()
            .name(agentSpec.name)
            .description(agentSpec.description)
            .maxIters(agentSpec.maxIterNum)
            .systemPrompt(agentSpec.systemPrompt)
            .workspace(workspaceRoot.resolve(agentSpec.name).resolve(sessionId))
            .stateStore(stateStore)

        // TODO [P0] Hardcoded API key and model name — must be replaced with chatModelConfigAdaptor
        //   lookup from DB/config. Current key is committed to Git and model is not configurable.
        val chatModelConfig = DashScopeChatModelConfig(
            "qwen3-vl-flash-2026-01-22",
            "sk-b6e5de9b14a947f5b9e9c7065c1fc0ec",
        )
        val chatModel = ModelHelper.createChatModel(
            chatModelConfig,
            chatSpec.enableThinking,
            true, // chatSpec.enableSearch,
        )
        agentBuilder.model(chatModel)

        // ----- MCP -----
        agentSpec.mcpServices.forEach {
            val mcpConfig = mcpConfigAdaptor.getConfig(it.mcpId)
            if (mcpConfig != null) {
                agentBuilder.addMcp(McpHelper.createMcpClient(mcpConfig, it.isAsync))
            } else if (!it.skipIfMissing) {
                log.error("Mcp config with id `${it.mcpId}` not found.")
                throw HarnaxErrorCode.AGENT_MCP_NOT_FOUND.format(it.mcpId)
            } else {
                log.warn("Mcp config with id `${it.mcpId}` not found.")
            }
        }

        // ----- Tools -----
        agentSpec.enableMetaTool?.let { agentBuilder.enableMetaTool(it) }
        TOOL_SET.forEach { toolBox ->
            toolBox.init(
                toolCallLogAdaptor,
                SessionMetaContext(agentSpec.id, sessionId),
                userIdentifier,
            )
            agentBuilder.addTool(toolBox)
            if (chatSpec.permission == Permission.NeedConfirmed) {
                needConfirmedTools.addAll(toolBox.needConfirmedTools())
            }
        }

        if (agentSpec.contextForTools.isNotEmpty()) {
            val ctxBuilder = io.agentscope.core.tool.ToolExecutionContext.builder()
            agentSpec.contextForTools.forEach { ctxBuilder.register(it) }
            agentBuilder.addToolContext(ctxBuilder.build())
        }

        // ----- Skills -----
        agentSpec.skills.forEach {
            val skill = skillAdaptor.getSkill(it.skillId)
            if (skill != null) {
                agentBuilder.addSkill(skill)
            } else if (!it.skipIfMissing) {
                log.error("Skill with id `${it.skillId}` not found.")
                throw HarnaxErrorCode.AGENT_SKILL_NOT_FOUND.format(it.skillId)
            } else {
                log.warn("Skill with id `${it.skillId}` not found.")
            }
        }

        // ----- Middleware (replaces Hooks in 2.0.0) -----
        MIDDLEWARE_SET.forEach { middleware ->
            if (middleware is ConfirmToolsMiddleware) {
                middleware.setDangerousTools(needConfirmedTools)
            }
            if (middleware is ProcessLogMiddleware) {
                middleware.initial(processLogAdaptor, agentSpec.id, agentSpec.name, sessionId)
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

        // ----- Docker Sandbox + MinIO Snapshot -----
        var keepAliveSnapshotSpec: SandboxSnapshotSpec? = null
        if (harnessConfig.sandbox.enabled) {
            val snapshotSpec = if (minioConfig != null) {
                RemoteSnapshotSpec(
                    MinioSnapshotClient(
                        minioConfig.createMinioClient(),
                        minioConfig.snapshotBucket,
                        minioConfig.snapshotPrefix,
                    ),
                )
            } else {
                LocalSnapshotSpec(workspaceRoot.resolve("snapshots"))
            }

            if (harnessConfig.sandbox.keepAlive) {
                keepAliveSnapshotSpec = snapshotSpec
            }

            // DockerFilesystemSpec moved to io.agentscope.harness.agent.sandbox.impl.docker in 2.0.0
            // .sandboxStateStore() is removed — sandbox state is now managed via DistributedStore
            val dockerSpec = DockerFilesystemSpec()
                .image(harnessConfig.sandbox.image)
                .workspaceRoot(harnessConfig.sandbox.workspaceRoot)
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
        } else if (minioConfig != null) {
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
            dangerousTools = needConfirmedTools,
            tokenStatBuilder = TokenStatBuilder()
                .agentId(agentSpec.id)
                .sessionId(sessionId)
                .modelId(agentSpec.chatModelId),
            tokenStatAdaptor = tokenStatAdaptor,
            sessionId = sessionId,
            keepAliveSandboxManager = keepAliveSandboxManager,
            keepAliveSnapshotSpec = keepAliveSnapshotSpec,
            sandboxImage = harnessConfig.sandbox.image,
            sandboxWorkspaceRoot = harnessConfig.sandbox.workspaceRoot,
        )
    }

    /**
     * Clears all persisted state for the given session: agent state, plan notes,
     * and optionally the MinIO snapshot.
     *
     * In 2.0.0, Session.delete(SimpleSessionKey) → AgentStateStore.delete(userId, sessionId).
     */
    fun clearSession(sessionId: String) {
        // Delete all state for this session (userId="" covers the default anonymous user)
        stateStore.delete("", sessionId)
        planNoteAdaptor.deletePlan(sessionId)
        keepAliveSandboxManager?.destroy(sessionId)
        // Also try to delete the MinIO snapshot if available
        if (minioConfig != null) {
            try {
                val snapshotClient = MinioSnapshotClient(
                    minioConfig.createMinioClient(),
                    minioConfig.snapshotBucket,
                    minioConfig.snapshotPrefix,
                )
                snapshotClient.delete(sessionId)
            } catch (e: Exception) {
                log.warn("Failed to delete MinIO snapshot for session {}: {}", sessionId, e.message)
            }
        }
    }

    /**
     * Loads session messages from the AgentStateStore.
     *
     * In 2.0.0, Session.getList(SimpleSessionKey, key, type) →
     * AgentStateStore.getList(userId, sessionId, key, type).
     */
    fun loadSessionMessages(sessionId: String): List<Msg> = stateStore.getList(
        "",
        sessionId,
        "memory_messages",
        Msg::class.java,
    )

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
        ): HarnessAgentLauncher {
            minioConfig?.ensureBuckets()
            val keepAliveManager = if (harnessConfig.sandbox.enabled && harnessConfig.sandbox.keepAlive) {
                KeepAliveSandboxManager(
                    image = harnessConfig.sandbox.image,
                    workspaceRoot = harnessConfig.sandbox.workspaceRoot,
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
            )
        }
    }
}

package com.agnetix.harnax.harness

import com.agnetix.harnax.agent.AgentSpec
import com.agnetix.harnax.agent.ChatSpec
import com.agnetix.harnax.agent.CustomerPlanNoteStorage
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
import com.agnetix.harnax.agent.provider.HOOK_SET
import com.agnetix.harnax.agent.provider.TOOL_SET
import com.agnetix.harnax.agent.provider.hook.ConfirmToolsHook
import com.agnetix.harnax.agent.provider.hook.ProcessLogHook
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
import com.agnetix.harnax.harness.sandbox.MysqlCompatibleSandboxStateStore
import io.agentscope.core.message.Msg
import io.agentscope.core.plan.PlanNotebook
import io.agentscope.core.session.Session
import io.agentscope.core.state.PlanNotebookState
import io.agentscope.core.state.SimpleSessionKey
import io.agentscope.core.tool.ToolExecutionContext
import io.agentscope.harness.agent.IsolationScope
import io.agentscope.harness.agent.filesystem.spec.DockerFilesystemSpec
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec
import io.agentscope.harness.agent.sandbox.SandboxDistributedOptions
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
 * Differences from [com.agnetix.harnax.agent.AscopeAgentLauncher]:
 * - Uses [HarnessAgentBuilder] (→ [io.agentscope.harness.agent.HarnessAgent]) instead of
 *   [com.agnetix.harnax.agent.AscopeAgentBuilder] (→ [io.agentscope.core.ReActAgent]).
 * - Session persistence is handled automatically by the built-in `SessionPersistenceHook`; no
 *   manual [io.agentscope.core.session.SessionManager] is needed.
 * - Supports Docker sandbox with MinIO-backed snapshot persistence.
 * - Supports MinIO-backed distributed filesystem for cross-node file sharing.
 *
 * @param chatModelConfigAdaptor adaptor for chat model configuration
 * @param mcpConfigAdaptor adaptor for MCP service configuration
 * @param session distributed [Session] backend (e.g. MysqlSession)
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
    val session: Session,
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
            .reminder(agentSpec.reminder)
            .workspace(workspaceRoot.resolve(agentSpec.name).resolve(sessionId))
            .session(session)

        // ----- Chat model -----
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
            val ctxBuilder = ToolExecutionContext.builder()
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

        // ----- Hooks -----
        HOOK_SET.forEach { hook ->
            if (hook is ConfirmToolsHook) {
                hook.setDangerousTools(needConfirmedTools)
            }
            if (hook is ProcessLogHook) {
                hook.initial(processLogAdaptor, agentSpec.id, agentSpec.name, sessionId)
            }
            agentBuilder.addHook(hook)
        }

        // ----- Memory -----
        // HarnessAgent always uses InMemoryMemory internally; no explicit memory configuration needed.
        if (stateless) {
            log.info("Stateless mode: HarnessAgent will use fresh InMemoryMemory for session={}", sessionId)
        }

        // ----- Plan -----
        if (chatSpec.enablePlan) {
            val planNotebookBuilder = PlanNotebook.builder()
                .storage(CustomerPlanNoteStorage(sessionId, planNoteAdaptor))
            agentSpec.planSpec.maxSubTask?.let { planNotebookBuilder.maxSubtasks(it) }
            agentSpec.planSpec.needUserConfirmed?.let { planNotebookBuilder.needUserConfirm(it) }
            agentBuilder.enablePlan(true)
            agentBuilder.addPlanNotebook(planNotebookBuilder.build())
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

            val dockerSpec = DockerFilesystemSpec()
                .image(harnessConfig.sandbox.image)
                .workspaceRoot(harnessConfig.sandbox.workspaceRoot)
                .isolationScope(harnessConfig.sandbox.isolationScope)
                .snapshotSpec(snapshotSpec)
                .sandboxStateStore(
                    MysqlCompatibleSandboxStateStore(session, agentSpec.name),
                )

            agentBuilder.filesystem(dockerSpec)
            agentBuilder.sandboxDistributed(
                SandboxDistributedOptions.builder()
                    .session(session)
                    .snapshotSpec(snapshotSpec)
                    .requireDistributed(false)
                    .build(),
            )
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

        // ----- Disable built-in features that conflict with Harnax custom hooks -----
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
     * Clears all persisted state for the given session: agent state in MySQL, plan notes,
     * and optionally the MinIO snapshot.
     */
    fun clearSession(sessionId: String) {
        session.delete(SimpleSessionKey.of(sessionId))
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

    fun loadSessionMessages(sessionId: String): List<Msg> = session.getList(
        SimpleSessionKey.of(sessionId),
        "memory_messages",
        Msg::class.java,
    )

    fun loadSessionHistoryPlan(sessionId: String): List<PlanNote> = planNoteAdaptor.getPlanNotes(sessionId)

    fun loadSessionCurrentPlanNote(sessionId: String): PlanNote? {
        val planNote = session.get(
            SimpleSessionKey.of(sessionId),
            "planNotebook_state",
            PlanNotebookState::class.java,
        )
        if (planNote.isPresent) {
            return CustomerPlanNoteStorage.convertToPlanNote(sessionId, planNote.get().currentPlan)
        }
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
                session = SessionLoader.load(sessionConfig),
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

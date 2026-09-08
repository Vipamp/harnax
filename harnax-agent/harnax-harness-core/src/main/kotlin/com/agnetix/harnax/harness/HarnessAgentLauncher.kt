package com.agnetix.harnax.harness

import com.agnetix.harnax.agent.AgentSpec
import com.agnetix.harnax.agent.ChatSpec
import com.agnetix.harnax.agent.adaptor.ChatModelConfigAdaptor
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
import com.agnetix.harnax.common.error.HarnaxErrorCode
import com.agnetix.harnax.common.mcp.McpConfigDecryptor
import com.agnetix.harnax.harness.config.HarnessConfig
import com.agnetix.harnax.harness.config.MinioConfig
import com.agnetix.harnax.harness.minio.MinioBaseStore
import com.agnetix.harnax.harness.minio.MinioSnapshotClient
import com.agnetix.harnax.harness.output.OutputFileDetector
import com.agnetix.harnax.harness.output.OutputFileStore
import com.agnetix.harnax.harness.sandbox.CliImageBuilder
import com.agnetix.harnax.harness.sandbox.KeepAliveSandboxManager
import com.agnetix.harnax.harness.sandbox.plugin.HarnaxCliPluginInitializer
import com.agnetix.harnax.harness.sandbox.plugin.SandboxPluginInitializer
import com.agnetix.harnax.tools.sdk.HttpProxyToolBox
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
import io.agentscope.core.tool.AgentTool
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
    val snapshotSpec: SandboxSnapshotSpec? = null,
    val mcpConfigDecryptor: McpConfigDecryptor? = null,
    val toolConfigAdaptor: ToolConfigAdaptor? = null,
    val toolRegistry: ToolRegistry? = null,
    val cliImageBuilder: CliImageBuilder? = null,
    val outputFileDetector: OutputFileDetector? = null,
    val outputFileStore: OutputFileStore? = null,
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

    private fun createAgentBase(
        agentSpec: AgentSpec,
        sessionId: String,
        stateless: Boolean = false,
        chatSpec: ChatSpec = ChatSpec.builder().build(),
        userIdentifier: UserIdentifier,
    ): HarnessAgentWrapper {
        val needConfirmedTools = mutableSetOf<String>()
        val dangerousInputTools = mutableSetOf<String>()

        // When internet search is enabled, append a capability hint so the model knows
        // it can answer real-time questions directly without spawning subagents or
        // executing shell commands to fetch web pages.
        val effectivePrompt = if (chatSpec.enableSearch) {
            agentSpec.systemPrompt + "\n\n" +
                "[能力提示] 你已具备联网搜索能力，可以直接回答实时信息相关问题（如新闻、价格、天气等），" +
                "无需通过工具抓取网页或派遣子智能体。请优先利用自身联网知识直接作答。\n\n" +
                OUTPUT_FILE_INSTRUCTION
        } else {
            agentSpec.systemPrompt + "\n\n" + OUTPUT_FILE_INSTRUCTION
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
        agentSpec.mcpServices.forEach {
            val mcpConfig = mcpConfigAdaptor.getConfig(it.mcpId)
            if (mcpConfig != null) {
                agentBuilder.addMcp(McpHelper.createMcpClient(mcpConfig, it.isAsync, mcpConfigDecryptor?.let { d -> d::decryptToMap }, mcpConfigDecryptor?.let { d -> d::decryptToolEnvParamsToMap }))
            } else if (!it.skipIfMissing) {
                log.error("Mcp config with id `${it.mcpId}` not found.")
                throw HarnaxErrorCode.AGENT_MCP_NOT_FOUND.format(it.mcpId)
            } else {
                log.warn("Mcp config with id `${it.mcpId}` not found.")
            }
        }

        // ----- Tools -----
        agentSpec.enableMetaTool?.let { agentBuilder.enableMetaTool(it) }

        if (agentSpec.toolSpecs.isNotEmpty() && toolConfigAdaptor != null) {
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

                    val resolvedTool: Any? = when (toolConfig.type.uppercase()) {
                        "BUILTIN", "CUSTOM" -> {
                            val beanName = toolConfig.beanName ?: ""
                            if (beanName.isNotEmpty() && beanName !in addedToolBoxBeans) {
                                val toolBox = toolRegistry?.createToolBoxInstance(beanName)
                                if (toolBox != null) {
                                    toolBox.init(
                                        toolCallLogAdaptor,
                                        SessionMetaContext(agentSpec.id, sessionId),
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
                        }
                        "HTTP" -> {
                            HttpProxyToolBox(
                                toolName = toolConfig.name,
                                toolDescription = toolConfig.description,
                                httpUrl = toolConfig.httpUrl ?: "",
                                httpMethod = toolConfig.httpMethod ?: "POST",
                                httpHeaders = mcpConfigDecryptor?.let { d ->
                                    try {
                                        d.decryptToMap(toolConfig.httpHeaders)
                                    } catch (e: Exception) {
                                        log.warn("Failed to decrypt HTTP headers for tool '{}': {}", toolConfig.name, e.message)
                                        emptyMap()
                                    }
                                } ?: emptyMap(),
                                inputSchemaJson = toolConfig.inputSchema ?: "{}",
                                timeoutSeconds = toolConfig.timeoutSeconds,
                            )
                        }
                        else -> null
                    }

                    if (resolvedTool != null) {
                        if (resolvedTool is AgentTool) {
                            agentBuilder.registerAgentTool(resolvedTool)
                        }

                        // Per-method needConfirm from DB record, or from toolSpec override
                        val shouldConfirm = toolConfig.needConfirm == 1 || toolSpec.needConfirm
                        if (shouldConfirm) {
                            // Use the framework tool name (@Tool.name) so PermissionEngine can match
                            needConfirmedTools.add(toolConfig.name)
                        }

                        // Scan ToolBox methods for @ToolMeta(dangerousInput=true) — deduplicated by class
                        val beanName = toolConfig.beanName ?: ""
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
        } else if (agentSpec.toolSpecs.isNotEmpty()) {
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
        // Dangerous-tool interception is fully handled by the built-in PermissionEngine
        // (ASK/ALLOW/DENY rules configured below via PermissionContextState).
        // No custom ConfirmToolsMiddleware needed.
        MIDDLEWARE_SET.forEach { middleware ->
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

        // ----- CLI sandbox image (per-agent, built from CLI install scripts) -----
        val cliEnv: Map<String, String> = agentSpec.cliSpecs
            .flatMap { it.envBindings.entries }
            .associate { it.key to it.value }
        val resolvedSandboxImage: String = if (agentSpec.cliSpecs.isNotEmpty() && cliImageBuilder != null) {
            val image = cliImageBuilder.resolveImage(agentSpec.cliSpecs)
            log.info(
                "Resolved CLI sandbox image '{}' for agent '{}' (CLIs: {})",
                image,
                agentSpec.name,
                agentSpec.cliSpecs.joinToString(",") { it.name },
            )
            image
        } else {
            harnessConfig.sandbox.image
        }

        // ----- Docker Sandbox + Snapshot (snapshotSpec pre-created in initLauncher) -----
        if (harnessConfig.sandbox.enabled && snapshotSpec != null) {
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

        // ----- Permission Context (ASK rules for dangerous tools + ALLOW rules for framework tools) -----
        // Framework tools (plan mode, todo, subagent) must always be allowed — without explicit ALLOW
        // rules, PermissionEngine defaults to ASK in DEFAULT mode, which blocks these internal tools.
        val frameworkAllowTools = setOf(
            "plan_enter", "plan_write", "plan_exit",
            "todo_write",
            "agent_spawn", "agent_send", "agent_list",
            "task_output", "task_list",
        )
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
            dangerousTools = needConfirmedTools + dangerousInputTools,
            tokenStatBuilder = TokenStatBuilder()
                .agentId(agentSpec.id)
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
            pluginInitializers = if (harnessConfig.sandbox.cliPluginsEnabled) listOf<SandboxPluginInitializer>(HarnaxCliPluginInitializer()) else emptyList(),
            pluginAdminUrl = harnessConfig.sandbox.pluginAdminUrl,
            pluginInternalSecret = harnessConfig.sandbox.pluginInternalSecret,
            outputFileDetector = outputFileDetector,
            outputFileStore = outputFileStore,
        )
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
         * Prompt instruction: instruct the agent to save generated output files to /workspace/output/.
         */
        private const val OUTPUT_FILE_INSTRUCTION =
            "[文件输出规范] 当你生成结果文件（如报告、图表、数据文件等）时，必须将最终交付文件保存到 /workspace/output/ 目录下。" +
                "系统会自动检测该目录中的新文件并提供给用户下载。中间过程文件请勿放在此目录。"

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
                val effectiveImage = if (harnessConfig.sandbox.cliPluginsEnabled) {
                    harnessConfig.sandbox.pluginImage
                } else {
                    harnessConfig.sandbox.image
                }
                KeepAliveSandboxManager(
                    image = effectiveImage,
                    workspaceRoot = harnessConfig.sandbox.workspaceRoot,
                    snapshotSpec = snapshotSpec,
                    network = harnessConfig.sandbox.network,
                )
            } else {
                null
            }
            val cliImageBuilder = if (harnessConfig.sandbox.enabled) {
                CliImageBuilder(baseImage = harnessConfig.sandbox.image)
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
            )
        }
    }
}

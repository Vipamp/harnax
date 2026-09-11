package com.agnetix.harnax.harness

import com.agnetix.harnax.agent.adaptor.TokenStatAdaptor
import com.agnetix.harnax.agent.adaptor.token.TokenStatBuilder
import com.agnetix.harnax.agent.chat.MsgExtractHelper
import com.agnetix.harnax.agent.protocol.ChatEvent
import com.agnetix.harnax.agent.protocol.ChatEventConverter
import com.agnetix.harnax.agent.protocol.ChatResponse
import com.agnetix.harnax.agent.protocol.EndEventChatEvent
import com.agnetix.harnax.agent.protocol.ErrorChatEvent
import com.agnetix.harnax.agent.protocol.FileAttachment
import com.agnetix.harnax.common.error.HarnaxErrorCode
import com.agnetix.harnax.common.error.HarnaxException
import com.agnetix.harnax.harness.output.OutputFileDetector
import com.agnetix.harnax.harness.output.OutputFileStore
import com.agnetix.harnax.harness.sandbox.KeepAliveSandboxManager
import com.agnetix.harnax.harness.sandbox.plugin.SandboxPluginInitializer
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.event.AgentEventType
import io.agentscope.core.event.RequireUserConfirmEvent
import io.agentscope.core.event.ToolCallDeltaEvent
import io.agentscope.core.event.ToolCallEndEvent
import io.agentscope.core.event.ToolResultEndEvent
import io.agentscope.core.event.ToolResultTextDeltaEvent
import io.agentscope.core.message.Base64Source
import io.agentscope.core.message.ContentBlock
import io.agentscope.core.message.GenerateReason
import io.agentscope.core.message.ImageBlock
import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import io.agentscope.core.message.TextBlock
import io.agentscope.core.message.ThinkingBlock
import io.agentscope.core.message.ToolCallState
import io.agentscope.core.message.ToolResultBlock
import io.agentscope.core.message.ToolUseBlock
import io.agentscope.core.permission.PermissionContextState
import io.agentscope.core.permission.PermissionMode
import io.agentscope.core.state.AgentState
import io.agentscope.core.state.Task
import io.agentscope.core.tool.mcp.McpClientWrapper
import io.agentscope.harness.agent.HarnessAgent
import io.agentscope.harness.agent.sandbox.SandboxContext
import io.agentscope.harness.agent.sandbox.WorkspaceSpec
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClient
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec
import org.slf4j.LoggerFactory
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wraps a [HarnessAgent] and exposes a streaming call API.
 *
 * Key changes in agentscope 2.0.0:
 * - `stream()` → `streamEvents()` (returns `Flux<AgentEvent>` instead of `Flux<Event>`)
 * - `call()` now requires `RuntimeContext` (the old overload without ctx is deprecated)
 * - Event → AgentEvent with fine-grained subtypes
 */
class HarnessAgentWrapper(
    val harnessAgent: HarnessAgent,
    /**
     * The MCP clients created for this agent. [closeMcpClients] is the only thing that releases them:
     * `HarnessAgent.close()` does not touch the toolkit, and a stdio client is an OS process.
     */
    val mcpClients: List<McpClientWrapper> = emptyList(),
    val dangerousTools: Set<String>,
    val tokenStatBuilder: TokenStatBuilder,
    val tokenStatAdaptor: TokenStatAdaptor,
    val sessionId: String,
    val userId: String? = null,
    val keepAliveSandboxManager: KeepAliveSandboxManager? = null,
    val keepAliveSnapshotSpec: SandboxSnapshotSpec? = null,
    val sandboxImage: String = "python:3.11-slim",
    val sandboxEnv: Map<String, String> = emptyMap(),
    val sandboxWorkspaceRoot: String = "/workspace",
    val sandboxNetwork: String? = null,
    val permissionMode: String = "DEFAULT",
    /**
     * The [PermissionContextState] configured at agent creation time (ALLOW/ASK rules from
     * tool `needConfirm` settings and framework tool allow-list). Stored here so we can
     * merge it into the loaded agent state before each call — without this, persisted states
     * with trivial (empty-rule) permission contexts bypass the full permission engine,
     * causing read-only tools like `glob_files` to skip the confirmation prompt.
     */
    val configuredPermissionContext: PermissionContextState? = null,
    val pluginInitializers: List<SandboxPluginInitializer> = emptyList(),
    val pluginAdminUrl: String = "",
    val pluginInternalSecret: String = "",
    val outputFileDetector: OutputFileDetector? = null,
    val outputFileStore: OutputFileStore? = null,
) {

    private val log = LoggerFactory.getLogger(HarnessAgentWrapper::class.java)

    /**
     * Holds the active [Disposable] for the blocking [call] so that [interrupt] can cancel it.
     * Only one call per wrapper instance should be active at any time.
     */
    @Volatile
    private var activeCallDisposable: Disposable? = null

    /** Set once by [release]; see the note there about reaching this from two paths. */
    private val released = AtomicBoolean(false)

    /** Tracks whether CLI plugins have been initialized for this wrapper's sandbox. */
    @Volatile
    private var pluginsInitialized = false

    /**
     * Cached pending tool calls from the last [RequireUserConfirmEvent].
     * Used by confirm() to construct [ConfirmResult] metadata for agentscope.
     */
    @Volatile
    private var pendingToolCalls: List<ToolUseBlock> = emptyList()

    /**
     * Buffer for accumulating TOOL_CALL_DELTA fragments.
     * Key: toolCallId, Value: accumulated JSON string.
     */
    private val toolCallArgsBuffer = mutableMapOf<String, String>()

    /**
     * Buffer for accumulating TOOL_RESULT_TEXT_DELTA fragments.
     * Key: toolCallId, Value: accumulated result text.
     */
    private val toolResultBuffer = mutableMapOf<String, String>()

    /**
     * Returns the pending tool calls that require user confirmation.
     * Populated when a [RequireUserConfirmEvent] is intercepted during streaming.
     */
    fun getPendingToolCalls(): List<ToolUseBlock> = pendingToolCalls

    /**
     * Ensures the configured permission rules (ALLOW/ASK) are present on the loaded agent state.
     *
     * When `ReActAgent` loads a persisted `AgentState` from the stateStore, it uses the
     * persisted `permissionContext` as-is. If the state was saved before permission rules were
     * configured, the context is "trivial" (empty rules), causing the framework to skip the
     * full permission engine and auto-allow tools like `glob_files` via the legacy lightweight path.
     *
     * This method replaces a trivial context with the configured one (preserving the current mode),
     * and re-builds the cached `PermissionEngine` via `setPermissionMode`.
     */
    private fun ensurePermissionRulesMerged() {
        val configured = configuredPermissionContext ?: return
        if (configured.isTrivial) return

        try {
            val delegate = harnessAgent.delegate ?: return
            val state = delegate.getAgentState(userId, sessionId) ?: return
            val current = state.permissionContext

            // Only merge if the loaded state has a trivial (empty-rule) context
            if (current.isTrivial) {
                val merged = configured.withMode(PermissionMode.fromString(permissionMode))
                state.setPermissionContext(merged)
                log.info(
                    "[harness] Merged configured permission rules into trivial state for session={}: " +
                        "allowRules={}, askRules={}, mode={}",
                    sessionId,
                    merged.allowRules.keys,
                    merged.askRules.keys,
                    merged.mode,
                )
            }
        } catch (e: Exception) {
            log.debug("[harness] ensurePermissionRulesMerged failed for session={}: {}", sessionId, e.message)
        }
    }

    /**
     * Reads the current plan markdown file (plans/PLAN.md) from the workspace.
     */
    fun readPlanContent(): String? = readWorkspaceFile("plans/PLAN.md")

    /**
     * Reads the todo/task-list file (plans/todo.md) from the workspace.
     */
    fun readTodoContent(): String? = readWorkspaceFile("plans/todo.md")

    /**
     * Reads structured Task list from the live in-memory AgentState.
     * This is the preferred way to get subtasks (vs parsing markdown or reading from StateStore).
     *
     * Uses [HarnessAgent.getDelegate] → [ReActAgent.getAgentState] to access the same
     * in-memory AgentState that the `todo_write` tool mutates during execution.
     * This ensures real-time task states (PENDING/IN_PROGRESS/COMPLETED) are always current,
     * unlike reading from AgentStateStore which only gets flushed after each ReAct iteration.
     *
     * Returns null if no tasks are found or an error occurs.
     */
    fun readTasksFromState(): List<Task>? {
        return try {
            val state = getLiveAgentState() ?: return null
            val tasks = state.tasksContext?.tasks ?: return null
            if (tasks.isEmpty()) null else tasks
        } catch (e: Exception) {
            log.debug("[harness] readTasksFromState failed for session={}: {}", sessionId, e.message)
            null
        }
    }

    /**
     * Reads the full plan data from the live in-memory AgentState in a single call.
     * Combines planModeContext (is plan active, plan file path) and tasksContext (structured tasks).
     *
     * This is the preferred data source for the frontend plan panel — it reads from the same
     * in-memory [AgentState] that the `todo_write` tool mutates via `tasksMutable()`,
     * so task execution states (PENDING/IN_PROGRESS/COMPLETED) are always real-time.
     *
     * Previously this read from AgentStateStore, which only gets flushed after each ReAct
     * iteration — causing stale task states during active agent execution.
     *
     * @return [AgentStatePlanData] if AgentState is available, null otherwise.
     */
    fun readFullPlanFromState(): AgentStatePlanData? {
        return try {
            val state = getLiveAgentState() ?: return null

            val planCtx = state.planModeContext
            val tasks = state.tasksContext?.tasks

            log.debug(
                "[harness] readFullPlanFromState for session={}: planActive={}, currentPlanFile={}, tasks={}",
                sessionId,
                planCtx?.isPlanActive,
                planCtx?.currentPlanFile,
                tasks?.size ?: 0,
            )

            AgentStatePlanData(
                planActive = planCtx?.isPlanActive ?: false,
                currentPlanFile = planCtx?.currentPlanFile,
                tasks = if (tasks.isNullOrEmpty()) null else tasks,
            )
        } catch (e: Exception) {
            log.debug("[harness] readFullPlanFromState failed for session={}: {}", sessionId, e.message)
            null
        }
    }

    /**
     * Gets the live in-memory [AgentState] from the delegate [ReActAgent].
     *
     * This is the same object that `todo_write` and other state-injected tools mutate
     * during agent execution. Reading from here (instead of AgentStateStore) ensures
     * we always see the latest task states, even mid-iteration before the state is flushed.
     *
     * Falls back to AgentStateStore if the delegate is unavailable.
     */
    private fun getLiveAgentState(): AgentState? {
        // Primary: read from the live in-memory state via delegate ReActAgent
        try {
            val delegate = harnessAgent.delegate
            if (delegate != null) {
                val state = delegate.getAgentState(userId, sessionId)
                if (state != null) return state
            }
        } catch (e: Exception) {
            log.debug("[harness] getLiveAgentState via delegate failed for session={}: {}", sessionId, e.message)
        }

        // Fallback: read from AgentStateStore (e.g. if delegate not yet initialized)
        return try {
            val stateStore = harnessAgent.stateStore ?: return null
            stateStore.get(userId, sessionId, "agent_state", AgentState::class.java)
                .orElse(null)
        } catch (e: Exception) {
            log.debug("[harness] getLiveAgentState via stateStore failed for session={}: {}", sessionId, e.message)
            null
        }
    }

    /**
     * Reads a workspace file using two-layer strategy:
     * 1. workspaceManager (works during active agent call context)
     * 2. Direct sandbox Docker exec (works outside call context, e.g. frontend polling)
     */
    private fun readWorkspaceFile(relativePath: String): String? {
        // Try workspace manager first (works during active calls)
        try {
            val wsManager = harnessAgent.workspaceManager
            if (wsManager != null) {
                val rc = RuntimeContext.builder().sessionId(sessionId).userId(userId ?: "").build()
                val content = wsManager.readManagedWorkspaceFileUtf8(rc, relativePath)
                if (content.isNotBlank()) {
                    return content
                }
            }
        } catch (e: Exception) {
            log.debug("[harness] readWorkspaceFile via workspaceManager failed for session={}, path={}: {}", sessionId, relativePath, e.message)
        }

        // Fall back to direct sandbox exec (works outside call context)
        return readFileFromSandbox(relativePath)
    }

    /**
     * Reads a file directly from sandbox container via Docker exec.
     */
    private fun readFileFromSandbox(relativePath: String): String? {
        val sandboxManager = keepAliveSandboxManager ?: return null
        return try {
            val sandbox = sandboxManager.getSandbox(sessionId) ?: run {
                log.debug("[harness] readFileFromSandbox: no sandbox for session={}", sessionId)
                return null
            }
            val result = sandbox.exec(null, "cat $sandboxWorkspaceRoot/$relativePath 2>/dev/null", 5)
            val content = result.stdout()
            if (content.isBlank()) null else content
        } catch (e: Exception) {
            log.debug("[harness] readFileFromSandbox: failed for session={}, path={}: {}", sessionId, relativePath, e.message)
            null
        }
    }

    /**
     * Set to `true` by [interrupt] so that [call] can detect that an exception was caused by
     * an explicit interrupt (rather than a genuine failure) and return a clean empty response.
     */
    @Volatile
    private var interrupted: Boolean = false

    /** The thread currently blocked in [call], used by [interrupt] to break out of `Mono.block()`. */
    @Volatile
    private var blockingThread: Thread? = null

    fun callStream(
        prompt: String,
        imageUrls: List<String> = listOf(),
    ): Flux<ChatEvent> = Flux.defer {
        val list: MutableList<ContentBlock> = mutableListOf()
        list.add(TextBlock.builder().text(prompt).build())
        imageUrls.forEach { list.add(imageBlock(it)) }
        val msg = Msg.builder().name("user").role(MsgRole.USER).content(list).build()
        callStreamInternal(msg)
    }.subscribeOn(Schedulers.boundedElastic())

    fun callStream(
        msg: Msg? = null,
    ): Flux<ChatEvent> = Flux.defer {
        callStreamInternal(*if (msg != null) arrayOf(msg) else emptyArray())
    }.subscribeOn(Schedulers.boundedElastic())

    /**
     * Non-streaming call — returns a [ChatResponse] directly.
     */
    fun call(
        prompt: String,
        imageUrls: List<String> = listOf(),
    ): ChatResponse {
        val list: MutableList<ContentBlock> = mutableListOf()
        list.add(TextBlock.builder().text(prompt).build())
        imageUrls.forEach { list.add(imageBlock(it)) }
        val userMsg = Msg.builder().name("user").role(MsgRole.USER).content(list).build()
        return call(listOf(userMsg))
    }

    fun call(msgs: List<Msg>): ChatResponse {
        val callStartTime = System.currentTimeMillis()
        val ctxResult = buildRuntimeContext()
        // Ensure configured permission rules are merged into loaded state before setting mode
        ensurePermissionRulesMerged()
        // Channel sessions (chn-*) cannot support interactive tool approval (no /approve /deny UI),
        // so force BYPASS to prevent PERMISSION_ASKING from blocking tool execution.
        val effectiveMode = if (sessionId.startsWith("chn-")) "BYPASS" else permissionMode
        harnessAgent.setPermissionMode(ctxResult.runtimeContext, PermissionMode.fromString(effectiveMode))
        try {
            val mono = harnessAgent.call(msgs, ctxResult.runtimeContext)
                .timeout(Duration.ofMinutes(5))
            // Use a single subscribe() to avoid double subscription on the cold Mono.
            // The previous subscribe()+block() pattern created two independent subscriptions,
            // causing SandboxLifecycleMiddleware to acquire/release the sandbox twice and
            // leaving the second subscription with a null sandbox reference.
            var result: Msg? = null
            var error: Throwable? = null
            val latch = CountDownLatch(1)
            blockingThread = Thread.currentThread()
            activeCallDisposable =
                mono.subscribe(
                    { msg ->
                        result = msg
                        latch.countDown()
                    },
                    { err ->
                        error = err
                        latch.countDown()
                    },
                )
            try {
                latch.await()
                // If we got a result, return it even if a late error also arrived.
                // This handles a race in agentscope where SandboxLifecycleMiddleware.releaseForCall()
                // sets filesystemProxy.sandbox = null AFTER the Mono has already emitted the response.
                // A subsequent post-processing step on a different thread may then try to access
                // SandboxBackedFilesystem and trigger a spurious SandboxConfigurationException.
                // Since the response was already successfully produced, we should return it.
                if (result != null) {
                    if (error != null) {
                        log.warn("[harness] Late error after successful call for session={}, suppressed: {}", sessionId, error!!.message)
                    }
                    var content = resolveReplyContent(result!!)
                    val thinking = MsgExtractHelper.extractThinking(result!!)

                    // Detect and persist output files generated during this call
                    val attachments = detectAndPersistOutputFiles(callStartTime)
                    if (attachments.isNotEmpty() && !sessionId.startsWith("chn-")) {
                        // Only append download links for WebUI sessions.
                        // Channel sessions receive files via sendFile() directly.
                        content = appendDownloadLinks(content, attachments)
                    }

                    return ChatResponse(
                        sessionId = sessionId,
                        content = content,
                        thinking = thinking?.ifEmpty { null },
                        attachments = attachments,
                    )
                }
                if (error != null) {
                    val cause = error!!
                    // harnessAgent.interrupt() invalidates the sandbox context, which causes
                    // the agent's internal tool calls to throw various exceptions (e.g.
                    // SandboxConfigurationException, InterruptedException, CancellationException).
                    // Check the interrupted flag to distinguish an explicit stop from a real failure.
                    if (interrupted) {
                        log.info("[harness] Call interrupted for session={}, suppressed error: {}", sessionId, cause.message)
                        return ChatResponse(sessionId = sessionId, content = "", thinking = null)
                    }
                    // HITL pause: the agent stopped waiting for tool confirmation. Text-only
                    // channels can't render a confirm dialog, so reply with an /approve
                    // /deny prompt instead of surfacing the raw framework error.
                    val pausePrompt = buildConfirmPromptIfPaused(cause)
                    if (pausePrompt != null) {
                        log.info("[harness] Call paused for tool confirmation for session={}", sessionId)
                        return ChatResponse(sessionId = sessionId, content = pausePrompt, thinking = null)
                    }
                    throw cause
                }
                // Neither result nor error — should not happen, but handle gracefully
                log.warn("[harness] Call completed without result or error for session={}", sessionId)
                return ChatResponse(sessionId = sessionId, content = "", thinking = null)
            } catch (e: InterruptedException) {
                if (interrupted) {
                    log.info("[harness] Call interrupted for session={}, suppressed InterruptedException", sessionId)
                    return ChatResponse(sessionId = sessionId, content = "", thinking = null)
                }
                throw e
            } finally {
                activeCallDisposable = null
                blockingThread = null
            }
        } finally {
            persistKeepAliveSnapshot(ctxResult)
        }
    }

    /**
     * Resolve the user-facing reply text from the final agent message.
     *
     * Some models end the turn immediately after a tool call without emitting a
     * final text block. In that case fall back to the tool result output so the
     * user always sees an outcome (success or failure) instead of an empty reply.
     */
    private fun resolveReplyContent(msg: Msg): String {
        // HITL pause returned as a normal result: tool calls left in ASKING state.
        val asking = msg.getContentBlocks(ToolUseBlock::class.java)
            .filter { it.state == ToolCallState.ASKING }
        if (asking.isNotEmpty()) {
            pendingToolCalls = asking
            log.info(
                "[harness] Result msg carries {} ASKING tool call(s) for session={}, prompting for confirmation",
                asking.size,
                sessionId,
            )
            val toolLines = asking.mapIndexed { i, b -> "${i + 1}. ${b.name}" }.joinToString("\n") + "\n\n"
            return "⚠️ AI 需要执行以下工具，请确认：\n\n${toolLines}回复 /approve 同意执行，或 /deny 拒绝。"
        }

        val text = MsgExtractHelper.extractText(msg)
        if (!text.isNullOrBlank()) return text

        val toolResults = msg.getContentBlocks(ToolResultBlock::class.java)
        if (toolResults.isNotEmpty()) {
            val toolOutput = toolResults.joinToString("\n") { MsgExtractHelper.extractToolOutput(it) }.trim()
            if (toolOutput.isNotBlank()) {
                log.info("[harness] Final msg has no text block, falling back to tool result output for session={}", sessionId)
                return toolOutput
            }
        }

        // Defensive: PERMISSION_ASKING with empty content means the permission gate blocked
        // tool execution but no ToolUseBlocks were surfaced. Prompt user to approve.
        if (msg.generateReason == GenerateReason.PERMISSION_ASKING) {
            log.warn(
                "[harness] PERMISSION_ASKING with no ASKING blocks in content for session={}, " +
                    "prompting for confirmation",
                sessionId,
            )
            return "⚠️ AI 需要执行工具操作，请回复 /approve 同意执行，或 /deny 拒绝。"
        }

        // Detailed diagnostic: dump all content block types so we can see exactly what the
        // ReAct loop returned (e.g. tool_use blocks in ALLOWED state = tool was approved but
        // never executed; empty ThinkingBlock = model returned empty completion, etc.)
        val allBlocks = msg.content ?: emptyList()
        val blockSummary = allBlocks.joinToString(", ") { b ->
            when (b) {
                is ToolUseBlock -> "ToolUse(name=${b.name}, id=${b.id}, state=${b.state})"
                is ToolResultBlock -> "ToolResult(id=${b.id}, state=${b.state})"
                is TextBlock -> "Text(len=${b.text?.length ?: 0})"
                is ThinkingBlock -> "Thinking(len=${b.thinking?.length ?: 0})"
                else -> "${b.javaClass.simpleName}"
            }
        }
        log.warn(
            "[harness] Final msg has neither text nor tool output for session={}, returning completion notice. " +
                "generateReason={}, contentBlocks=[{}]",
            sessionId,
            msg.generateReason,
            blockSummary,
        )
        return "✅ 已执行完成，但模型未返回文本说明。"
    }

    /**
     * If the throwable (or any of its causes) is the agentscope HITL pause —
     * tool calls waiting in ASKING state for user confirmation — build a
     * user-facing prompt asking for /approve or /deny. Returns null otherwise.
     *
     * The framework throws a plain IllegalStateException here (no tool call
     * objects attached), so we parse "name (id=call_xxx)" pairs out of the
     * message and rebuild minimal [ToolUseBlock]s into [pendingToolCalls];
     * the confirm flow matches them against the persisted ASKING state by id.
     */
    private fun buildConfirmPromptIfPaused(error: Throwable): String? {
        var t: Throwable? = error
        var msg: String? = null
        while (t != null) {
            val m = t.message
            if (m != null && (m.contains("human-in-the-loop confirmation") || m.contains("ASKING state"))) {
                msg = m
                break
            }
            t = t.cause
        }
        if (msg == null) return null

        // Prefer the complete ToolUseBlocks (with full input args) from the
        // persisted agent state. Confirming with a rebuilt block that has an
        // empty input map would execute the tool with no arguments.
        val askingBlocks = findAskingToolCallsFromState()
        if (askingBlocks.isNotEmpty()) {
            pendingToolCalls = askingBlocks
            log.info(
                "[harness] Recovered {} ASKING tool call(s) from persisted state for session={}: {}",
                askingBlocks.size,
                sessionId,
                askingBlocks.joinToString(", ") { it.name },
            )
        } else {
            // Fallback: rebuild minimal blocks from the message. The confirm may
            // execute with empty args, but at least /approve will not 404.
            val pairs = Regex("([\\w-]+)\\s*\\(id=([^)\\s]+)\\)").findAll(msg)
                .map { it.groupValues[1] to it.groupValues[2] }
                .toList()
            if (pairs.isNotEmpty()) {
                pendingToolCalls = pairs.map { (name, id) ->
                    ToolUseBlock.builder().id(id).name(name).input(emptyMap()).build()
                }
                log.warn(
                    "[harness] State recovery failed; cached {} minimal pending tool call(s) from pause message for session={}",
                    pairs.size,
                    sessionId,
                )
            }
        }

        val toolNames = pendingToolCalls.map { it.name }
        val toolLines = if (toolNames.isEmpty()) {
            ""
        } else {
            toolNames.mapIndexed { i, name -> "${i + 1}. $name" }.joinToString("\n") + "\n\n"
        }
        return "⚠️ AI 需要执行以下工具，请确认：\n\n${toolLines}回复 /approve 同意执行，或 /deny 拒绝。"
    }

    /**
     * Recover the pending (ASKING) tool call blocks — including their full
     * input arguments — from the persisted [AgentState] context, scanning from
     * the most recent message backwards.
     */
    private fun findAskingToolCallsFromState(): List<ToolUseBlock> {
        return try {
            val delegate = harnessAgent.delegate ?: return emptyList()
            val state = delegate.getAgentState(userId, sessionId) ?: return emptyList()
            for (m in state.context.asReversed()) {
                val asking = m.getContentBlocks(ToolUseBlock::class.java)
                    .filter { it.state == ToolCallState.ASKING }
                if (asking.isNotEmpty()) return asking
            }
            emptyList()
        } catch (e: Exception) {
            log.debug("[harness] findAskingToolCallsFromState failed for session={}: {}", sessionId, e.message)
            emptyList()
        }
    }

    /**
     * Interrupt the ongoing agent execution.
     *
     * Three mechanisms are used:
     * 1. Sets [interrupted] flag so [call] can detect the interrupt and suppress resulting errors.
     * 2. Disposes the active [Disposable] from [call] — best-effort Mono subscription cancellation.
     * 3. Interrupts the blocking thread to break out of `Mono.block()`.
     * 4. Calls [HarnessAgent.interrupt] — invalidates the sandbox context, causing the agent's
     *    internal operations to fail and the Mono to complete with an error.
     */
    fun interrupt() {
        log.info("[harness] Interrupting agent for session={}", sessionId)
        // 1. Mark as interrupted BEFORE triggering any cancellation, so the catch block
        //    in call() can detect it regardless of timing.
        interrupted = true
        // 2. Cancel the Mono subscription (best-effort)
        val disposable = activeCallDisposable
        if (disposable != null && !disposable.isDisposed) {
            disposable.dispose()
            log.info("[harness] Disposed active call subscription for session={}", sessionId)
        }
        // 3. Interrupt the blocking thread to break out of Mono.block()
        val thread = blockingThread
        if (thread != null) {
            thread.interrupt()
            log.info("[harness] Interrupted blocking thread for session={}", sessionId)
        }
        // 4. Signal to agent internals — invalidates sandbox, causes agent ops to fail
        harnessAgent.interrupt()
    }

    /**
     * Release what this agent created but nothing else will free: the MCP clients, then the agent.
     *
     * Called when the wrapper is discarded (cache expiry, an explicit invalidation such as `/refresh`
     * or a capability toggle, service shutdown). [HarnessAgent.close] stops at the state layer, so
     * without this a stdio client — an OS process — outlives every rebuild, and an agent is rebuilt on
     * a 30-minute timer and on each of those commands.
     *
     * Not safe to call while a request is in flight: an active call would lose the tools underneath
     * it. The caller owns that check.
     */
    fun release() {
        // Idempotent: a shutdown sweep and the cache's own removal listener can both reach for this
        // agent, and a double close would otherwise report the second one as a fresh failure.
        if (!released.compareAndSet(false, true)) return
        mcpClients.forEach { client ->
            try {
                client.close()
            } catch (e: Exception) {
                log.warn("[harness] Failed to close MCP client for session={}: {}", sessionId, e.message)
            }
        }
        try {
            harnessAgent.close()
        } catch (e: Exception) {
            log.warn("[harness] Failed to close agent for session={}: {}", sessionId, e.message)
        }
    }

    private fun callStreamInternal(
        vararg msg: Msg = arrayOf(),
    ): Flux<ChatEvent> {
        val ctxResult = buildRuntimeContext()
        // Ensure configured permission rules are merged into loaded state before setting mode
        ensurePermissionRulesMerged()
        // Channel sessions (chn-*) cannot support interactive tool approval (no /approve /deny UI),
        // so force BYPASS to prevent PERMISSION_ASKING from blocking tool execution.
        val effectiveMode = if (sessionId.startsWith("chn-")) "BYPASS" else permissionMode
        harnessAgent.setPermissionMode(ctxResult.runtimeContext, PermissionMode.fromString(effectiveMode))
        return harnessAgent.streamEvents(msg.toList(), ctxResult.runtimeContext)
            .doOnNext { agentEvent ->
                // Intercept RequireUserConfirmEvent BEFORE flatMap to cache pending tool calls
                if (agentEvent is RequireUserConfirmEvent) {
                    pendingToolCalls = agentEvent.toolCalls
                    log.info("[harness] Cached {} pending tool calls for session={}", agentEvent.toolCalls.size, sessionId)
                }
            }
            .flatMap { agentEvent ->
                when (agentEvent.type) {
                    AgentEventType.TOOL_CALL_DELTA -> {
                        // Accumulate argument delta fragments
                        val deltaEvent = agentEvent as ToolCallDeltaEvent
                        toolCallArgsBuffer.merge(
                            deltaEvent.toolCallId,
                            deltaEvent.delta ?: "",
                        ) { old, new -> old + new }
                        Flux.empty<ChatEvent>()
                    }
                    AgentEventType.TOOL_CALL_END -> {
                        // Emit CallToolChatEvent with fully accumulated arguments
                        val endEvent = agentEvent as ToolCallEndEvent
                        val argsJson = toolCallArgsBuffer.remove(endEvent.toolCallId) ?: ""
                        ChatEventConverter.convertToolCallEnd(
                            endEvent.toolCallId,
                            endEvent.toolCallName,
                            argsJson,
                        )
                    }
                    AgentEventType.TOOL_RESULT_START -> {
                        // Start event not needed by frontend; tool card already exists from CallToolEvent
                        Flux.empty<ChatEvent>()
                    }
                    AgentEventType.TOOL_RESULT_TEXT_DELTA -> {
                        // Accumulate result text delta fragments
                        val deltaEvent = agentEvent as ToolResultTextDeltaEvent
                        toolResultBuffer.merge(
                            deltaEvent.toolCallId,
                            deltaEvent.delta ?: "",
                        ) { old, new -> old + new }
                        Flux.empty<ChatEvent>()
                    }
                    AgentEventType.TOOL_RESULT_DATA_DELTA -> {
                        // Binary data result delta (e.g. images) — currently not accumulated.
                        // Log for observability; text results cover all current tools.
                        log.debug(
                            "[harness] TOOL_RESULT_DATA_DELTA for toolId={}, skipping binary accumulation",
                            (agentEvent as? io.agentscope.core.event.ToolResultDataDeltaEvent)?.toolCallId,
                        )
                        Flux.empty<ChatEvent>()
                    }
                    AgentEventType.TOOL_RESULT_END -> {
                        // Delegate to ChatEventConverter with accumulated result text
                        val endEvent = agentEvent as ToolResultEndEvent
                        val resultText = toolResultBuffer.remove(endEvent.toolCallId) ?: ""
                        ChatEventConverter.convertToolResultEnd(endEvent, resultText)
                    }
                    else -> ChatEventConverter.convert(agentEvent, dangerousTools)
                }
            }
            .doOnNext { extracted(it) }
            .doFinally { persistKeepAliveSnapshot(ctxResult) }
            .concatWith(Flux.just(EndEventChatEvent()))
            .onErrorResume { e ->
                log.error("[harness] stream error for session={}: {}", sessionId, e.message, e)
                val errorEvent = if (e is HarnaxException) {
                    ErrorChatEvent.from(e)
                } else {
                    ErrorChatEvent(
                        code = HarnaxErrorCode.SYSTEM_ERROR.code,
                        message = e.message ?: "Unknown error",
                    )
                }
                Flux.just(errorEvent, EndEventChatEvent())
            }
    }

    private fun imageBlock(url: String): ImageBlock = if (url.startsWith("data:image")) {
        val parts = url.split(",")
        val mimeType = parts[0].substringAfter(":").substringBefore(";")
        val base64Data = parts[1]
        ImageBlock.builder().source(
            Base64Source.builder().data(base64Data).mediaType(mimeType).build(),
        ).build()
    } else {
        ImageBlock.builder().source(
            Base64Source.builder()
                .data(Base64.getEncoder().encodeToString(Files.readAllBytes(Paths.get(url))))
                .mediaType("image/png")
                .build(),
        ).build()
    }

    private fun extracted(it: ChatEvent) {
        if (it.tokenUsage != null) {
            tokenStatAdaptor.saveTokenStat(
                tokenStatBuilder.inputToken(it.tokenUsage!!.inputTokens)
                    .outputToken(it.tokenUsage!!.outputTokens)
                    .totalToken(it.tokenUsage!!.totalTokens)
                    .build(),
            )
        }
    }

    /**
     * Persists the workspace snapshot for the keep-alive sandbox (if any).
     *
     * Called after both streaming ([callStreamInternal]) and non-streaming ([call])
     * requests complete, ensuring workspace state is saved regardless of the call path.
     * Exceptions are caught and logged so they never propagate to the caller.
     */
    private fun persistKeepAliveSnapshot(ctxResult: RuntimeContextResult) {
        val keepAliveSandbox = ctxResult.keepAliveSandbox ?: return
        try {
            val snapshot = keepAliveSandbox.state.snapshot
            if (snapshot != null && snapshot.isPersistenceEnabled) {
                keepAliveSandbox.persistWorkspace().use { archive ->
                    snapshot.persist(archive)
                }
                log.debug("[keepAlive] Workspace snapshot persisted for session={}", sessionId)
            }
        } catch (e: Exception) {
            log.warn("[keepAlive] Failed to persist snapshot for session={}: {}", sessionId, e.message)
        }
    }

    /**
     * Detect new files in the sandbox workspace and persist them to MinIO.
     * Returns a list of [FileAttachment] with stable download URLs.
     *
     * This method is best-effort: any failure is logged and an empty list is returned,
     * so it never affects the main text reply.
     */
    private fun detectAndPersistOutputFiles(callStartTime: Long): List<FileAttachment> {
        val detector = outputFileDetector ?: return emptyList()
        val sandboxManager = keepAliveSandboxManager ?: return emptyList()

        // Channel sessions download directly from sandbox workspace; no MinIO needed.
        val isChannelSession = sessionId.startsWith("chn-")
        val store = outputFileStore
        if (!isChannelSession && store == null) return emptyList()

        return try {
            val sandbox = sandboxManager.getSandbox(sessionId) ?: run {
                log.debug("[outputFiles] No sandbox available for session={}, skipping file detection", sessionId)
                return emptyList()
            }

            val detectedFiles = detector.detect(sandbox, sandboxWorkspaceRoot, callStartTime)
            if (detectedFiles.isEmpty()) return emptyList()

            val attachments = mutableListOf<FileAttachment>()
            for (file in detectedFiles) {
                try {
                    val mimeType = guessMimeType(file.fileName)

                    if (isChannelSession) {
                        // Channel: return workspace path only, channel-service downloads via workspace API
                        attachments.add(
                            FileAttachment(
                                fileId = java.util.UUID.randomUUID().toString(),
                                fileName = file.fileName,
                                filePath = file.path,
                                fileSize = file.size,
                                mimeType = mimeType,
                                url = "",
                                objectKey = "",
                            ),
                        )
                    } else {
                        // Web/Task: persist to MinIO for stable download URL
                        val fileBytes = extractFileFromSandbox(sandbox, file.path) ?: continue
                        val stored = store!!.persist(
                            sessionId = sessionId,
                            fileName = file.fileName,
                            data = fileBytes,
                            mimeType = mimeType,
                        )
                        attachments.add(
                            FileAttachment(
                                fileId = stored.fileId,
                                fileName = file.fileName,
                                filePath = file.path,
                                fileSize = file.size,
                                mimeType = mimeType,
                                url = stored.url,
                                objectKey = stored.objectKey,
                            ),
                        )
                    }
                } catch (e: Exception) {
                    log.warn("[outputFiles] Failed to process file '{}' for session={}: {}", file.fileName, sessionId, e.message)
                }
            }

            if (attachments.isNotEmpty()) {
                log.info("[outputFiles] Detected {} file(s) for session={}: {}", attachments.size, sessionId, attachments.joinToString { it.fileName })
            }
            attachments
        } catch (e: Exception) {
            log.warn("[outputFiles] Output file detection/persistence failed for session={}: {}", sessionId, e.message)
            emptyList()
        }
    }

    /**
     * Extract file bytes from sandbox using base64 encoding.
     */
    private fun extractFileFromSandbox(sandbox: io.agentscope.harness.agent.sandbox.Sandbox, filePath: String): ByteArray? {
        return try {
            val result = sandbox.exec(null, "base64 '$filePath'", 30)
            val base64Str = result.stdout().trim()
            if (base64Str.isBlank()) {
                log.warn("[outputFiles] Empty base64 output for file: {}", filePath)
                return null
            }
            Base64.getMimeDecoder().decode(base64Str)
        } catch (e: Exception) {
            log.warn("[outputFiles] Failed to extract file '{}': {}", filePath, e.message)
            null
        }
    }

    /**
     * Append download links to the reply text so WebUI users can click to download.
     */
    private fun appendDownloadLinks(content: String, attachments: List<FileAttachment>): String {
        val sb = StringBuilder(content)
        sb.appendLine()
        sb.appendLine()
        sb.appendLine("\uD83D\uDCCE **生成的文件：**")
        attachments.forEach { file ->
            sb.appendLine("- [${file.fileName}](${file.url})")
        }
        return sb.toString()
    }

    /**
     * Guess MIME type from file extension.
     */
    private fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast(".", "").lowercase()
        return when (ext) {
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "ppt" -> "application/vnd.ms-powerpoint"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "xls" -> "application/vnd.ms-excel"
            "csv" -> "text/csv"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "doc" -> "application/msword"
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "zip" -> "application/zip"
            "tar" -> "application/x-tar"
            "gz" -> "application/gzip"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            "wav" -> "audio/wav"
            "html" -> "text/html"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
    }

    private fun buildRuntimeContext(): RuntimeContextResult {
        val ctxBuilder = RuntimeContext.builder()
            .sessionId(sessionId)
            .userId(userId ?: "")

        var keepAliveSandbox: io.agentscope.harness.agent.sandbox.Sandbox? = null
        if (keepAliveSandboxManager != null) {
            val sandbox = keepAliveSandboxManager.getOrCreate(
                sessionId,
                WorkspaceSpec(),
                keepAliveSnapshotSpec,
                imageOverride = sandboxImage,
                env = sandboxEnv,
            )
            keepAliveSandbox = sandbox

            // === CLI Plugin initialization (only once per wrapper lifecycle) ===
            if (!pluginsInitialized && pluginInitializers.isNotEmpty() && pluginAdminUrl.isNotEmpty() && pluginInternalSecret.isNotEmpty()) {
                pluginInitializers.forEach { initializer ->
                    initializer.initialize(sandbox, pluginAdminUrl, pluginInternalSecret)
                }
                pluginsInitialized = true
            } else if (!pluginsInitialized && pluginInitializers.isNotEmpty()) {
                log.warn("[harness] CLI plugins configured but skipped: pluginAdminUrl or pluginInternalSecret is empty")
            }

            val clientOptions = DockerSandboxClientOptions()
                .image(sandboxImage)
                .workspaceRoot(sandboxWorkspaceRoot)
            if (sandboxEnv.isNotEmpty()) {
                clientOptions.environment = sandboxEnv
            }
            if (sandboxNetwork != null) {
                clientOptions.network(sandboxNetwork)
            }
            val sandboxContext = SandboxContext.builder()
                .client(DockerSandboxClient())
                .clientOptions(clientOptions)
                .externalSandbox(sandbox)
                .build()
            ctxBuilder.put(SandboxContext::class.java, sandboxContext)
            log.debug("[keepAlive] Injected external sandbox for session={}", sessionId)
        }

        return RuntimeContextResult(ctxBuilder.build(), keepAliveSandbox)
    }

    private data class RuntimeContextResult(
        val runtimeContext: RuntimeContext,
        val keepAliveSandbox: io.agentscope.harness.agent.sandbox.Sandbox?,
    )
}

/**
 * Structured plan data extracted from [AgentState].
 *
 * Preferred over parsing markdown files (plans/PLAN.md, plans/todo.md) because
 * it carries real-time task execution states (PENDING/IN_PROGRESS/COMPLETED)
 * written by the `todo_write` tool.
 */
data class AgentStatePlanData(
    /** Whether plan mode is currently active. */
    val planActive: Boolean,
    /** Workspace-relative path of the current plan file (e.g. "plans/PLAN.md"). */
    val currentPlanFile: String?,
    /** Structured tasks from `tasksContext` — null if no tasks exist. */
    val tasks: List<Task>?,
)

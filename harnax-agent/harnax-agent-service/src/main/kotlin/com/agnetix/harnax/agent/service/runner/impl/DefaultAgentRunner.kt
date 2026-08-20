package com.agnetix.harnax.agent.service.runner.impl

import com.agnetix.harnax.agent.adaptor.PlanNote
import com.agnetix.harnax.agent.adaptor.PlanSubTask
import com.agnetix.harnax.agent.adaptor.TaskState
import com.agnetix.harnax.agent.chat.MessageLog
import com.agnetix.harnax.agent.chat.MessageLogConverter
import com.agnetix.harnax.agent.protocol.ChatAgentRequest
import com.agnetix.harnax.agent.protocol.ChatEvent
import com.agnetix.harnax.agent.protocol.ChatResponse
import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.agent.protocol.CommandResponse
import com.agnetix.harnax.agent.protocol.CommandType
import com.agnetix.harnax.agent.protocol.ConfirmAgentRequest
import com.agnetix.harnax.agent.protocol.EndEventChatEvent
import com.agnetix.harnax.agent.protocol.ErrorChatEvent
import com.agnetix.harnax.agent.protocol.StreamTextChatEvent
import com.agnetix.harnax.agent.protocol.ToolResultChatEvent
import com.agnetix.harnax.agent.service.client.AdminApiClient
import com.agnetix.harnax.agent.service.client.AgentSpecContextHolder
import com.agnetix.harnax.agent.service.runner.AgentRunner
import com.agnetix.harnax.agent.service.runner.AgentSpecResolver
import com.agnetix.harnax.common.error.HarnaxErrorCode
import com.agnetix.harnax.common.error.HarnaxException
import com.agnetix.harnax.harness.AgentStatePlanData
import com.agnetix.harnax.harness.HarnessAgentLauncher
import com.agnetix.harnax.harness.HarnessAgentWrapper
import com.agnetix.harnax.tools.sdk.UserIdentifier
import com.github.benmanes.caffeine.cache.Caffeine
import io.agentscope.core.event.ConfirmResult
import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import io.agentscope.core.state.AgentState
import io.agentscope.core.state.Task
import org.reactivestreams.Subscription
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap as JConcurrentHashMap

/**
 * Default implementation of AgentRunner.
 * Uses HarnessAgentLauncher to create agents based on session configuration.
 */
@Service
class DefaultAgentRunner(
    private val launcher: HarnessAgentLauncher,
    private val agentSpecResolver: AgentSpecResolver,
    private val specContextHolder: AgentSpecContextHolder,
    private val adminApiClient: AdminApiClient,
    @Value($$"${agent.cache.max-size:500}")
    private val cacheMaxSize: Long,
) : AgentRunner {

    private val log = LoggerFactory.getLogger(DefaultAgentRunner::class.java)
    private val agentCache = Caffeine.newBuilder()
        .maximumSize(cacheMaxSize)
        .expireAfterWrite(30, TimeUnit.MINUTES)
        .removalListener<String, HarnessAgentWrapper> { key, _, cause ->
            log.info("Agent evicted from cache: session=$key, cause=$cause")
        }
        .build<String, HarnessAgentWrapper>()
    private val activeStreams = JConcurrentHashMap<String, Subscription>()

    override fun process(request: ChatAgentRequest): ChatResponse {
        val sessionId = request.sessionId
        val message = request.message
        val imageUrls = request.imageUrls
        log.info("Processing direct (non-streaming) chat request for session=$sessionId")

        try {
            // TODO [P1] UserIdentifier(0) is hardcoded — all requests share userId=0.
            //   Should extract real user ID from request context (e.g. SecurityContext or request header).
            val userIdentifier = UserIdentifier(0)
            val agent = getOrCreateAgent(sessionId, userIdentifier)
            return agent.call(message, imageUrls)
        } catch (e: Exception) {
            log.error("Error creating agent or calling for session=$sessionId: ${e.message}", e)
            throw e as? HarnaxException
                ?: HarnaxException(
                    HarnaxErrorCode.AGENT_INIT_FAILED.code,
                    e.message ?: "Agent call failed",
                    e,
                )
        }
    }

    override fun streamProcess(request: ChatAgentRequest): Flux<ChatEvent> {
        val sessionId = request.sessionId
        val message = request.message
        val imageUrls = request.imageUrls
        log.info("Streaming message for session=$sessionId: $message, images=${imageUrls.size}")

        try {
            // TODO [P1] UserIdentifier(0) is hardcoded — see process() for details.
            val userIdentifier = UserIdentifier(0)
            val agent = getOrCreateAgent(sessionId, userIdentifier)
            return agent.callStream(message, imageUrls)
                .doOnSubscribe { subscription ->
                    activeStreams[sessionId] = subscription
                    log.debug("Stream started for session=$sessionId")
                }
                .doFinally {
                    activeStreams.remove(sessionId)
                    log.debug("Stream ended for session=$sessionId")
                }
        } catch (e: Exception) {
            log.error("Error creating agent or streaming for session=$sessionId: ${e.message}", e)
            val errorEvent = if (e is HarnaxException) {
                ErrorChatEvent.from(e)
            } else {
                ErrorChatEvent(
                    code = HarnaxErrorCode.AGENT_INIT_FAILED.code,
                    message = e.message ?: "Agent initialization failed",
                )
            }
            return Flux.just(errorEvent, EndEventChatEvent())
        }
    }

    override fun executeCommand(request: CommandAgentRequest): CommandResponse {
        val sessionId = request.sessionId
        val command = request.command
        val args = request.args
        log.info("Executing command for session=$sessionId, command=$command, args='$args'")
        return when (command) {
            CommandType.INTERRUPT -> {
                interrupt(sessionId)
                CommandResponse.success(sessionId, message = "Stream interrupted")
            }
            CommandType.CLEAR -> {
                clearSession(sessionId)
                CommandResponse.success(sessionId, message = "Session cleared")
            }
            CommandType.COMPACT -> {
                // TODO: implement memory compaction/summarization (args may carry token limit etc.)
                log.info("Compact command received for session=$sessionId, args='$args' (not yet implemented)")
                CommandResponse.success(sessionId, message = "Compact not yet implemented")
            }
            CommandType.APPROVE -> handleApproveOrDeny(sessionId, isConfirmed = true)
            CommandType.DENY -> handleApproveOrDeny(sessionId, isConfirmed = false)
            CommandType.STOP_SANDBOX -> {
                val sandboxManager = launcher.keepAliveSandboxManager
                if (sandboxManager != null) {
                    interrupt(sessionId)
                    agentCache.invalidate(sessionId)
                    sandboxManager.destroy(sessionId)
                    log.info("Sandbox stopped for session=$sessionId")
                    CommandResponse.success(sessionId, message = "Sandbox stopped")
                } else {
                    log.warn("Stop-sandbox command received but keepAliveSandboxManager is null for session=$sessionId")
                    CommandResponse.failure(sessionId, "Sandbox manager not available")
                }
            }
            CommandType.ENABLE -> {
                handleCapabilityToggle(sessionId, args, enable = true)
            }
            CommandType.DISABLE -> {
                handleCapabilityToggle(sessionId, args, enable = false)
            }
            CommandType.PERMISSION -> {
                handlePermissionModeChange(sessionId, args)
            }
            CommandType.REFRESH -> {
                // Force-rebuild the agent entity from the latest spec.
                // Invalidates the agent cache so the next getOrCreateAgent() call will:
                //   1. Re-fetch the agent spec from admin (picks up config changes)
                //   2. Re-create the HarnessAgent + HarnessAgentWrapper
                // The sandbox container and session message history are preserved because:
                //   - KeepAliveSandboxManager.getOrCreate() returns the existing container
                //   - Session messages are stored in DB, not in the agent instance
                val existingAgent = agentCache.getIfPresent(sessionId)
                if (existingAgent != null) {
                    agentCache.invalidate(sessionId)
                    log.info("Agent cache invalidated for session=$sessionId (refresh command). Next call will rebuild from latest spec.")
                    CommandResponse.success(sessionId, message = "Agent refreshed — next message will use the latest configuration")
                } else {
                    log.info("Refresh command for session=$sessionId but no cached agent found")
                    CommandResponse.success(sessionId, message = "No active agent to refresh")
                }
            }
        }
    }

    override fun interrupt(sessionId: String) {
        // 1. Interrupt the agent execution via HarnessAgent.interrupt() ( works both streaming and blocking calls)
        val agent = agentCache.getIfPresent(sessionId)
        if (agent != null) {
            agent.interrupt()
        }

        // 2. Also cancel active stream subscription (belt-and-suspenders for streaming case)
        val subscription = activeStreams.remove(sessionId)
        if (subscription != null) {
            subscription.cancel()
            log.info("Interrupted active stream for session=$sessionId")
        }

        if (agent == null && subscription == null) {
            log.info("No active agent or stream to interrupt for session=$sessionId")
        }
    }

    override fun loadHistory(sessionId: String): List<MessageLog> = launcher.loadSessionMessages(sessionId)
        .flatMap { MessageLogConverter.convert(it) }

    override fun confirm(request: ConfirmAgentRequest): Flux<ChatEvent> {
        val sessionId = request.sessionId
        log.info("Confirm request for session=$sessionId, confirmed=${request.isConfirmed}, toolResults=${request.toolResults.size}")
        val agent = agentCache.getIfPresent(sessionId)
            ?: run {
                log.warn("Agent not in cache for confirm, rebuilding: session=$sessionId")
                getOrCreateAgent(sessionId, UserIdentifier(0))
            }

        // Build ConfirmResult list for agentscope's METADATA_CONFIRM_RESULTS
        val pendingToolCalls = agent.getPendingToolCalls()
        if (pendingToolCalls.isEmpty()) {
            log.error("No pending tool calls for confirm session=$sessionId — agent may have been rebuilt")
            return Flux.just(
                ErrorChatEvent(
                    code = HarnaxErrorCode.SYSTEM_ERROR.code,
                    message = "No pending tool confirmation for session $sessionId. The agent state may have been lost. Please retry your message.",
                ),
                EndEventChatEvent(),
            )
        }
        val confirmResults = if (request.toolResults.isNotEmpty()) {
            // Per-tool decision mode
            request.toolResults.map { tr ->
                val toolUseBlock = pendingToolCalls.find { it.id == tr.toolId }
                ConfirmResult(tr.confirmed, toolUseBlock, null)
            }
        } else {
            // Bulk mode: apply isConfirmed to all pending tools
            pendingToolCalls.map { ConfirmResult(request.isConfirmed, it, null) }
        }

        // Construct msg with ConfirmResult metadata as agentscope expects
        val msg = Msg.builder()
            .name("user").role(MsgRole.USER)
            .textContent("[confirm]")
            .metadata(mapOf(Msg.METADATA_CONFIRM_RESULTS to confirmResults))
            .build()

        val stream = agent.callStream(msg = msg)
        return stream
            .doOnSubscribe { subscription ->
                activeStreams[sessionId] = subscription
                log.debug("Confirm stream started for session=$sessionId")
            }
            .doFinally {
                activeStreams.remove(sessionId)
                log.debug("Confirm stream ended for session=$sessionId")
            }
    }

    override fun clearSession(sessionId: String) {
        interrupt(sessionId)
        agentCache.invalidate(sessionId)
        launcher.clearSession(sessionId)
        log.info("Cleared session and agent cache for sessionId=$sessionId")
    }

    override fun loadPlans(sessionId: String): List<PlanNote> = launcher.loadSessionHistoryPlan(sessionId)

    override fun loadCurrentPlan(sessionId: String): PlanNote? {
        // First try the legacy DB-based plan notes
        val legacyPlan = launcher.loadSessionCurrentPlanNote(sessionId)
        if (legacyPlan != null) {
            log.info("[loadCurrentPlan] Returning legacy DB plan for session={}, subtasks={}", sessionId, legacyPlan.subtasks.size)
            return legacyPlan
        }

        // ─── Step 1: Try structured plan data from AgentState (preferred) ───
        val wrapper = agentCache.getIfPresent(sessionId)
        // When wrapper exists, readFullPlanFromState() already falls back to StateStore internally
        // via getLiveAgentState(), so readFullPlanFromStateStore() is only needed when wrapper is null
        // (e.g. after service restart or cache eviction).
        val statePlanData = if (wrapper != null) {
            wrapper.readFullPlanFromState()
        } else {
            readFullPlanFromStateStore(sessionId)
        }

        val stateTasks = statePlanData?.tasks
        if (stateTasks != null) {
            // AgentState has structured tasks — use them directly
            val subtasks = convertStructuredTasks(stateTasks)

            // For plan name/description, still read PLAN.md (AgentState doesn't store plan text)
            val planContent = wrapper?.readPlanContent() ?: readPlanFromSandbox(sessionId)
            val (name, description) = extractPlanNameAndDescription(planContent)

            log.info(
                "[loadCurrentPlan] Using AgentState: planActive={}, {} structured tasks, plan='{}' for session={}",
                statePlanData?.planActive,
                subtasks.size,
                name,
                sessionId,
            )

            return PlanNote(
                sessionId = sessionId,
                planId = "active",
                name = name,
                description = description,
                subtasks = subtasks,
                createdAt = "",
                finishedAt = null,
                costTimeSeconds = 0L,
                status = computePlanStatus(subtasks),
            )
        }

        // ─── Step 2: Fallback — parse PLAN.md for plan content and subtasks ───
        val planContent = wrapper?.readPlanContent() ?: readPlanFromSandbox(sessionId)

        if (planContent == null) {
            log.info(
                "[loadCurrentPlan] No plan content found for session={} (wrapper={})",
                sessionId,
                wrapper != null,
            )
            return null
        }

        val (name, description) = extractPlanNameAndDescription(planContent)

        // Parse subtasks from markdown (fallback when agent doesn't use todo_write)
        val subtasks = parseSubtasksFromPlanMarkdown(planContent)
        log.info(
            "[loadCurrentPlan] Plan '{}' from PLAN.md with {} subtasks (parsed from markdown) for session={}",
            name,
            subtasks.size,
            sessionId,
        )

        return PlanNote(
            sessionId = sessionId,
            planId = "active",
            name = name,
            description = description,
            subtasks = subtasks,
            createdAt = "",
            finishedAt = null,
            costTimeSeconds = 0L,
            status = computePlanStatus(subtasks),
        )
    }

    /**
     * Computes the overall plan status from its subtask states.
     * - All DONE → DONE
     * - Any IN_PROGRESS → IN_PROGRESS
     * - Otherwise → TODO
     */
    private fun computePlanStatus(subtasks: List<PlanSubTask>): TaskState {
        if (subtasks.isEmpty()) return TaskState.IN_PROGRESS
        return when {
            subtasks.all { it.state == TaskState.DONE } -> TaskState.DONE
            subtasks.any { it.state == TaskState.IN_PROGRESS } -> TaskState.IN_PROGRESS
            else -> TaskState.TODO
        }
    }

    /**
     * Extracts plan name and description from markdown content.
     * The first heading becomes the name, the rest becomes the description.
     */
    private fun extractPlanNameAndDescription(planContent: String?): Pair<String, String> {
        if (planContent == null) return "Plan" to ""

        val lines = planContent.lines()
        val titleLineIdx = lines.indexOfFirst { it.startsWith("#") }
        val name = if (titleLineIdx >= 0) {
            lines[titleLineIdx].replace(HEADING_PREFIX_REGEX, "").trim()
        } else {
            "Plan"
        }
        val description = if (titleLineIdx >= 0) {
            lines.toMutableList().apply { removeAt(titleLineIdx) }
                .joinToString("\n")
                .trim()
        } else {
            planContent
        }
        return name to description
    }

    /**
     * Parses subtasks from PLAN.md markdown as a fallback when agent doesn't use todo_write.
     * Extracts ## headings as section groups and `- [ ]` / `- [x]` checkboxes as individual tasks.
     */
    private fun parseSubtasksFromPlanMarkdown(planContent: String): List<PlanSubTask> {
        val lines = planContent.lines()
        val subtasks = mutableListOf<PlanSubTask>()
        var currentSection = ""

        for (line in lines) {
            val trimmed = line.trim()

            // Extract section headers (## level)
            val headerMatch = HEADER_REGEX.find(trimmed)
            if (headerMatch != null) {
                currentSection = headerMatch.groupValues[1].trim()
                continue
            }

            // Extract checkbox items
            val checkboxMatch = CHECKBOX_REGEX.find(trimmed)
            if (checkboxMatch != null) {
                val isChecked = checkboxMatch.groupValues[1].lowercase() == "x"
                val taskText = checkboxMatch.groupValues[2].trim()
                val state = if (isChecked) TaskState.DONE else TaskState.TODO
                val name = if (currentSection.isNotEmpty()) "$currentSection: $taskText" else taskText

                subtasks.add(
                    PlanSubTask(
                        name = name,
                        description = "",
                        expectedOutcome = "",
                        outcome = "",
                        state = state,
                        createdAt = "",
                        finishedAt = if (isChecked) "" else null,
                        costTimeSeconds = 0L,
                    ),
                )
            }
        }

        // If no checkbox items found, try to use ## headings as tasks
        if (subtasks.isEmpty()) {
            for (line in lines) {
                val trimmed = line.trim()
                val headerMatch = HEADER_REGEX.find(trimmed)
                if (headerMatch != null) {
                    subtasks.add(
                        PlanSubTask(
                            name = headerMatch.groupValues[1].trim(),
                            description = "",
                            expectedOutcome = "",
                            outcome = "",
                            state = TaskState.TODO,
                            createdAt = "",
                            finishedAt = null,
                            costTimeSeconds = 0L,
                        ),
                    )
                }
            }
        }

        return subtasks
    }

    /**
     * Reads plan markdown directly from sandbox when agent is not in cache.
     */
    private fun readPlanFromSandbox(sessionId: String): String? = readSandboxFile(sessionId, "plans/PLAN.md")

    /**
     * Reads a workspace file from sandbox via Docker exec, independent of agent cache.
     */
    private fun readSandboxFile(sessionId: String, relativePath: String): String? {
        val sandboxManager = launcher.keepAliveSandboxManager ?: return null
        return try {
            // Use attachToExisting to discover containers not in memory cache (e.g. after restart)
            val sandbox = sandboxManager.attachToExisting(sessionId) ?: run {
                log.debug("[readSandboxFile] No sandbox for session={}", sessionId)
                return null
            }
            val workspaceRoot = launcher.harnessConfig.sandbox.workspaceRoot
            val result = sandbox.exec(null, "cat $workspaceRoot/$relativePath 2>/dev/null", 5)
            val content = result.stdout()
            if (content.isBlank()) null else content
        } catch (e: Exception) {
            log.debug("[readSandboxFile] Failed for session={}, path={}: {}", sessionId, relativePath, e.message)
            null
        }
    }

    /**
     * Reads full plan data from AgentStateStore, independent of agent cache.
     * Used when the agent wrapper is not in the cache (e.g. after service restart).
     */
    private fun readFullPlanFromStateStore(sessionId: String): AgentStatePlanData? {
        return try {
            val stateStore = launcher.stateStore
            val state = stateStore.get(null, sessionId, "agent_state", AgentState::class.java)
                .orElse(null) ?: return null
            val planCtx = state.planModeContext
            val tasks = state.tasksContext?.tasks
            AgentStatePlanData(
                planActive = planCtx?.isPlanActive ?: false,
                currentPlanFile = planCtx?.currentPlanFile,
                tasks = if (tasks.isNullOrEmpty()) null else tasks,
            )
        } catch (e: Exception) {
            log.debug("[readFullPlanFromStateStore] Failed for session={}: {}", sessionId, e.message)
            null
        }
    }

    /**
     * Converts agentscope structured Task objects to our PlanSubTask model.
     */
    private fun convertStructuredTasks(tasks: List<Task>): List<PlanSubTask> = tasks.map { task ->
        val state = when (task.state) {
            Task.State.COMPLETED -> TaskState.DONE
            Task.State.IN_PROGRESS -> TaskState.IN_PROGRESS
            Task.State.PENDING -> TaskState.TODO
            else -> TaskState.TODO
        }
        PlanSubTask(
            name = task.subject ?: "",
            description = task.description ?: "",
            expectedOutcome = "",
            outcome = "",
            state = state,
            createdAt = task.createdAt ?: "",
            finishedAt = if (state == TaskState.DONE) "" else null,
            costTimeSeconds = 0L,
        )
    }

    override suspend fun initAgent(agentId: Long) {
        log.info("Initializing agent: $agentId")
        // Agent will be lazily created on first request via streamProcess
    }

    override suspend fun destroyAgent(agentId: Long) {
        log.info("Destroying agent: $agentId")
        agentCache.invalidateAll()
        log.info("Cleared all cached agents")
    }

    /**
     * Route agent creation based on sessionId prefix.
     * Delegates spec resolution to AgentSpecResolver (which calls Admin).
     */
    private fun getOrCreateAgent(sessionId: String, userIdentifier: UserIdentifier): HarnessAgentWrapper = try {
        agentCache.get(sessionId) { sid ->
            log.info("Resolving agent spec for sessionId=$sid")
            val (agentSpec, chatSpec) = agentSpecResolver.resolve(sid)
            val agent = launcher.createSingleAgent(
                agentSpec = agentSpec,
                sessionId = sid,
                stateless = false,
                chatSpec = chatSpec,
                userIdentifier = userIdentifier,
            )
            log.info("Agent for session=$sid created and cached successfully")
            agent
        }
    } finally {
        // Clear ThreadLocal context to prevent leaks after agent creation
        specContextHolder.clear()
    }

    /**
     * Handle /approve and /deny commands by delegating to the confirm flow.
     * Builds a ConfirmAgentRequest, calls confirm() to resume the agent,
     * collects the streaming output, and returns a CommandResponse with the text.
     */
    private fun handleApproveOrDeny(sessionId: String, isConfirmed: Boolean): CommandResponse {
        val action = if (isConfirmed) "approve" else "deny"
        log.info("Handling $action command for session=$sessionId, delegating to confirm flow")

        val confirmRequest = ConfirmAgentRequest(
            sessionId = sessionId,
            isConfirmed = isConfirmed,
        )
        val stream = confirm(confirmRequest)
        val events = stream.collectList().block() ?: emptyList()

        val textContent = StringBuilder()
        val toolResults = mutableListOf<ToolResultChatEvent>()
        var hasError = false
        var errorMessage = ""

        for (event in events) {
            when (event) {
                is StreamTextChatEvent -> textContent.append(event.message)
                is ToolResultChatEvent -> toolResults.add(event)
                is ErrorChatEvent -> {
                    hasError = true
                    errorMessage = "[${event.code}] ${event.message}"
                }
                else -> { /* skip other events */ }
            }
        }

        return if (hasError) {
            CommandResponse.failure(sessionId, errorMessage)
        } else {
            val output = textContent.toString()
            val message = if (output.isNotBlank()) {
                output
            } else if (toolResults.isNotEmpty()) {
                // Model ended the turn right after tool execution without a final
                // text; surface the tool outcomes so the user sees what happened.
                toolResults.joinToString("\n") { tr ->
                    val status = if (tr.success) "✅" else "❌"
                    val detail = tr.message.trim().let { if (it.length > 500) it.take(500) + "…" else it }
                    "$status ${tr.toolName}${if (detail.isBlank()) "" else ": $detail"}"
                }
            } else if (isConfirmed) {
                "Tools approved, agent resumed."
            } else {
                "Tools denied."
            }
            CommandResponse.success(sessionId, message = message)
        }
    }

    /**
     * Handle /enable and /disable commands to toggle session capabilities.
     *
     * Supported args values:
     * - "search"   → toggle enableSearch
     * - "thinking" → toggle enableThink
     * - "plan"     → toggle enablePlan
     * - "bypass"   → toggle permissionMode between BYPASS and DEFAULT
     *
     * After updating the DB, invalidates the agent cache so the next request
     * will rebuild the agent with the new ChatSpec.
     */
    private fun handleCapabilityToggle(sessionId: String, args: String, enable: Boolean): CommandResponse {
        val action = if (enable) "enable" else "disable"
        val capability = args.trim().lowercase()

        if (capability !in SUPPORTED_CAPABILITIES) {
            return CommandResponse.failure(
                sessionId,
                "Unknown capability: '$capability'. Supported: ${SUPPORTED_CAPABILITIES.joinToString(", ")}",
            )
        }

        // Task sessions don't support per-session capability toggle
        if (sessionId.startsWith("task-")) {
            return CommandResponse.failure(sessionId, "Capability toggle is not supported for task sessions")
        }

        val display = capability.replaceFirstChar { it.uppercase() }

        // Validate capability against model constraints:
        // - enabling requires model support
        // - disabling thinking is blocked when model requires thinking (thinkingMode=2)
        if (enable || capability == "thinking") {
            try {
                val specInfo = adminApiClient.getAgentSpec(sessionId)
                if (!enable && capability == "thinking" && specInfo.modelThinkingMode == 2) {
                    return CommandResponse.failure(
                        sessionId,
                        "Current model requires Deep Thinking and it cannot be turned off.",
                    )
                }
                if (enable) {
                    val unsupported = when (capability) {
                        "thinking" -> specInfo.modelSupportReasoning != 1 && specInfo.modelThinkingMode < 1
                        "search" -> specInfo.modelSupportInternet != 1
                        else -> false
                    }
                    if (unsupported) {
                        return CommandResponse.failure(
                            sessionId,
                            "Current model does not support $display. Please switch to a model that supports this capability.",
                        )
                    }
                }
            } catch (e: Exception) {
                log.warn("Failed to validate model capability for session=$sessionId: ${e.message}")
                // Proceed with toggle if validation fails (fail-open)
            }
        }

        // Delegate to admin API instead of direct DB writes
        val success = adminApiClient.toggleCapability(sessionId, capability, enable)
        if (!success) {
            return CommandResponse.failure(sessionId, "Failed to toggle $display via admin API")
        }

        // Invalidate cached agent so it gets recreated with new ChatSpec
        agentCache.invalidate(sessionId)

        log.info("Session capability toggled: session=$sessionId, action=$action, capability=$capability")
        return CommandResponse.success(sessionId, message = "$display ${action}d")
    }

    /**
     * Handle /permission command to change the session's permission mode.
     *
     * Valid args: DEFAULT, BYPASS, ACCEPT_EDITS, EXPLORE, DONT_ASK
     * Updates the DB and invalidates the agent cache so the next request
     * rebuilds with the new ChatSpec (including new permissionMode).
     */
    private fun handlePermissionModeChange(sessionId: String, args: String): CommandResponse {
        val mode = args.trim().uppercase()

        if (mode !in VALID_PERMISSION_MODES) {
            return CommandResponse.failure(
                sessionId,
                "Invalid permission mode: '$mode'. Valid modes: ${VALID_PERMISSION_MODES.joinToString(", ")}",
            )
        }

        // Task sessions don't support custom permission mode (always BYPASS)
        if (sessionId.startsWith("task-")) {
            return CommandResponse.failure(sessionId, "Permission mode change is not supported for task sessions")
        }

        // Delegate to admin API instead of direct DB writes
        val success = adminApiClient.updatePermissionMode(sessionId, mode)
        if (!success) {
            return CommandResponse.failure(sessionId, "Failed to update permission mode via admin API")
        }

        // Invalidate cached agent so it gets recreated with new permissionMode
        agentCache.invalidate(sessionId)

        log.info("Session permission mode changed: session=$sessionId, mode=$mode")
        return CommandResponse.success(sessionId, message = "Permission mode set to $mode")
    }

    companion object {
        private val SUPPORTED_CAPABILITIES = setOf("search", "thinking", "plan", "bypass")
        private val VALID_PERMISSION_MODES = setOf("DEFAULT", "BYPASS", "ACCEPT_EDITS", "EXPLORE", "DONT_ASK")
        private val HEADER_REGEX = Regex("^##\\s+(.+)$")
        private val CHECKBOX_REGEX = Regex("^-\\s+\\[([ xX])\\]\\s+(.+)$")
        private val HEADING_PREFIX_REGEX = Regex("^#+\\s*")
    }
}

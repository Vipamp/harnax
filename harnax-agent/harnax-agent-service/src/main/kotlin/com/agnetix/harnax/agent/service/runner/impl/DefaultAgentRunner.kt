package com.agnetix.harnax.agent.service.runner.impl

import com.agnetix.harnax.agent.adaptor.PlanNote
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
import com.agnetix.harnax.agent.service.runner.AgentRunner
import com.agnetix.harnax.agent.service.runner.AgentSpecResolver
import com.agnetix.harnax.common.error.HarnaxErrorCode
import com.agnetix.harnax.common.error.HarnaxException
import com.agnetix.harnax.harness.HarnessAgentLauncher
import com.agnetix.harnax.harness.HarnessAgentWrapper
import com.agnetix.harnax.mapper.ChannelMapper
import com.agnetix.harnax.mapper.SessionMapper
import com.agnetix.harnax.tools.sdk.UserIdentifier
import com.github.benmanes.caffeine.cache.Caffeine
import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import io.agentscope.core.message.TextBlock
import io.agentscope.core.message.ToolResultBlock
import org.reactivestreams.Subscription
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap as JConcurrentHashMap

/**
 * Default implementation of AgentRunner.
 * Uses HarnessAgentLauncher to create agents based on session configuration,
 * similar to ChatService but adapted for the AgentRunner interface.
 */
@Service
class DefaultAgentRunner(
    private val launcher: HarnessAgentLauncher,
    private val agentSpecResolver: AgentSpecResolver,
    private val sessionMapper: SessionMapper,
    private val channelMapper: ChannelMapper,
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
            CommandType.APPROVE -> {
                // TODO: implement approve with optional args
                log.info("Approve command received for session=$sessionId, args='$args' (not yet implemented)")
                CommandResponse.success(sessionId, message = "Approve not yet implemented")
            }
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
        log.info("Confirm request for session=$sessionId, confirmed=${request.isConfirmed}")
        val agent = agentCache.getIfPresent(sessionId)
            ?: run {
                log.warn("Agent not in cache for confirm, rebuilding: session=$sessionId")
                getOrCreateAgent(sessionId, UserIdentifier(0))
            }
        val stream = if (request.isConfirmed) {
            agent.callStream()
        } else {
            val results = request.toolInfoList.map { tool ->
                ToolResultBlock.of(
                    tool.toolId,
                    tool.toolName,
                    TextBlock.builder().text("Operation cancelled by user").build(),
                )
            }
            val cancelResult = Msg.builder()
                .name("Assistant").role(MsgRole.TOOL)
                .content(*results.toTypedArray())
                .build()
            agent.callStream(msg = cancelResult)
        }
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

    override fun loadCurrentPlan(sessionId: String): PlanNote? = launcher.loadSessionCurrentPlanNote(sessionId)

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
    private fun getOrCreateAgent(sessionId: String, userIdentifier: UserIdentifier): HarnessAgentWrapper = agentCache.get(sessionId) { sid ->
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

    /**
     * Handle /enable and /disable commands to toggle session capabilities.
     *
     * Supported args values:
     * - "search"   → toggle enableSearch
     * - "thinking" → toggle enableThink
     * - "plan"     → toggle enablePlan
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

        val flag = if (enable) 1 else 0
        val display = capability.replaceFirstChar { it.uppercase() }

        if (sessionId.startsWith("chn-")) {
            // Channel session: update channel table
            val channel = channelMapper.selectBySessionId(sessionId)
                ?: return CommandResponse.failure(sessionId, "Channel not found: $sessionId")
            when (capability) {
                "search" -> channel.enableSearch = flag
                "thinking" -> channel.enableThink = flag
                "plan" -> channel.enablePlan = flag
            }
            channel.updateTime = LocalDateTime.now()
            channelMapper.updateById(channel)
        } else {
            // Web/mp session: update session table
            val session = sessionMapper.selectBySessionIdAndStatus(sessionId, 1)
                ?: return CommandResponse.failure(sessionId, "Session not found: $sessionId")
            when (capability) {
                "search" -> session.enableSearch = flag
                "thinking" -> session.enableThink = flag
                "plan" -> session.enablePlan = flag
            }
            session.updateTime = LocalDateTime.now()
            sessionMapper.updateById(session)
        }

        // Invalidate cached agent so it gets recreated with new ChatSpec
        agentCache.invalidate(sessionId)

        log.info("Session capability toggled: session=$sessionId, action=$action, capability=$capability")
        return CommandResponse.success(sessionId, message = "$display ${action}d")
    }

    companion object {
        private val SUPPORTED_CAPABILITIES = setOf("search", "thinking", "plan")
    }
}

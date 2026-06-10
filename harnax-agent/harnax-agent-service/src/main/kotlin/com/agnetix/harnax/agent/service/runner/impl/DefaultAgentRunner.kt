package com.agnetix.harnax.agent.service.runner.impl

import com.agnetix.harnax.agent.AgentSpec
import com.agnetix.harnax.agent.AscopeAgentLauncher
import com.agnetix.harnax.agent.ChatSpecBuilder
import com.agnetix.harnax.agent.ReActAgentWrapper
import com.agnetix.harnax.agent.chat.MessageLog
import com.agnetix.harnax.agent.chat.MessageLogConverter
import com.agnetix.harnax.agent.protocol.ChatAgentRequest
import com.agnetix.harnax.agent.protocol.ChatEvent
import com.agnetix.harnax.agent.protocol.ChatResponse
import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.agent.protocol.CommandResponse
import com.agnetix.harnax.agent.protocol.CommandType
import com.agnetix.harnax.agent.protocol.EndEventChatEvent
import com.agnetix.harnax.agent.protocol.ErrorChatEvent
import com.agnetix.harnax.agent.provider.tool.UserIdentifier
import com.agnetix.harnax.agent.service.runner.AgentRunner
import com.agnetix.harnax.common.error.HarnaxErrorCode
import com.agnetix.harnax.common.error.HarnaxException
import com.agnetix.harnax.mapper.SessionMapper
import org.reactivestreams.Subscription
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import java.util.concurrent.ConcurrentHashMap

/**
 * Default implementation of AgentRunner.
 * Uses AscopeAgentLauncher to create agents based on session configuration,
 * similar to ChatService but adapted for the AgentRunner interface.
 */
@Service
class DefaultAgentRunner(
    private val launcher: AscopeAgentLauncher,
    private val sessionMapper: SessionMapper,
) : AgentRunner {

    private val log = LoggerFactory.getLogger(DefaultAgentRunner::class.java)
    private val agentCache = ConcurrentHashMap<String, ReActAgentWrapper>()
    private val activeStreams = ConcurrentHashMap<String, Subscription>()

    override fun process(request: ChatAgentRequest): ChatResponse {
        val sessionId = request.sessionId
        log.info("Processing direct chat request for session=$sessionId")
        val events = streamProcess(request).collectList().block() ?: emptyList()
        return ChatResponse.fromEvents(sessionId, events)
    }

    override fun streamProcess(request: ChatAgentRequest): Flux<ChatEvent> {
        val sessionId = request.sessionId
        val message = request.message
        log.info("Streaming message for session=$sessionId: $message")
        try {
            val userIdentifier = UserIdentifier(0)
            val agent = getOrCreateAgent(sessionId, userIdentifier)
            return agent.callStream(message)
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
        log.info("Executing command for session=$sessionId, command=$command")
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
                // TODO: implement memory compaction/summarization
                log.info("Compact command received for session=$sessionId (not yet implemented)")
                CommandResponse.success(sessionId, message = "Compact not yet implemented")
            }
        }
    }

    override fun interrupt(sessionId: String) {
        val subscription = activeStreams.remove(sessionId)
        if (subscription != null) {
            subscription.cancel()
            log.info("Interrupted active stream for session=$sessionId")
        } else {
            log.info("No active stream to interrupt for session=$sessionId")
        }
    }

    override fun loadHistory(sessionId: String): List<MessageLog> = launcher.loadSessionMessages(sessionId)
        .flatMap { MessageLogConverter.convert(it) }

    override suspend fun initAgent(agentId: Long) {
        log.info("Initializing agent: $agentId")
        // Agent will be lazily created on first request via streamProcess
    }

    override suspend fun destroyAgent(agentId: Long) {
        log.info("Destroying agent: $agentId")
        agentCache.keys.forEach { key ->
            agentCache.remove(key)
            log.info("Removed cached agent for session=$key")
        }
    }

    /**
     * Clear session and remove cached agent.
     */
    fun clearSession(sessionId: String) {
        interrupt(sessionId)
        agentCache.remove(sessionId)
        launcher.clearSession(sessionId)
        log.info("Cleared session and agent cache for sessionId=$sessionId")
    }

    /**
     * Get or create an agent for the given sessionId.
     * Retrieves session configuration from DB and builds the agent via AscopeAgentLauncher.
     */
    private fun getOrCreateAgent(sessionId: String, userIdentifier: UserIdentifier): ReActAgentWrapper = agentCache.computeIfAbsent(sessionId) { sid ->
        val session = sessionMapper.selectBySessionIdAndStatus(sid, 1)
            ?: throw IllegalArgumentException("Session not found: $sid")

        log.info("Creating agent for session=$sid, agentId=${session.agentId}")

        val chatSpec = ChatSpecBuilder()
            .enableThinking(session.enableThink == 1)
            .enableSearch(session.enableSearch == 1)
            .enablePlan(session.enablePlan == 1)
            .build()

        val agentSpec = AgentSpec.builder()
            .id(session.agentId)
            .name(session.name)
            .description(session.description)
            .systemPrompt(session.systemPrompt)
            .chatModelId(session.modelId)
            .build()

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
}

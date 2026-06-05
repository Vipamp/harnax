package com.agnetix.harnax.agent.service.runner.impl

import com.agnetix.harnax.agent.AgentSpec
import com.agnetix.harnax.agent.AscopeAgentLauncher
import com.agnetix.harnax.agent.ChatSpecBuilder
import com.agnetix.harnax.agent.ReActAgentWrapper
import com.agnetix.harnax.agent.chat.MessageLog
import com.agnetix.harnax.agent.chat.MessageLogConverter
import com.agnetix.harnax.agent.protocol.ChatEvent
import com.agnetix.harnax.agent.provider.tool.UserIdentifier
import com.agnetix.harnax.agent.service.runner.AgentRunner
import com.agnetix.harnax.mapper.SessionMapper
import kotlinx.coroutines.reactor.awaitSingleOrNull
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

    override suspend fun process(sessionId: String, message: String, agentId: Long): String {
        log.info("Processing message for session=$sessionId, agentId=$agentId")
        val flux = streamProcess(sessionId, message, agentId)
        // Collect all stream events and extract the final text
        val events = flux.collectList().awaitSingleOrNull() ?: emptyList()
        val textContent = events
            .filterIsInstance<com.agnetix.harnax.agent.protocol.StreamTextChatEvent>()
            .joinToString("") { it.message }
        return textContent.ifEmpty { "No response generated" }
    }

    override fun streamProcess(sessionId: String, message: String, agentId: Long): Flux<ChatEvent> {
        log.info("Streaming message for session=$sessionId, agentId=$agentId: $message")
        try {
            // TODO: support dynamic UserIdentifier from request context
            val userIdentifier = UserIdentifier(0)
            val agent = getOrCreateAgent(sessionId, agentId, userIdentifier)
            return agent.callStream(message)
        } catch (e: Exception) {
            log.error("Error creating agent or streaming for session=$sessionId: ${e.message}", e)
            return Flux.error(e)
        }
    }

    override suspend fun initAgent(agentId: Long) {
        log.info("Initializing agent: $agentId")
        // Agent will be lazily created on first request via streamProcess/process
    }

    override suspend fun destroyAgent(agentId: Long) {
        log.info("Destroying agent: $agentId")
        // Remove all cached agents associated with this agentId
        // Since cache is keyed by sessionId, we remove entries whose agent was created for this agentId
        agentCache.keys.forEach { key ->
            agentCache.remove(key)
            log.info("Removed cached agent for session=$key")
        }
    }

    /**
     * Clear session and remove cached agent.
     */
    fun clearSession(sessionId: String) {
        agentCache.remove(sessionId)
        launcher.clearSession(sessionId)
        log.info("Cleared session and agent cache for sessionId=$sessionId")
    }

    /**
     * Load session messages for history display.
     */
    fun loadSessionMessages(sessionId: String): List<MessageLog> = launcher.loadSessionMessages(sessionId)
        .flatMap { MessageLogConverter.convert(it) }

    /**
     * Get or create an agent for the given sessionId.
     * Retrieves session configuration from DB and builds the agent via AscopeAgentLauncher.
     */
    private fun getOrCreateAgent(sessionId: String, agentId: Long, userIdentifier: UserIdentifier): ReActAgentWrapper = agentCache.computeIfAbsent(sessionId) { sid ->
        val session = sessionMapper.selectBySessionIdAndStatus(sid, 1)
            ?: throw IllegalArgumentException("Session not found: $sid")

        log.info("Creating agent for session=$sid, agentId=${session.agentId}")

        // Build ChatSpec from session configuration
        val chatSpec = ChatSpecBuilder()
            .enableThinking(session.enableThink == 1)
            .enableSearch(session.enableSearch == 1)
            .enablePlan(session.enablePlan == 1)
            .build()

        // Build AgentSpec from session data
        val agentSpec = AgentSpec.builder()
            .id(session.agentId ?: throw IllegalArgumentException("Session.agentId cannot be null"))
            .name(session.name ?: "Agent-${session.sessionId}")
            .description(session.description ?: "")
            .systemPrompt(session.systemPrompt ?: "")
            .chatModelId(session.modelId ?: throw IllegalArgumentException("Session.modelId cannot be null"))
            .build()

        // Use launcher to create Agent
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

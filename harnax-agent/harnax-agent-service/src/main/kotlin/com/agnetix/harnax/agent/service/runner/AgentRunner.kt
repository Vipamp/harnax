package com.agnetix.harnax.agent.service.runner

import kotlinx.coroutines.flow.Flow

/**
 * Agent runner interface.
 * Responsible for processing messages through the agent.
 */
interface AgentRunner {

    /**
     * Process a message synchronously.
     * @param sessionId Session identifier for context tracking
     * @param message User message content
     * @param agentId Agent ID to determine which agent to use
     * @return Agent response content
     */
    suspend fun process(sessionId: String, message: String, agentId: Long): String

    /**
     * Process a message with streaming output.
     * @param sessionId Session identifier for context tracking
     * @param message User message content
     * @param agentId Agent ID to determine which agent to use
     * @return Flow of streaming events
     */
    fun streamProcess(sessionId: String, message: String, agentId: Long): Flow<AgentStreamEvent>

    /**
     * Initialize an agent instance.
     * @param agentId Agent ID
     */
    suspend fun initAgent(agentId: Long)

    /**
     * Destroy an agent instance.
     * @param agentId Agent ID
     */
    suspend fun destroyAgent(agentId: Long)
}

/**
 * Sealed class representing streaming events from agent processing.
 */
sealed class AgentStreamEvent {
    data class TextStreamEvent(val content: String, val isLast: Boolean) : AgentStreamEvent()
    data class ThinkingStreamEvent(val content: String, val isLast: Boolean = false) : AgentStreamEvent()
    data class EndStreamEvent(val fullContent: String?) : AgentStreamEvent()
    data class ErrorStreamEvent(val error: String, val cause: Throwable? = null) : AgentStreamEvent()
}

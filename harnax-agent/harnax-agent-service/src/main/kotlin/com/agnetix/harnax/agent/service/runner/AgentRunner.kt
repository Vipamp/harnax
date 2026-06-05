package com.agnetix.harnax.agent.service.runner

import com.agnetix.harnax.agent.protocol.ChatEvent
import reactor.core.publisher.Flux

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
     * Returns Flux<ChatEvent> which is the unified streaming type across the entire chain.
     * @param sessionId Session identifier for context tracking
     * @param message User message content
     * @param agentId Agent ID to determine which agent to use
     * @return Flux of ChatEvent
     */
    fun streamProcess(sessionId: String, message: String, agentId: Long): Flux<ChatEvent>

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

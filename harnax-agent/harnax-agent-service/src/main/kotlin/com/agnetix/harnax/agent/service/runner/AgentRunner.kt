package com.agnetix.harnax.agent.service.runner

import com.agnetix.harnax.agent.chat.MessageLog
import com.agnetix.harnax.agent.protocol.ChatAgentRequest
import com.agnetix.harnax.agent.protocol.ChatEvent
import com.agnetix.harnax.agent.protocol.ChatResponse
import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.agent.protocol.CommandResponse
import reactor.core.publisher.Flux

/**
 * Agent runner interface.
 * Responsible for processing messages through the agent.
 *
 * Supports three output modes:
 * - streamProcess: streaming output via Flux<ChatEvent>
 * - process:       direct (non-streaming) output via ChatResponse
 * - executeCommand: command execution via CommandResponse
 */
interface AgentRunner {

    /**
     * Process a request with direct (non-streaming) output.
     * Collects all streaming events internally and returns an aggregated ChatResponse.
     * @param request ChatAgentRequest containing sessionId and message
     * @return ChatResponse with aggregated content
     */
    fun process(request: ChatAgentRequest): ChatResponse

    /**
     * Process a request with streaming output.
     * @param request ChatAgentRequest containing sessionId and message
     * @return Flux of ChatEvent
     */
    fun streamProcess(request: ChatAgentRequest): Flux<ChatEvent>

    /**
     * Execute a command request.
     * @param request CommandAgentRequest containing sessionId and command
     * @return CommandResponse with execution result
     */
    fun executeCommand(request: CommandAgentRequest): CommandResponse

    /**
     * Interrupt the ongoing stream for a session.
     * @param sessionId Session identifier
     */
    fun interrupt(sessionId: String)

    /**
     * Load historical messages for a session.
     * @param sessionId Session identifier
     * @return List of MessageLog representing the conversation history
     */
    fun loadHistory(sessionId: String): List<MessageLog>

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

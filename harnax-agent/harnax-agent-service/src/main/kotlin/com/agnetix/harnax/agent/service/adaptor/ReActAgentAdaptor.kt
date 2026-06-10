package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.agent.*
import com.agnetix.harnax.agent.protocol.ChatEvent
import com.agnetix.harnax.agent.protocol.EndEventChatEvent
import com.agnetix.harnax.agent.protocol.ErrorChatEvent
import com.agnetix.harnax.agent.protocol.StreamTextChatEvent
import com.agnetix.harnax.agent.protocol.StreamThinkingChatEvent
import com.agnetix.harnax.agent.provider.tool.UserIdentifier
import com.agnetix.harnax.channel.sdk.adaptor.AgentAdaptor
import com.agnetix.harnax.channel.sdk.adaptor.AgentContext
import com.agnetix.harnax.channel.sdk.adaptor.AgentResponse
import com.agnetix.harnax.channel.sdk.adaptor.AgentStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.reactive.asFlow
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * ReAct Agent Adaptor
 *
 * Bridges the channel SDK's AgentAdaptor with harnax-agent's AscopeAgentLauncher,
 * enabling WeChat/Feishu/DingTalk channels to use the ReAct agent for AI conversation.
 *
 * Core responsibilities:
 * - Create ReActAgent instances based on channel configuration (agentId from ChannelSpec)
 * - Convert ChatEvent (from harnax-agent) to AgentStreamEvent (from channel SDK)
 * - Support both batch (process) and streaming (streamProcess) modes
 *
 * Streaming conversion:
 * - AscopeAgentLauncher returns Flux<ChatEvent> (Reactor)
 * - Channel SDK uses Flow<AgentStreamEvent> (Kotlin Coroutines)
 * - kotlinx-coroutines-reactor bridges the two via .asFlow() / .asFlux()
 *
 * Session mapping:
 * - Channel sessionId (e.g., WeChat openId) is mapped to agent sessionId
 * - This allows maintaining conversation context across channel and agent layers
 */
@Service
class ReActAgentAdaptor(
    private val launcher: AscopeAgentLauncher,
) : AgentAdaptor() {

    private val logger = LoggerFactory.getLogger(ReActAgentAdaptor::class.java)

    override fun getName(): String = "react-agent"

    override fun supportsStreaming(): Boolean = true

    /**
     * Batch process - collects all stream events and returns merged response
     *
     * Used when the channel doesn't support streaming output.
     */
    override suspend fun process(context: AgentContext): AgentResponse {
        val agentWrapper = createAgent(context)
        val userMessage = context.message.content

        val fullText = agentWrapper.callStream(userMessage)
            .filter { event -> event is StreamTextChatEvent }
            .map { event -> (event as StreamTextChatEvent).message }
            .collectList()
            .block()!!
            .joinToString("")

        return AgentResponse(
            content = fullText,
            shouldReply = true,
        )
    }

    /**
     * Streaming process - returns Flow of AgentStreamEvent
     *
     * Used when the channel supports streaming output (e.g., HTTP SSE).
     * Converts Flux<ChatEvent> to Flow<AgentStreamEvent> using kotlinx-coroutines-reactor.
     */
    override fun streamProcess(context: AgentContext): Flow<AgentStreamEvent> = flow {
        val agentWrapper = createAgent(context)
        val userMessage = context.message.content
        val requestId = context.requestId

        agentWrapper.callStream(userMessage)
            .asFlow()
            .collect { chatEvent: ChatEvent ->
                val streamEvent = convertChatEvent(chatEvent, requestId)
                if (streamEvent != null) {
                    emit(streamEvent)
                }
            }
    }

    /**
     * Create ReActAgent based on channel configuration
     *
     * Uses ChannelSpec.agentId to build AgentSpec.
     * For channel conversations, we use a simplified configuration:
     * - Default ChatSpec (no thinking/search/plan enabled)
     * - Session ID derived from channel sessionId for conversation continuity
     */
    private fun createAgent(context: AgentContext): ReActAgentWrapper {
        val channelSpec = context.channelSpec
        val channelSessionId = context.message.sessionId

        // Use agentId from ChannelSpec to configure the agent
        // In a full implementation, this would look up agent configuration from the database
        val agentId = channelSpec.agentId ?: 1L

        // Build simplified AgentSpec for channel conversations
        val agentSpec = AgentSpec.builder()
            .id(agentId)
            .name("ChannelAgent-$agentId")
            .description("AI Agent for channel conversations")
            .systemPrompt("You are a helpful AI assistant. Respond to user messages naturally and concisely.")
            .chatModelId(agentId)
            .build()

        // Use default ChatSpec for channel conversations
        val chatSpec = ChatSpec.builder().build()

        // Create user identifier from channel context
        val userIdentifier = UserIdentifier(context.message.senderId?.let { 0 } ?: 0)

        return launcher.createSingleAgent(
            agentSpec = agentSpec,
            sessionId = "channel-${channelSpec.id}-$channelSessionId",
            stateless = false,
            chatSpec,
            userIdentifier,
        )
    }

    /**
     * Convert ChatEvent (from harnax-agent) to AgentStreamEvent (from channel SDK)
     *
     * Only relevant events are converted:
     * - StreamTextChatEvent → TextStreamEvent
     * - StreamThinkingChatEvent → ThinkingStreamEvent
     * - EndEventChatEvent → EndStreamEvent
     * - Other events (ToolConfirm, CallTool, ToolResult) are filtered out for channels
     */
    private fun convertChatEvent(event: ChatEvent, requestId: String): AgentStreamEvent? = when (event) {
        is StreamTextChatEvent -> AgentStreamEvent.TextStreamEvent(
            content = event.message,
            isLast = event.isLast,
        )
        is StreamThinkingChatEvent -> AgentStreamEvent.ThinkingStreamEvent(
            content = event.message,
            isLast = event.isLast,
        )
        is EndEventChatEvent -> AgentStreamEvent.EndStreamEvent()
        is ErrorChatEvent -> AgentStreamEvent.ErrorStreamEvent(
            code = event.code,
            message = event.message,
            requestId = requestId,
        )
        else -> null // Tool events are not relevant for channel output
    }
}

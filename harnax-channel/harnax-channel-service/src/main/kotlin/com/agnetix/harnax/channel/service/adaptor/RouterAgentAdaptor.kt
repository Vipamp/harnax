package com.agnetix.harnax.channel.service.adaptor

import com.agnetix.harnax.agent.protocol.*
import com.agnetix.harnax.channel.sdk.adaptor.AgentAdaptor
import com.agnetix.harnax.channel.sdk.adaptor.AgentContext
import com.agnetix.harnax.channel.sdk.adaptor.AgentResponse
import com.agnetix.harnax.channel.sdk.adaptor.AgentStreamEvent
import com.agnetix.harnax.channel.sdk.adaptor.PendingToolInfo
import com.agnetix.harnax.channel.service.client.RouterClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory

/**
 * Router-based Agent Adaptor.
 * Bridges the RouterClient (which communicates with agent-service via session-router)
 * to the SDK's AgentAdaptor interface, enabling reuse of ChannelChatService orchestration.
 *
 * This adaptor delegates all agent processing to the remote agent-service instances
 * accessed through the session-router proxy.
 */
class RouterAgentAdaptor(
    private val routerClient: RouterClient,
) : AgentAdaptor() {

    private val log = LoggerFactory.getLogger(RouterAgentAdaptor::class.java)

    override fun getName(): String = "router-agent-proxy"

    override suspend fun process(context: AgentContext): AgentResponse {
        val agentId = context.channelSpec.agentId
        val sessionId = context.channelSpec.sessionId

        log.info("[Adaptor] Starting batch process() for session=$sessionId, agentId=$agentId")

        // Use parsed AgentRequest if available, otherwise build from message content
        val request = context.agentRequest ?: ChatAgentRequest(
            sessionId = sessionId,
            message = context.message.content,
        )
        log.info("[Adaptor] Request type=${request.javaClass.simpleName} for session=$sessionId")

        return when (request) {
            is ChatAgentRequest -> {
                val chatResponse = routerClient.sendToAgent(
                    sessionId = sessionId,
                    agentId = agentId,
                    message = request.message,
                    imageUrls = request.imageUrls,
                )
                if (chatResponse.content.isBlank()) {
                    log.warn("[Adaptor] Empty response from router for session=$sessionId — agent may have failed silently")
                } else if (chatResponse.content.startsWith("[Router Error]")) {
                    log.warn("[Adaptor] Router error response for session=$sessionId: {}", chatResponse.content)
                } else {
                    log.info("[Adaptor] Batch process completed for session=$sessionId, responseLength={}", chatResponse.content.length)
                }
                AgentResponse(content = chatResponse.content, shouldReply = true, attachments = chatResponse.attachments)
            }
            is CommandAgentRequest -> {
                val commandResponse = routerClient.sendCommand(
                    sessionId = sessionId,
                    agentId = agentId,
                    command = request.command,
                    args = request.args,
                )
                AgentResponse(
                    content = commandResponse.message ?: "Command executed",
                    shouldReply = true,
                )
            }
            else -> {
                log.warn("Unknown request type: ${request.javaClass.simpleName}, treating as chat")
                val chatResponse = routerClient.sendToAgent(
                    sessionId = sessionId,
                    agentId = agentId,
                    message = context.message.content,
                )
                AgentResponse(content = chatResponse.content, shouldReply = true, attachments = chatResponse.attachments)
            }
        }
    }

    override fun supportsStreaming(): Boolean = true

    override fun streamProcess(context: AgentContext): Flow<AgentStreamEvent> = flow {
        val sessionId = context.channelSpec.sessionId
        val agentId = context.channelSpec.agentId
        val requestId = context.requestId

        log.info("[Adaptor] Starting streamProcess for session=$sessionId, agentId=$agentId, requestId=$requestId")

        // Use parsed AgentRequest if available, otherwise fall back to chat with message content
        val request = context.agentRequest ?: ChatAgentRequest(
            sessionId = sessionId,
            message = context.message.content,
        )

        var eventCount = 0
        val collector: FlowCollector<AgentStreamEvent> = this
        routerClient.streamRequest(
            request = request,
            agentId = agentId,
        ).collect { chatEvent: ChatEvent ->
            val streamEvent = convertChatEvent(chatEvent, requestId)
            if (streamEvent != null) {
                log.debug("[Adaptor] Converting event #$eventCount for session=$sessionId: ${chatEvent.javaClass.simpleName} -> ${streamEvent.javaClass.simpleName}")
                eventCount++
                collector.emit(streamEvent)
            } else {
                log.debug("[Adaptor] Skipped null conversion for event: ${chatEvent.javaClass.simpleName}")
            }
        }
        log.info("[Adaptor] StreamProcess completed for session=$sessionId, total events=$eventCount")
    }

    /**
     * Convert ChatEvent (from harnax-protocol) to AgentStreamEvent (from channel SDK)
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
        is ToolConfirmChatEvent -> AgentStreamEvent.ToolConfirmStreamEvent(
            pendingTools = event.pendingCallTools.map {
                PendingToolInfo(it.toolId, it.toolName, it.arguments, it.isDangerous)
            },
        )
        else -> null // Other events not relevant for channel output
    }
}

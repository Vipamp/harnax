package com.agnetix.harnax.channel.service.adaptor

import com.agnetix.harnax.channel.sdk.adaptor.AgentAdaptor
import com.agnetix.harnax.channel.sdk.adaptor.AgentContext
import com.agnetix.harnax.channel.sdk.adaptor.AgentResponse
import com.agnetix.harnax.channel.sdk.adaptor.AgentStreamEvent
import com.agnetix.harnax.channel.service.client.RouterClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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

    override fun getName(): String = "router-agent-proxy"

    override suspend fun process(context: AgentContext): AgentResponse {
        val agentId = context.channelSpec.agentId
        val responseContent = routerClient.sendToAgent(
            sessionId = context.message.sessionId,
            agentId = agentId,
            message = context.message.content,
        )
        return AgentResponse(content = responseContent, shouldReply = true)
    }

    override fun supportsStreaming(): Boolean = true

    override fun streamProcess(context: AgentContext): Flow<AgentStreamEvent> = flow {
        val agentId = context.channelSpec.agentId

        routerClient.streamToAgent(
            sessionId = context.message.sessionId,
            agentId = agentId,
            message = context.message.content,
        ).collect { eventContent ->
            emit(AgentStreamEvent.TextStreamEvent(eventContent, false))
        }

        emit(AgentStreamEvent.EndStreamEvent())
    }
}

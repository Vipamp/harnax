package com.agnetix.harnax.agent.service.chat

import com.agnetix.harnax.agent.AgentSpec
import com.agnetix.harnax.agent.ChatSpec
import com.agnetix.harnax.agent.ChatSpecBuilder
import com.agnetix.harnax.agent.adaptor.PlanNote
import com.agnetix.harnax.agent.chat.MessageLog
import com.agnetix.harnax.agent.chat.MessageLogConverter
import com.agnetix.harnax.agent.protocol.ChatEvent
import com.agnetix.harnax.agent.provider.tool.UserIdentifier
import com.agnetix.harnax.agent.service.chat.dto.ChatRequest
import com.agnetix.harnax.agent.service.chat.dto.ConfirmRequest
import com.agnetix.harnax.agent.service.chat.dto.SessionConfigResponse
import com.agnetix.harnax.agent.service.chat.dto.SessionConfigUpdateRequest
import com.agnetix.harnax.harness.HarnessAgentLauncher
import com.agnetix.harnax.harness.HarnessAgentWrapper
import com.agnetix.harnax.mapper.SessionMapper
import com.github.benmanes.caffeine.cache.Caffeine
import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import io.agentscope.core.message.TextBlock
import io.agentscope.core.message.ToolResultBlock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux

/**
 * @Description: ChatService
 */
@Service
class ChatService(
    private val launcher: HarnessAgentLauncher,
    private val sessionMapper: SessionMapper,
    @Value($$"${agent.cache.max-size:500}")
    private val cacheMaxSize: Long,
) {

    private val log = LoggerFactory.getLogger(ChatService::class.java)
    private val agentCache = Caffeine.newBuilder()
        .maximumSize(cacheMaxSize)
        .removalListener<String, HarnessAgentWrapper> { key, _, cause ->
            log.info("Agent evicted from cache: session=$key, cause=$cause")
        }
        .build<String, HarnessAgentWrapper>()

    fun chat(request: ChatRequest): Flux<ChatEvent> {
        try {
            val userIdentifier = UserIdentifier(0)
            val chatSpec = ChatSpecBuilder()
                .enableThinking(request.enableThink)
                .enableSearch(request.enableSearch)
                .enablePlan(request.enablePlan)
                .build()
            return getOrCreateAgent(request.sessionId, chatSpec, userIdentifier)
                .callStream(request.message, request.imageUrl)
        } catch (e: Exception) {
            log.error("Error creating agent or streaming text: ${e.message}")
            return Flux.error { e }
        }
    }

    fun confirm(confirmRequest: ConfirmRequest): Flux<ChatEvent> {
        val chatSpec =
            ChatSpec.builder().enableThinking(confirmRequest.enableThink).enableSearch(confirmRequest.enableSearch)
                .build()
        val userIdentifier = UserIdentifier(0)
        val agent = getOrCreateAgent(confirmRequest.sessionId, chatSpec, userIdentifier)
        if (confirmRequest.isConfirmed) {
            return agent.callStream()
        } else {
            val results: MutableList<ToolResultBlock?> = ArrayList()
            val cancelMessage = "Operation cancelled by user"
            for (tool in confirmRequest.toolInfoList) {
                results.add(
                    ToolResultBlock.of(
                        tool.toolId,
                        tool.toolName,
                        TextBlock.builder().text(cancelMessage).build(),
                    ),
                )
            }
            val cancelResult =
                Msg.builder()
                    .name("Assistant").role(MsgRole.TOOL)
                    .content(*results.toTypedArray<ToolResultBlock?>()).build()
            return agent.callStream(msg = cancelResult)
        }
    }

    /**
     * Get or create an agent for the given sessionId.
     * Caches the agent so subsequent calls reuse the same instance.
     */
    private fun getOrCreateAgent(sessionId: String, chatSpec: ChatSpec, userIdentifier: UserIdentifier): HarnessAgentWrapper = agentCache.get(sessionId) { sid ->
        val session = sessionMapper.selectBySessionIdAndStatus(sid, 1)
            ?: throw IllegalArgumentException("Session not found: $sid")

        log.info("Creating agent for session: ${session.sessionId}, agentId: ${session.agentId}")

        val agentSpec = AgentSpec.builder()
            .id(session.agentId ?: throw IllegalArgumentException("Session.agentId cannot be null"))
            .name(session.name ?: "Agent-${session.sessionId}")
            .description(session.description ?: "")
            .systemPrompt(session.systemPrompt ?: "")
            .chatModelId(session.modelId ?: throw IllegalArgumentException("Session.modelId cannot be null"))
            .build()

        val agent = launcher.createSingleAgent(
            agentSpec = agentSpec,
            sessionId = sid,
            stateless = false,
            chatSpec,
            userIdentifier,
        )

        log.info("Agent for session $sid created and cached successfully")
        agent
    }

    fun clearSession(sessionId: String) {
        agentCache.invalidate(sessionId)
        launcher.clearSession(sessionId)
    }

    fun loadSessionMessages(sessionId: String): List<MessageLog> = launcher.loadSessionMessages(sessionId)
        .flatMap { MessageLogConverter.convert(it) }

    fun loadSessionHistoryPlan(sessionId: String): List<PlanNote> = launcher.loadSessionHistoryPlan(sessionId)

    fun loadSessionCurrentPlanNote(sessionId: String): PlanNote? = launcher.loadSessionCurrentPlanNote(sessionId)

    /**
     * Get chat configuration for session
     */
    fun getSessionConfig(sessionId: String): SessionConfigResponse {
        val session = sessionMapper.selectBySessionIdAndStatus(sessionId, 1)
            ?: throw IllegalArgumentException("Session not found: $sessionId")

        return SessionConfigResponse(
            sessionId = sessionId,
            enableThink = session.enableThink == 1,
            enableSearch = session.enableSearch == 1,
            enablePlan = session.enablePlan == 1,
        )
    }

    /**
     * Update chat configuration for session
     */
    fun updateSessionConfig(sessionId: String, request: SessionConfigUpdateRequest) {
        val session = sessionMapper.selectBySessionIdAndStatus(sessionId, 1)
            ?: throw IllegalArgumentException("Session not found: $sessionId")

        session.enableThink = if (request.enableThink) 1 else 0
        session.enableSearch = if (request.enableSearch) 1 else 0
        session.enablePlan = if (request.enablePlan) 1 else 0

        sessionMapper.updateById(session)
        log.info("Session config updated for sessionId: $sessionId")
    }
}

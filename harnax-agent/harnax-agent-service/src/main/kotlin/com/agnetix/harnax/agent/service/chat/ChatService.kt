package com.agnetix.harnax.agent.service.chat

import com.agnetix.harnax.agent.ChatSpec
import com.agnetix.harnax.agent.ChatSpecBuilder
import com.agnetix.harnax.agent.adaptor.PlanNote
import com.agnetix.harnax.agent.chat.MessageLog
import com.agnetix.harnax.agent.chat.MessageLogConverter
import com.agnetix.harnax.agent.protocol.ChatEvent
import com.agnetix.harnax.agent.protocol.EndEventChatEvent
import com.agnetix.harnax.agent.protocol.ErrorChatEvent
import com.agnetix.harnax.agent.provider.tool.UserIdentifier
import com.agnetix.harnax.agent.service.chat.dto.ChatRequest
import com.agnetix.harnax.agent.service.chat.dto.ConfirmRequest
import com.agnetix.harnax.agent.service.chat.dto.SessionConfigResponse
import com.agnetix.harnax.agent.service.chat.dto.SessionConfigUpdateRequest
import com.agnetix.harnax.agent.service.runner.AgentSpecResolver
import com.agnetix.harnax.common.error.HarnaxErrorCode
import com.agnetix.harnax.harness.HarnessAgentLauncher
import com.agnetix.harnax.harness.HarnessAgentWrapper
import com.agnetix.harnax.mapper.SessionMapper
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * @Description: ChatService
 */
@Service
class ChatService(
    private val launcher: HarnessAgentLauncher,
    private val sessionMapper: SessionMapper,
    private val agentSpecResolver: AgentSpecResolver,
    @Value($$"${agent.cache.max-size:500}")
    private val cacheMaxSize: Long,
) {

    private val log = LoggerFactory.getLogger(ChatService::class.java)
    private val agentCache = Caffeine.newBuilder()
        .maximumSize(cacheMaxSize)
        .expireAfterWrite(30, TimeUnit.MINUTES)
        .removalListener<String, HarnessAgentWrapper> { key, _, cause ->
            log.info("Agent evicted from cache: session=$key, cause=$cause")
        }
        .build<String, HarnessAgentWrapper>()

    private val activeStreams = ConcurrentHashMap<String, Subscription>()

    fun chat(request: ChatRequest): Flux<ChatEvent> {
        try {
            // TODO [P1] UserIdentifier(0) is hardcoded — all requests share userId=0.
            //   Should extract real user ID from request context (e.g. SecurityContext or request header).
            val userIdentifier = UserIdentifier(0)
            val chatSpec = ChatSpecBuilder()
                .enableThinking(request.enableThink)
                .enableSearch(request.enableSearch)
                .enablePlan(request.enablePlan)
                .build()
            return getOrCreateAgent(request.sessionId, chatSpec, userIdentifier)
                .callStream(request.message, request.imageUrl)
        } catch (e: Exception) {
            log.error("Error creating agent or streaming text for session=${request.sessionId}: ${e.message}", e)
            return Flux.just(
                ErrorChatEvent(
                    code = HarnaxErrorCode.AGENT_INIT_FAILED.code,
                    message = e.message ?: "Agent call failed",
                ),
                EndEventChatEvent(),
            )
        }
    }

    fun confirm(confirmRequest: ConfirmRequest): Flux<ChatEvent> {
        val sessionId = confirmRequest.sessionId
        val chatSpec =
            ChatSpec.builder().enableThinking(confirmRequest.enableThink).enableSearch(confirmRequest.enableSearch)
                .build()
        // TODO [P1] UserIdentifier(0) is hardcoded — see chat() for details.
        val userIdentifier = UserIdentifier(0)
        val agent = getOrCreateAgent(sessionId, chatSpec, userIdentifier)
        val stream = if (confirmRequest.isConfirmed) {
            agent.callStream()
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
            agent.callStream(msg = cancelResult)
        }
        return stream
            .doOnSubscribe { subscription ->
                activeStreams[sessionId] = subscription
            }
            .doFinally {
                activeStreams.remove(sessionId)
            }
    }

    /**
     * Route agent creation based on sessionId prefix.
     * Delegates spec resolution to AgentSpecResolver (which calls Admin).
     */
    private fun getOrCreateAgent(sessionId: String, chatSpec: ChatSpec, userIdentifier: UserIdentifier): HarnessAgentWrapper = agentCache.get(sessionId) { sid ->
        log.info("Resolving agent spec for sessionId=$sid")
        val (agentSpec, _) = agentSpecResolver.resolve(sid)
        val agent = launcher.createSingleAgent(
            agentSpec = agentSpec,
            sessionId = sid,
            stateless = false,
            chatSpec,
            userIdentifier,
        )
        log.info("Agent for session=$sid created and cached successfully")
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
     * Get chat configuration for session.
     * Only web/mp sessions have per-session config in the session table.
     * For chn-/task- sessions, return defaults (all disabled).
     */
    fun getSessionConfig(sessionId: String): SessionConfigResponse {
        if (sessionId.startsWith("chn-") || sessionId.startsWith("task-")) {
            return SessionConfigResponse(
                sessionId = sessionId,
                enableThink = false,
                enableSearch = false,
                enablePlan = false,
            )
        }
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
     * Update chat configuration for session.
     * Only web/mp sessions have per-session config in the session table.
     * For chn-/task- sessions, this is a no-op.
     */
    fun updateSessionConfig(sessionId: String, request: SessionConfigUpdateRequest) {
        if (sessionId.startsWith("chn-") || sessionId.startsWith("task-")) {
            log.info("Skipping session config update for non-session-table sessionId: $sessionId")
            return
        }
        val session = sessionMapper.selectBySessionIdAndStatus(sessionId, 1)
            ?: throw IllegalArgumentException("Session not found: $sessionId")

        session.enableThink = if (request.enableThink) 1 else 0
        session.enableSearch = if (request.enableSearch) 1 else 0
        session.enablePlan = if (request.enablePlan) 1 else 0

        sessionMapper.updateById(session)
        log.info("Session config updated for sessionId: $sessionId")
    }
}

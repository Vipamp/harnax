package com.vipamp.vipclaw.ascopagent

import com.vipamp.vipclaw.admin.mapper.SessionMapper
import com.vipamp.vipclaw.agent.*
import com.vipamp.vipclaw.agent.adaptor.PlanNote
import com.vipamp.vipclaw.agent.chat.ChatEvent
import com.vipamp.vipclaw.agent.chat.MessageLog
import com.vipamp.vipclaw.agent.chat.MessageLogConverter
import com.vipamp.vipclaw.agent.provider.tool.UserIdentifier
import com.vipamp.vipclaw.ascopagent.dto.ChatRequest
import com.vipamp.vipclaw.ascopagent.dto.ConfirmRequest
import com.vipamp.vipclaw.ascopagent.dto.SessionConfigResponse
import com.vipamp.vipclaw.ascopagent.dto.SessionConfigUpdateRequest
import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import io.agentscope.core.message.TextBlock
import io.agentscope.core.message.ToolResultBlock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux

/**
 * @Author: heqingsong
 * @Date: 2026/4/12
 * @Description: ChatService
 * @Project: vipclaw
 */
@Service
class ChatService(
    private val launcher: AscopeAgentLauncher,
    private val sessionMapper: SessionMapper,
) {

    private val log = LoggerFactory.getLogger(ChatService::class.java)

    fun chat(request: ChatRequest): Flux<ChatEvent> {
        try {
            // TODO
            val userIdentifier = UserIdentifier(0)
            val chatSpec = ChatSpecBuilder()
                .enableThinking(request.enableThink)
                .enableSearch(request.enableSearch)
                .enablePlan(request.enablePlan)
                .build()
            return createAgent(request.sessionId, chatSpec, userIdentifier)
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
        // TODO
        val userIdentifier = UserIdentifier(0)
        val agent = createAgent(confirmRequest.sessionId, chatSpec, userIdentifier)
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
     * 根据 sessionId 从数据库获取 Session 信息，然后创建 Agent
     */
    private fun createAgent(sessionId: String, chatSpec: ChatSpec, userIdentifier: UserIdentifier): ReActAgentWrapper {
        val session = sessionMapper.selectBySessionIdAndStatus(sessionId, 1)
            ?: throw IllegalArgumentException("Session not found: $sessionId")

        log.info("Creating agent for session: ${session.sessionId}, agentId: ${session.agentId}")

        // 构建 AgentSpec
        val agentSpec = AgentSpec.builder()
            .id(session.agentId ?: throw IllegalArgumentException("Session.agentId cannot be null"))
            .name(session.name ?: "Agent-${session.sessionId}")
            .description(session.description ?: "")
            .systemPrompt(session.systemPrompt ?: "")
            .chatModelId(session.modelId ?: throw IllegalArgumentException("Session.modelId cannot be null"))
            .build()

        // 使用 launcher 创建 Agent
        val agent = launcher.createSingleAgent(
            agentSpec = agentSpec,
            sessionId = sessionId,
            stateless = false,
            chatSpec,
            userIdentifier,
        )

        log.info("Agent for session $sessionId created and cached successfully")

        return agent
    }

    fun clearSession(sessionId: String) {
        launcher.clearSession(sessionId)
    }

    fun loadSessionMessages(sessionId: String): List<MessageLog> = launcher.loadSessionMessages(sessionId)
        .flatMap { MessageLogConverter.convert(it) }

    fun loadSessionHistoryPlan(sessionId: String): List<PlanNote> = launcher.loadSessionHistoryPlan(sessionId)

    fun loadSessionCurrentPlanNote(sessionId: String): PlanNote? = launcher.loadSessionCurrentPlanNote(sessionId)

    /**
     * 获取会话的聊天配置
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
     * 更新会话的聊天配置
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

package com.vipamp.vipclaw.agent.service

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper
import com.vipamp.vipclaw.agent.*
import com.vipamp.vipclaw.agent.chat.ChatEvent
import com.vipamp.vipclaw.agent.chat.MessageLog
import com.vipamp.vipclaw.agent.service.dto.ChatRequest
import com.vipamp.vipclaw.agent.service.dto.ConfirmRequest
import com.vipamp.vipclaw.common.entity.Session
import com.vipamp.vipclaw.common.log.logger
import com.vipamp.vipclaw.common.mapper.SessionMapper
import lombok.RequiredArgsConstructor
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux

/**
 * @Author: heqingsong
 * @Date: 2026/4/12
 * @Description: ChatService
 * @Project: vipclaw
 */
@Service
@RequiredArgsConstructor
class ChatService(
    private val launcher: AscopeAgentLauncher,
    private val sessionMapper: SessionMapper
) {

    fun chat(request: ChatRequest): Flux<ChatEvent> {
        try {
            val chatSpec = ChatSpecBuilder()
                .enableThinking(request.enableThink)
                .enableSearch(request.enableSearch)
                .build()
            return createAgent(request.sessionId, chatSpec)
                .streamTextAll(request.message)
        } catch (e: Exception) {
            logger().error("Error creating agent or streaming text: ${e.message}")
            return Flux.error { e }
        }
    }

    fun confirm(request: ConfirmRequest): Flux<ChatEvent> {
        return Flux.empty()
    }

    /**
     * 根据 sessionId 从数据库获取 Session 信息，然后创建 Agent
     */
    private fun createAgent(sessionId: String, chatSpec: ChatSpec): ReActAgentWrapper {
        val queryWrapper = LambdaQueryWrapper<Session>()
            .eq(Session::getSessionId, sessionId)
            .eq(Session::getActive, 1)

        val session = sessionMapper.selectOne(queryWrapper)
            ?: throw IllegalArgumentException("Session not found: $sessionId")

        logger().info("Creating agent for session: ${session.sessionId}, agentId: ${session.agentId}")

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
            chatSpec
        )

        logger().info("Agent for session $sessionId created and cached successfully")

        return agent
    }

    fun clearSession(sessionId: String) {

    }

    fun loadSessionMessages(sessionId: String): List<MessageLog> {
        return emptyList()
    }
}

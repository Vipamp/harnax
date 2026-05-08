package com.vipamp.vipclaw.channel.session

import com.vipamp.vipclaw.channel.message.ChannelMessage
import com.vipamp.vipclaw.channel.message.MessageRole

/**
 * Channel 会话管理接口
 * 用于管理多轮对话的历史消息
 */
interface ChannelSessionManager {

    /**
     * 获取会话的历史消息
     * @param channelId Channel ID
     * @param sessionId 会话标识
     * @param limit 限制返回的消息数量
     * @return 历史消息列表
     */
    suspend fun getHistory(channelId: Long, sessionId: String, limit: Int = 20): List<ChannelMessage>

    /**
     * 添加消息到会话
     * @param channelId Channel ID
     * @param message 消息对象
     */
    suspend fun addMessage(channelId: Long, message: ChannelMessage)

    /**
     * 清除会话历史
     * @param channelId Channel ID
     * @param sessionId 会话标识
     */
    suspend fun clearHistory(channelId: Long, sessionId: String)

    /**
     * 将历史消息转换为 Agent 可用的格式
     * @param messages 历史消息列表
     * @return Agent 可用的消息列表
     */
    fun toAgentMessages(messages: List<ChannelMessage>): List<AgentMessage> = messages.map { msg ->
        AgentMessage(
            role = when (msg.role) {
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "assistant"
                MessageRole.SYSTEM -> "system"
            },
            content = msg.content,
        )
    }
}

/**
 * Agent 消息格式
 */
data class AgentMessage(
    val role: String,
    val content: String,
)

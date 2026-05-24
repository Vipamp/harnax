package com.agnetix.harnax.channel.sdk.session

import com.agnetix.harnax.channel.sdk.message.AgentMessage
import com.agnetix.harnax.channel.sdk.message.ChannelMessage
import com.agnetix.harnax.channel.sdk.message.MessageRole

/**
 * Channel Session Management Interface
 * Used to manage multi-turn conversation history
 *
 * This interface is a platform-agnostic abstraction at the SDK level,
 * specific implementations can be based on memory, database, Redis, etc.
 */
interface ChannelSessionManager {

    /**
     * Get session history messages
     * @param channelId Channel ID
     * @param sessionId Session identifier
     * @param limit Limit number of returned messages
     * @return History message list
     */
    suspend fun getHistory(channelId: Long, sessionId: String, limit: Int = 20): List<ChannelMessage>

    /**
     * Add message to session
     * @param channelId Channel ID
     * @param message Message object
     */
    suspend fun addMessage(channelId: Long, message: ChannelMessage)

    /**
     * Clear session history
     * @param channelId Channel ID
     * @param sessionId Session identifier
     */
    suspend fun clearHistory(channelId: Long, sessionId: String)

    /**
     * Convert history messages to Agent-compatible format
     * @param messages History message list
     * @return Agent-compatible message list
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

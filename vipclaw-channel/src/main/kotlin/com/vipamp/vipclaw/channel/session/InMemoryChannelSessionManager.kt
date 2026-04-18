package com.vipamp.vipclaw.channel.session

import com.vipamp.vipclaw.channel.message.ChannelMessage
import java.util.concurrent.ConcurrentHashMap

/**
 * 内存版 Channel 会话管理器
 * 用于演示和测试，消息存储在内存中
 */
class InMemoryChannelSessionManager : ChannelSessionManager {
    
    // channelId -> (sessionId -> messages)
    private val sessions: ConcurrentHashMap<Long, ConcurrentHashMap<String, MutableList<ChannelMessage>>> = ConcurrentHashMap()
    
    override suspend fun getHistory(channelId: Long, sessionId: String, limit: Int): List<ChannelMessage> {
        val channelSessions = sessions[channelId] ?: return emptyList()
        val messages = channelSessions[sessionId] ?: return emptyList()
        return messages.takeLast(limit)
    }
    
    override suspend fun addMessage(channelId: Long, message: ChannelMessage) {
        val channelSessions = sessions.computeIfAbsent(channelId) { ConcurrentHashMap() }
        val messages = channelSessions.computeIfAbsent(message.sessionId) { mutableListOf() }
        messages.add(message)
    }
    
    override suspend fun clearHistory(channelId: Long, sessionId: String) {
        sessions[channelId]?.remove(sessionId)
    }
    
    /**
     * 获取会话数量统计
     */
    fun getStats(): Map<String, Any> {
        var totalChannels = 0
        var totalSessions = 0
        var totalMessages = 0
        
        sessions.forEach { (_, channelSessions) ->
            totalChannels++
            channelSessions.forEach { (_, messages) ->
                totalSessions++
                totalMessages += messages.size
            }
        }
        
        return mapOf(
            "channels" to totalChannels,
            "sessions" to totalSessions,
            "messages" to totalMessages
        )
    }
}

package com.vipamp.vipclaw.channel.message

import com.vipamp.vipclaw.channel.ChannelType

/**
 * 统一消息模型
 * 用于在不同平台间统一消息格式
 */
data class ChannelMessage(
    /**
     * 消息 ID
     */
    val messageId: String? = null,
    
    /**
     * 会话 ID（用户 ID 或群组 ID）
     */
    val sessionId: String,
    
    /**
     * 消息类型
     */
    val messageType: MessageType = MessageType.TEXT,
    
    /**
     * 消息角色
     */
    val role: MessageRole = MessageRole.USER,
    
    /**
     * 消息内容
     */
    val content: String,
    
    /**
     * 平台类型
     */
    val channelType: ChannelType,
    
    /**
     * 发送者名称
     */
    val senderName: String? = null,
    
    /**
     * 发送者 ID
     */
    val senderId: String? = null,
    
    /**
     * 是否是群聊消息
     */
    val isGroupMessage: Boolean = false,
    
    /**
     * 群组 ID（如果是群聊）
     */
    val groupId: String? = null,
    
    /**
     * @ 用户列表
     */
    val atUserIds: List<String> = emptyList(),
    
    /**
     * 原始消息内容（平台特定格式）
     */
    val rawContent: Any? = null,
    
    /**
     * 时间戳
     */
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        @JvmStatic
        fun builder() = ChannelMessageBuilder()
    }
}

class ChannelMessageBuilder {
    private var messageId: String? = null
    private var sessionId: String = ""
    private var messageType: MessageType = MessageType.TEXT
    private var role: MessageRole = MessageRole.USER
    private var content: String = ""
    private var channelType: ChannelType = ChannelType.HTTP
    private var senderName: String? = null
    private var senderId: String? = null
    private var isGroupMessage: Boolean = false
    private var groupId: String? = null
    private var atUserIds: List<String> = emptyList()
    private var rawContent: Any? = null
    private var timestamp: Long = System.currentTimeMillis()

    fun messageId(messageId: String?) = apply { this.messageId = messageId }
    fun sessionId(sessionId: String) = apply { this.sessionId = sessionId }
    fun messageType(messageType: MessageType) = apply { this.messageType = messageType }
    fun role(role: MessageRole) = apply { this.role = role }
    fun content(content: String) = apply { this.content = content }
    fun channelType(channelType: ChannelType) = apply { this.channelType = channelType }
    fun senderName(senderName: String?) = apply { this.senderName = senderName }
    fun senderId(senderId: String?) = apply { this.senderId = senderId }
    fun isGroupMessage(isGroupMessage: Boolean) = apply { this.isGroupMessage = isGroupMessage }
    fun groupId(groupId: String?) = apply { this.groupId = groupId }
    fun atUserIds(atUserIds: List<String>) = apply { this.atUserIds = atUserIds }
    fun rawContent(rawContent: Any?) = apply { this.rawContent = rawContent }
    fun timestamp(timestamp: Long) = apply { this.timestamp = timestamp }

    fun build() = ChannelMessage(
        messageId = messageId,
        sessionId = sessionId,
        messageType = messageType,
        role = role,
        content = content,
        channelType = channelType,
        senderName = senderName,
        senderId = senderId,
        isGroupMessage = isGroupMessage,
        groupId = groupId,
        atUserIds = atUserIds,
        rawContent = rawContent,
        timestamp = timestamp
    )
}

/**
 * 消息类型
 */
enum class MessageType {
    TEXT,       // 文本消息
    MARKDOWN,   // Markdown 格式消息
    IMAGE,      // 图片消息
    FILE,       // 文件消息
    CARD,       // 卡片消息（交互式卡片）
    EVENT       // 事件消息
}

/**
 * 消息角色
 */
enum class MessageRole {
    USER,       // 用户消息
    ASSISTANT,  // AI 回复
    SYSTEM      // 系统消息
}

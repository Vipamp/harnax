package com.agnetix.harnax.channel.sdk.message

import com.agnetix.harnax.channel.sdk.config.ChannelType

/**
 * Message Type
 */
enum class MessageType {
    TEXT, // Text message
    MARKDOWN, // Markdown format message
    IMAGE, // Image message
    FILE, // File message
    CARD, // Card message (interactive card)
    EVENT, // Event message
}

/**
 * Message Role
 */
enum class MessageRole {
    USER, // User message
    ASSISTANT, // AI reply
    SYSTEM, // System message
}

/**
 * Unified Message Model
 * Used to unify message format across different platforms
 *
 * This class is the core message model of the SDK, platform-agnostic.
 * Each channel Adaptor is responsible for converting platform-specific messages to this format.
 */
data class ChannelMessage(
    /**
     * Message ID
     */
    val messageId: String? = null,

    /**
     * Session ID (user ID or group ID)
     */
    val sessionId: String,

    /**
     * Message type
     */
    val messageType: MessageType = MessageType.TEXT,

    /**
     * Message role
     */
    val role: MessageRole = MessageRole.USER,

    /**
     * Message content
     */
    val content: String,

    /**
     * Platform type
     */
    val channelType: ChannelType,

    /**
     * Sender name
     */
    val senderName: String? = null,

    /**
     * Sender ID
     */
    val senderId: String? = null,

    /**
     * Whether this is a group message
     */
    val isGroupMessage: Boolean = false,

    /**
     * Group ID (if group chat)
     */
    val groupId: String? = null,

    /**
     * Image URLs or base64 data URLs for multimodal input
     */
    val imageUrls: List<String> = emptyList(),

    /**
     * @-mentioned user list
     */
    val atUserIds: List<String> = emptyList(),

    /**
     * Original message content (platform-specific format)
     */
    val rawContent: Any? = null,

    /**
     * Timestamp
     */
    val timestamp: Long = System.currentTimeMillis(),
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
    private var imageUrls: List<String> = emptyList()
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
    fun imageUrls(imageUrls: List<String>) = apply { this.imageUrls = imageUrls }
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
        imageUrls = imageUrls,
        atUserIds = atUserIds,
        rawContent = rawContent,
        timestamp = timestamp,
    )
}

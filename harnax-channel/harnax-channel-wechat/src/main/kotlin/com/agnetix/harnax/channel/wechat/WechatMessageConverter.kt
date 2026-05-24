package com.agnetix.harnax.channel.wechat

import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.config.ChannelType
import com.agnetix.harnax.channel.sdk.message.ChannelMessage
import com.agnetix.harnax.channel.sdk.message.MessageRole
import com.agnetix.harnax.channel.sdk.message.MessageType
import com.github.wechat.ilink.sdk.core.model.MessageItem
import com.github.wechat.ilink.sdk.core.model.WeixinMessage

/**
 * WeChat Message Converter
 * Converts wechat-ilink-sdk's WeixinMessage to SDK's ChannelMessage
 */
object WechatMessageConverter {

    /**
     * Convert WeChat WeixinMessage to unified message model ChannelMessage
     *
     * @param wechatMessage WeChat original message
     * @param channel Channel configuration
     * @return Unified message model
     */
    fun toChannelMessage(wechatMessage: WeixinMessage, channel: ChannelSpec): ChannelMessage {
        val textContent = extractTextContent(wechatMessage)
        val messageType = mapMessageType(wechatMessage.message_type)

        return ChannelMessage.builder()
            .messageId(wechatMessage.message_id?.toString())
            .sessionId(wechatMessage.from_user_id ?: "")
            .messageType(messageType)
            .role(MessageRole.USER)
            .content(textContent)
            .channelType(ChannelType.WECHAT)
            .senderId(wechatMessage.from_user_id)
            .rawContent(wechatMessage)
            .timestamp(wechatMessage.create_time_ms ?: System.currentTimeMillis())
            .build()
    }

    /**
     * Extract text content from WeixinMessage
     */
    fun extractTextContent(message: WeixinMessage): String {
        val itemList = message.item_list ?: return ""
        val sb = StringBuilder()

        for (item in itemList) {
            val text = extractItemText(item)
            if (text.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append(text)
            }
        }

        return sb.toString()
    }

    /**
     * Extract text from MessageItem
     */
    private fun extractItemText(item: MessageItem): String {
        val textItem = item.text_item
        if (textItem != null) {
            return textItem.text ?: ""
        }

        val imageItem = item.image_item
        if (imageItem != null) {
            return "[图片]"
        }

        val fileItem = item.file_item
        if (fileItem != null) {
            val fileName = fileItem.file_name ?: "未知文件"
            return "[文件: $fileName]"
        }

        val voiceItem = item.voice_item
        if (voiceItem != null) {
            return "[语音消息]"
        }

        val videoItem = item.video_item
        if (videoItem != null) {
            return "[视频消息]"
        }

        return ""
    }

    /**
     * Map WeChat message type to SDK message type
     *
     * WeChat message_type:
     * - 1: Text
     * - Others: Non-text messages
     */
    private fun mapMessageType(messageType: Int?): MessageType = when (messageType) {
        1 -> MessageType.TEXT
        else -> MessageType.TEXT // Default to text processing
    }
}

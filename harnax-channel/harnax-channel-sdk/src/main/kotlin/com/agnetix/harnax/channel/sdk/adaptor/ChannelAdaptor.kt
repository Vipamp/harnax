package com.agnetix.harnax.channel.sdk.adaptor

import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.config.ChannelType
import com.agnetix.harnax.channel.sdk.message.ChannelMessage
import com.agnetix.harnax.channel.sdk.message.ChannelRequest
import com.agnetix.harnax.channel.sdk.message.RichMessage

/**
 * Channel 适配器接口
 * 定义各渠道的通用行为契约
 *
 * SDK 版本使用 ChannelRequest 替代 HttpServletRequest，
 * 实现与 Servlet 框架的解耦，使 SDK 可在非 Servlet 环境下使用。
 *
 * 生命周期:
 * 1. receiveMessage  - 接收并验证消息
 * 2. parseMessage    - 解析消息为统一格式
 * 3. (AgentAdaptor)  - 由 Agent 处理消息
 * 4. buildResponse   - 构建 Agent 回复的响应
 * 5. sendMessage     - 主动推送消息到平台
 */
interface ChannelAdaptor {

    /**
     * 获取平台类型
     */
    fun getType(): ChannelType

    /**
     * 验证回调签名
     * @param request 平台无关的请求对象
     * @param channel Channel 配置
     * @return 签名是否有效
     */
    fun verifySignature(request: ChannelRequest, channel: ChannelSpec): Boolean

    /**
     * 解析消息
     * @param request 平台无关的请求对象
     * @return 统一消息对象
     */
    fun parseMessage(request: ChannelRequest): ChannelMessage

    /**
     * 构建响应
     * @param reply AI 回复内容
     * @param originalMessage 原始消息
     * @return 平台特定格式的响应
     */
    fun buildResponse(reply: String, originalMessage: ChannelMessage): Any

    /**
     * 推送消息到平台
     * @param channel Channel 配置
     * @param sessionId 会话标识
     * @param message 消息内容
     */
    suspend fun sendMessage(channel: ChannelSpec, sessionId: String, message: String)

    /**
     * 发送富消息到平台
     * @param channel Channel 配置
     * @param sessionId 会话标识
     * @param richMessage 富消息对象
     */
    suspend fun sendRichMessage(channel: ChannelSpec, sessionId: String, richMessage: RichMessage) {
        // 默认实现：将富消息降级为纯文本发送
        val textContent = when (richMessage) {
            is com.agnetix.harnax.channel.sdk.message.TextRichMessage -> richMessage.content
            is com.agnetix.harnax.channel.sdk.message.MarkdownRichMessage -> richMessage.content
            else -> richMessage.toString()
        }
        sendMessage(channel, sessionId, textContent)
    }

    /**
     * 处理 URL 验证请求（首次配置时的验证）
     * @param request 平台无关的请求对象
     * @param channel Channel 配置
     * @return 验证响应，返回 null 表示非验证请求
     */
    fun handleUrlVerification(request: ChannelRequest, channel: ChannelSpec): Any? = null
}

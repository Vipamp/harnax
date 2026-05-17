package com.agnetix.harnax.channel.adaptor

import com.agnetix.harnax.channel.ChannelSpec
import com.agnetix.harnax.channel.ChannelType
import com.agnetix.harnax.channel.message.ChannelMessage
import jakarta.servlet.http.HttpServletRequest

/**
 * Channel 适配器接口
 * 定义各平台机器人的通用行为
 */
interface ChannelAdaptor {

    /**
     * 获取平台类型
     */
    fun getType(): ChannelType

    /**
     * 验证回调签名
     * @param request HTTP 请求
     * @param channel Channel 配置
     * @return 签名是否有效
     */
    fun verifySignature(request: HttpServletRequest, channel: ChannelSpec): Boolean

    /**
     * 解析消息
     * @param request HTTP 请求
     * @return 统一消息对象
     */
    fun parseMessage(request: HttpServletRequest): ChannelMessage

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
     * 处理 URL 验证请求（首次配置时的验证）
     * @param request HTTP 请求
     * @param channel Channel 配置
     * @return 验证响应
     */
    fun handleUrlVerification(request: HttpServletRequest, channel: ChannelSpec): Any? = null
}

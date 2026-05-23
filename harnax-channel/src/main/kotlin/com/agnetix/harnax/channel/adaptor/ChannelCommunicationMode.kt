package com.agnetix.harnax.channel.adaptor

import com.agnetix.harnax.channel.ChannelSpec
import com.agnetix.harnax.channel.message.ChannelMessage
import com.agnetix.harnax.channel.message.RichMessage

/**
 * Channel 通信模式接口
 * 定义不同通信方式（Webhook、WebSocket等）的通用行为契约
 *
 * 设计目标：
 * - 统一不同通信模式的接口，让上层 Adaptor 不感知底层通信方式
 * - 支持动态切换通信模式（如从 Webhook 切换到 WebSocket）
 * - 可扩展新的通信模式（如消息队列、gRPC 等）
 */
interface ChannelCommunicationMode {
    /**
     * 获取通信模式名称
     * @return 模式名称（如 "webhook", "websocket"）
     */
    fun getModeName(): String

    /**
     * 判断是否为回调模式
     * @return true 表示需要外部 HTTP 回调，false 表示主动拉取或长连接
     */
    fun isCallbackMode(): Boolean

    /**
     * 启动通信模式
     * @param channel Channel 配置
     * @param messageHandler 消息处理函数
     *
     * 说明：
     * - Webhook 模式：空操作，由外部 Servlet 容器处理
     * - WebSocket 模式：建立长连接并阻塞监听
     */
    fun start(channel: ChannelSpec, messageHandler: suspend (ChannelMessage) -> Unit)

    /**
     * 停止通信模式
     * @param channel Channel 配置
     *
     * 说明：
     * - Webhook 模式：空操作
     * - WebSocket 模式：关闭长连接
     */
    fun stop(channel: ChannelSpec)

    /**
     * 发送文本消息
     * @param channel Channel 配置
     * @param sessionId 会话 ID
     * @param message 消息内容
     */
    suspend fun sendMessage(channel: ChannelSpec, sessionId: String, message: String)

    /**
     * 发送富消息
     * @param channel Channel 配置
     * @param sessionId 会话 ID
     * @param richMessage 富消息对象
     */
    suspend fun sendRichMessage(channel: ChannelSpec, sessionId: String, richMessage: RichMessage)
}

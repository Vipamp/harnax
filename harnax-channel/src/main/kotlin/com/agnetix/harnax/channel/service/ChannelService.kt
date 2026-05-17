package com.agnetix.harnax.channel.service

import com.agnetix.harnax.channel.ChannelSpec
import com.agnetix.harnax.channel.ChannelType
import com.agnetix.harnax.channel.adaptor.ChannelAdaptor
import com.agnetix.harnax.channel.adaptor.ChannelAdaptorFactory
import com.agnetix.harnax.channel.adaptor.dingtalk.DingTalkAdaptor
import com.agnetix.harnax.channel.adaptor.feishu.FeishuAdaptor
import com.agnetix.harnax.channel.adaptor.wecom.WeComAdaptor
import com.agnetix.harnax.channel.client.PlatformHttpClient
import com.agnetix.harnax.channel.error.ChannelNotFoundException
import com.agnetix.harnax.channel.error.ChannelSendException
import com.agnetix.harnax.channel.error.ChannelSignatureException
import com.agnetix.harnax.channel.message.*
import com.agnetix.harnax.channel.session.AgentMessage
import com.agnetix.harnax.channel.session.ChannelSessionManager
import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * Channel 公共服务接口
 * 提供统一的 Channel 操作入口，供外部调用者使用
 *
 * 主要功能：
 * - 解析传入的回调消息
 * - 发送各种类型的消息（文本、Markdown、富消息）
 * - 会话历史管理
 * - 完整的处理流程（接收 → Agent 处理 → 发送回复）
 */
class ChannelService(
    private val adaptorFactory: ChannelAdaptorFactory = ChannelAdaptorFactory,
    private val sessionManager: ChannelSessionManager,
    private val httpClient: PlatformHttpClient = PlatformHttpClient(),
    private val channelResolver: suspend (Long) -> ChannelSpec?,
) {

    private val logger = LoggerFactory.getLogger(ChannelService::class.java)

    /**
     * 解析传入的回调消息
     *
     * @param channelId Channel ID
     * @param request HTTP 请求对象
     * @return 解析后的统一消息对象
     * @throws ChannelNotFoundException Channel 未找到
     * @throws ChannelSignatureException 签名验证失败
     */
    fun parseIncomingMessage(channelId: Long, request: HttpServletRequest): ChannelMessage {
        // 获取 Channel 配置
        val channel = channelResolverSync(channelId)
            ?: throw ChannelNotFoundException(channelId)

        // 获取适配器
        val adaptor = adaptorFactory.getAdaptor(channel.type)
            ?: throw RuntimeException("No adaptor found for channel type: ${channel.type}")

        // 验证签名
        if (!adaptor.verifySignature(request, channel)) {
            throw ChannelSignatureException(
                channelType = channel.type,
                detail = "Signature verification failed",
            )
        }

        // 处理 URL 验证请求
        val verificationResponse = adaptor.handleUrlVerification(request, channel)
        if (verificationResponse != null) {
            logger.info("URL verification successful for channel: $channelId")
            // URL 验证请求，返回验证响应（由调用方处理）
            throw UrlVerificationException(verificationResponse)
        }

        // 解析消息
        val message = adaptor.parseMessage(request)
        logger.info("Parsed message from channel $channelId: sessionId=${message.sessionId}")

        return message
    }

    /**
     * 发送纯文本消息
     *
     * @param channelId Channel ID
     * @param sessionId 会话 ID
     * @param content 消息内容
     * @return 发送结果
     */
    suspend fun sendTextMessage(
        channelId: Long,
        sessionId: String,
        content: String,
    ): SendResult = executeSend(channelId) { adaptor, channel ->
        adaptor.sendMessage(channel, sessionId, content)
        SendResult.Success()
    }

    /**
     * 发送 Markdown 消息
     *
     * @param channelId Channel ID
     * @param sessionId 会话 ID
     * @param content Markdown 内容
     * @return 发送结果
     */
    suspend fun sendMarkdownMessage(
        channelId: Long,
        sessionId: String,
        content: String,
    ): SendResult = executeSend(channelId) { adaptor, channel ->
        val richMessage = MarkdownRichMessage(content)
        sendRichMessageToAdaptor(adaptor, channel, sessionId, richMessage)
        SendResult.Success()
    }

    /**
     * 发送富消息
     *
     * @param channelId Channel ID
     * @param sessionId 会话 ID
     * @param richMessage 富消息对象
     * @return 发送结果
     */
    suspend fun sendRichMessage(
        channelId: Long,
        sessionId: String,
        richMessage: RichMessage,
    ): SendResult = executeSend(channelId) { adaptor, channel ->
        sendRichMessageToAdaptor(adaptor, channel, sessionId, richMessage)
        SendResult.Success()
    }

    /**
     * 获取会话历史消息
     *
     * @param channelId Channel ID
     * @param sessionId 会话 ID
     * @param limit 限制返回的消息数量
     * @return 历史消息列表
     */
    suspend fun getHistory(
        channelId: Long,
        sessionId: String,
        limit: Int = 20,
    ): List<ChannelMessage> = sessionManager.getHistory(channelId, sessionId, limit)

    /**
     * 清除会话历史
     *
     * @param channelId Channel ID
     * @param sessionId 会话 ID
     */
    suspend fun clearHistory(channelId: Long, sessionId: String) {
        sessionManager.clearHistory(channelId, sessionId)
    }

    /**
     * 完整处理流程：接收消息 → 调用 Agent → 发送回复
     *
     * @param channelId Channel ID
     * @param request HTTP 请求对象
     * @param agentProcessor Agent 处理函数
     *        参数：(历史消息列表, 当前消息内容)
     *        返回：AI 回复内容
     * @return 发送结果
     */
    suspend fun processAndReply(
        channelId: Long,
        request: HttpServletRequest,
        agentProcessor: suspend (List<AgentMessage>, String) -> String,
    ): SendResult {
        // 1. 解析传入消息
        val message = try {
            parseIncomingMessage(channelId, request)
        } catch (e: UrlVerificationException) {
            // URL 验证请求，直接返回成功
            return SendResult.Success(messageId = "url_verification")
        }

        // 2. 保存用户消息到会话
        sessionManager.addMessage(channelId, message)

        // 3. 获取历史消息
        val history = sessionManager.getHistory(channelId, message.sessionId, limit = 20)
        val agentMessages = sessionManager.toAgentMessages(history)

        // 4. 调用 Agent 处理
        val reply = agentProcessor(agentMessages, message.content)

        // 5. 保存 AI 回复
        val assistantMessage = ChannelMessage.builder()
            .sessionId(message.sessionId)
            .role(MessageRole.ASSISTANT)
            .content(reply)
            .channelType(message.channelType)
            .timestamp(System.currentTimeMillis())
            .build()
        sessionManager.addMessage(channelId, assistantMessage)

        // 6. 发送回复
        return executeSend(channelId) { adaptor, channel ->
            adaptor.sendMessage(channel, message.sessionId, reply)
            SendResult.Success()
        }
    }

    /**
     * 获取支持的 Channel 类型列表
     */
    fun getSupportedTypes(): List<ChannelType> = adaptorFactory.getSupportedTypes()

    // ==================== 私有辅助方法 ====================

    /**
     * 执行发送操作的通用方法
     */
    private suspend fun executeSend(
        channelId: Long,
        block: suspend (ChannelAdaptor, ChannelSpec) -> SendResult,
    ): SendResult {
        val channel = channelResolver(channelId)
            ?: return SendResult.Failure("CHANNEL_NOT_FOUND", "Channel $channelId not found")

        val adaptor = adaptorFactory.getAdaptor(channel.type)
            ?: return SendResult.Failure("NO_ADAPTOR", "No adaptor for ${channel.type}")

        return try {
            block(adaptor, channel)
        } catch (e: ChannelSendException) {
            logger.error("Failed to send message to channel $channelId: ${e.message}", e)
            SendResult.Failure(
                errorCode = e.platformErrorCode ?: "SEND_ERROR",
                errorMessage = e.message,
                cause = e,
            )
        } catch (e: Exception) {
            logger.error("Unexpected error sending message to channel $channelId: ${e.message}", e)
            SendResult.Failure(
                errorCode = "UNKNOWN_ERROR",
                errorMessage = "Unexpected error: ${e.message}",
                cause = e,
            )
        }
    }

    /**
     * 向适配器发送富消息
     */
    private suspend fun sendRichMessageToAdaptor(
        adaptor: ChannelAdaptor,
        channel: ChannelSpec,
        sessionId: String,
        richMessage: RichMessage,
    ) {
        when (adaptor) {
            is WeComAdaptor -> adaptor.sendRichMessage(channel, sessionId, richMessage)
            is FeishuAdaptor -> adaptor.sendRichMessage(channel, sessionId, richMessage)
            is DingTalkAdaptor -> adaptor.sendRichMessage(channel, sessionId, richMessage)
            else -> throw ChannelSendException(
                channelType = channel.type,
                platformErrorCode = null,
                message = "Rich message not supported for ${channel.type}",
            )
        }
    }

    /**
     * 同步解析 Channel（用于非 suspend 上下文）
     * 注意：这是一个临时方案，理想情况下应该使用异步解析
     */
    private fun channelResolverSync(channelId: Long): ChannelSpec? = try {
        runBlocking {
            channelResolver(channelId)
        }
    } catch (e: Exception) {
        logger.error("Failed to resolve channel $channelId: ${e.message}", e)
        null
    }
}

/**
 * URL 验证异常
 * 用于在 parseIncomingMessage 中识别 URL 验证请求
 */
class UrlVerificationException(
    val verificationResponse: Any,
) : RuntimeException("URL verification request")

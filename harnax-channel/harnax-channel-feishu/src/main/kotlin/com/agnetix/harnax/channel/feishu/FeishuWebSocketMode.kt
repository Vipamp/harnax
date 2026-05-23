package com.agnetix.harnax.channel.feishu

import com.agnetix.harnax.channel.feishu.client.PlatformHttpClient
import com.agnetix.harnax.channel.feishu.client.PlatformResponse
import com.agnetix.harnax.channel.sdk.adaptor.ChannelCommunicationMode
import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.config.ChannelType
import com.agnetix.harnax.channel.sdk.error.ChannelSendException
import com.agnetix.harnax.channel.sdk.message.ChannelMessage
import com.agnetix.harnax.channel.sdk.message.MessageType
import com.agnetix.harnax.channel.sdk.message.RichMessage
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lark.oapi.event.EventDispatcher
import com.lark.oapi.service.im.ImService
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import com.lark.oapi.ws.Client as WsClient

/**
 * 飞书 WebSocket 长连接模式实现
 * 基于飞书官方 Java SDK (oapi-sdk 2.4.0) 实现 WebSocket 全双工通信
 *
 * 特点：
 * - 无需公网 IP 或域名，只需能访问公网
 * - 内置加密和鉴权，无需额外处理签名
 * - 适用于内网开发环境和企业私有化部署
 * - 支持自动重连
 *
 * 注意事项：
 * - WebSocket 仅用于接收消息，发送消息仍需调用 Open API
 * - 每个 Channel 对应一个独立的 WebSocket 连接
 * - 飞书 WebSocket 为集群模式，同一应用多个客户端只有一个会收到消息
 */
class FeishuWebSocketMode(
    private val httpClient: PlatformHttpClient = PlatformHttpClient(),
) : ChannelCommunicationMode {

    private val logger = LoggerFactory.getLogger(FeishuWebSocketMode::class.java)
    private val objectMapper = ObjectMapper().registerKotlinModule()

    // 存储每个 Channel 的 WebSocket 客户端
    private val wsClients = ConcurrentHashMap<Long, WsClient>()

    // 存储每个 Channel 的消息处理器
    private val messageHandlers = ConcurrentHashMap<Long, suspend (ChannelMessage) -> Unit>()

    override fun getModeName(): String = "websocket"

    override fun isCallbackMode(): Boolean = false

    /**
     * 启动 WebSocket 长连接
     *
     * @param channel Channel 配置
     * @param messageHandler 消息处理函数
     */
    override fun start(channel: ChannelSpec, messageHandler: suspend (ChannelMessage) -> Unit) {
        if (wsClients.containsKey(channel.id)) {
            logger.warn("WebSocket connection already exists for channel: ${channel.id}")
            return
        }

        val appId = channel.appId
        val appSecret = channel.appSecret

        if (appId.isNullOrBlank() || appSecret.isNullOrBlank()) {
            throw IllegalArgumentException("WebSocket mode requires appId and appSecret for channel: ${channel.id}")
        }

        logger.info("Starting WebSocket connection for channel: ${channel.id}")

        try {
            // 创建消息事件处理器
            val messageEventHandler = object : ImService.P2MessageReceiveV1Handler() {
                override fun handle(data: P2MessageReceiveV1?) {
                    if (data == null) {
                        logger.warn("Received null message event for channel: ${channel.id}")
                        return
                    }

                    // 异步处理消息（不阻塞事件处理）
                    runBlocking {
                        try {
                            val channelMessage = parseFeishuEvent(data, channel.id)
                            if (channelMessage != null) {
                                val handler = messageHandlers[channel.id]
                                if (handler != null) {
                                    handler(channelMessage)
                                } else {
                                    logger.warn("No message handler registered for channel: ${channel.id}")
                                }
                            }
                        } catch (e: Exception) {
                            logger.error("Failed to handle Feishu message event for channel: ${channel.id}", e)
                        }
                    }
                }
            }

            // 创建事件分发器并注册消息处理器
            val eventDispatcher = EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(messageEventHandler)
                .build()

            // 创建 WebSocket 客户端
            val wsClient = WsClient.Builder(appId, appSecret)
                .eventHandler(eventDispatcher)
                .autoReconnect(true)
                .build()

            // 在后台线程启动（非阻塞）
            Thread {
                try {
                    wsClient.start()
                } catch (e: Exception) {
                    logger.error("WebSocket connection failed for channel: ${channel.id}", e)
                    wsClients.remove(channel.id)
                }
            }.apply {
                isDaemon = true
                name = "feishu-ws-channel-${channel.id}"
                start()
            }

            wsClients[channel.id] = wsClient
            messageHandlers[channel.id] = messageHandler
            logger.info("WebSocket connection started for channel: ${channel.id}")
        } catch (e: Exception) {
            logger.error("Failed to start WebSocket connection for channel: ${channel.id}", e)
            throw ChannelSendException(
                channelType = ChannelType.FEISHU,
                platformErrorCode = null,
                message = "Failed to start WebSocket: ${e.message}",
                cause = e,
            )
        }
    }

    /**
     * 停止 WebSocket 长连接
     *
     * @param channel Channel 配置
     */
    override fun stop(channel: ChannelSpec) {
        val wsClient = wsClients.remove(channel.id)
        if (wsClient != null) {
            try {
                logger.info("WebSocket connection removed for channel: ${channel.id}")
            } catch (e: Exception) {
                logger.error("Failed to stop WebSocket connection for channel: ${channel.id}", e)
            }
        } else {
            logger.warn("No WebSocket connection found for channel: ${channel.id}")
        }
    }

    /**
     * 发送文本消息
     * 通过飞书 Open API 发送（需要 tenant_access_token）
     */
    override suspend fun sendMessage(channel: ChannelSpec, sessionId: String, message: String) {
        val appId = channel.appId
        val appSecret = channel.appSecret

        if (appId.isNullOrBlank() || appSecret.isNullOrBlank()) {
            throw ChannelSendException(
                channelType = ChannelType.FEISHU,
                platformErrorCode = null,
                message = "WebSocket mode requires appId and appSecret to send messages",
            )
        }

        // 获取 tenant_access_token
        val token = getTenantAccessToken(appId, appSecret)

        // 构建消息体（必须包含 receive_id）
        // 注意：飞书 API 要求 content 字段是 JSON 字符串，不是对象
        val contentJson = objectMapper.writeValueAsString(mapOf("text" to message))
        val messageBody = mapOf(
            "receive_id" to sessionId,
            "msg_type" to "text",
            "content" to contentJson,
        )

        logger.debug("Sending Feishu message: receive_id={}, msg_type={}, content={}", sessionId, "text", message)

        // 调用飞书 Open API 发送消息
        val url = "https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id"
        val response = httpClient.postJson(
            url,
            messageBody,
            mapOf(
                "Authorization" to "Bearer $token",
            ),
        )

        if (response is PlatformResponse.Error) {
            logger.error("Feishu API error response: status={}, body={}", response.statusCode, response.body)
        }

        handleSendResponse(response)
    }

    /**
     * 发送富消息
     */
    override suspend fun sendRichMessage(channel: ChannelSpec, sessionId: String, richMessage: RichMessage) {
        val appId = channel.appId
        val appSecret = channel.appSecret

        if (appId.isNullOrBlank() || appSecret.isNullOrBlank()) {
            throw ChannelSendException(
                channelType = ChannelType.FEISHU,
                platformErrorCode = null,
                message = "WebSocket mode requires appId and appSecret to send rich messages",
            )
        }

        // 获取 tenant_access_token
        val token = getTenantAccessToken(appId, appSecret)

        // 构建富消息体（必须包含 receive_id）
        val content = FeishuMessageBuilder.buildFromRichMessage(richMessage)
        val contentJson = objectMapper.writeValueAsString(content["content"])
        val messageBody = mapOf(
            "receive_id" to sessionId,
            "msg_type" to content["msg_type"],
            "content" to contentJson,
        )

        // 调用飞书 Open API 发送消息
        val url = "https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id"
        val response = httpClient.postJson(
            url,
            messageBody,
            mapOf(
                "Authorization" to "Bearer $token",
            ),
        )

        handleSendResponse(response)
    }

    /**
     * 获取 tenant_access_token
     * 用于调用飞书 Open API
     */
    private suspend fun getTenantAccessToken(appId: String, appSecret: String): String {
        val requestBody = mapOf(
            "app_id" to appId,
            "app_secret" to appSecret,
        )

        val response = httpClient.postJson(
            "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal",
            requestBody,
        )

        return when (response) {
            is PlatformResponse.Success -> {
                try {
                    val json = objectMapper.readTree(response.body)
                    val code = json.path("code").asInt(-1)
                    if (code == 0) {
                        json.path("tenant_access_token").asText()
                    } else {
                        val msg = json.path("msg").asText("unknown")
                        throw ChannelSendException(
                            channelType = ChannelType.FEISHU,
                            platformErrorCode = code.toString(),
                            message = "Failed to get tenant_access_token: $msg",
                        )
                    }
                } catch (e: ChannelSendException) {
                    throw e
                } catch (e: Exception) {
                    throw ChannelSendException(
                        channelType = ChannelType.FEISHU,
                        platformErrorCode = null,
                        message = "Failed to parse tenant_access_token response: ${e.message}",
                        cause = e,
                    )
                }
            }
            is PlatformResponse.Error -> {
                throw ChannelSendException(
                    channelType = ChannelType.FEISHU,
                    platformErrorCode = response.platformCode,
                    message = "Failed to get tenant_access_token: HTTP ${response.statusCode}",
                    cause = response.exception,
                )
            }
        }
    }

    /**
     * 解析飞书事件为 ChannelMessage
     */
    private fun parseFeishuEvent(event: P2MessageReceiveV1, channelId: Long): ChannelMessage? {
        return try {
            val message = event.event?.message

            if (message == null) {
                logger.warn("Message is null in event for channel: $channelId")
                return null
            }

            val messageId = message.messageId ?: ""
            val chatId = message.chatId ?: ""
            val messageType = message.messageType ?: "text"
            val senderId = event.event?.sender?.senderId?.openId ?: ""

            val content = when (messageType) {
                "text" -> {
                    val contentStr = message.content ?: "{}"
                    try {
                        val contentJson = objectMapper.readTree(contentStr)
                        contentJson.path("text").asText("")
                    } catch (e: Exception) {
                        logger.warn("Failed to parse message content: ${e.message}")
                        contentStr
                    }
                }
                else -> {
                    logger.info("Unsupported message type: $messageType, using raw content")
                    message.content ?: ""
                }
            }

            ChannelMessage(
                messageId = messageId,
                sessionId = chatId,
                senderId = senderId,
                content = content,
                channelType = ChannelType.FEISHU,
                messageType = when (messageType) {
                    "text" -> MessageType.TEXT
                    "image" -> MessageType.IMAGE
                    "file" -> MessageType.FILE
                    else -> MessageType.TEXT
                },
                rawContent = event,
            )
        } catch (e: Exception) {
            logger.error("Failed to parse Feishu event for channel: $channelId", e)
            null
        }
    }

    /**
     * 处理发送响应
     */
    private fun handleSendResponse(response: PlatformResponse) {
        when (response) {
            is PlatformResponse.Success -> {
                try {
                    val json = objectMapper.readTree(response.body)
                    val code = json.path("code").asInt(-1)
                    val msg = json.path("msg").asText("unknown")

                    logger.debug("Feishu API response: code={}, msg={}", code, msg)

                    if (code == 0) {
                        logger.info("Feishu message sent successfully via WebSocket mode")
                    } else {
                        val fullError = json.toString()
                        logger.error("Feishu API returned error code {}: {}", code, fullError)
                        throw ChannelSendException(
                            channelType = ChannelType.FEISHU,
                            platformErrorCode = code.toString(),
                            message = "Feishu send failed (code=$code): $msg",
                        )
                    }
                } catch (e: ChannelSendException) {
                    throw e
                } catch (e: Exception) {
                    throw ChannelSendException(
                        channelType = ChannelType.FEISHU,
                        platformErrorCode = null,
                        message = "Failed to parse Feishu response: ${e.message}",
                        cause = e,
                    )
                }
            }
            is PlatformResponse.Error -> {
                logger.error("Feishu HTTP error: status={}, body={}", response.statusCode, response.body)
                throw ChannelSendException(
                    channelType = ChannelType.FEISHU,
                    platformErrorCode = response.platformCode,
                    message = "Feishu HTTP error: ${response.statusCode} - ${response.platformMessage ?: response.body}",
                    cause = response.exception,
                )
            }
        }
    }
}

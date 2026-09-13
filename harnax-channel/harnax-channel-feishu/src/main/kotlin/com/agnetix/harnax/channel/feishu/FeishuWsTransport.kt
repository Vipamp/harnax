package com.agnetix.harnax.channel.feishu

import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.lark.oapi.event.EventDispatcher
import com.lark.oapi.service.im.ImService
import org.slf4j.LoggerFactory
import java.lang.reflect.Method
import com.lark.oapi.ws.Client as WsClient

/**
 * The two lifecycle calls [FeishuWebSocketMode] makes against the Feishu WebSocket client.
 *
 * `com.lark.oapi.ws.Client` has a private constructor and opens a real socket from `start()`,
 * so it cannot be swapped for a fake where the connection-thread bookkeeping needs testing —
 * and that bookkeeping is exactly where the listener-lifecycle bug lived. This seam keeps the
 * SDK plumbing in one file and leaves the ownership rules testable without a network.
 */
interface FeishuWsTransport {
    /**
     * Hands the connection to the SDK and returns; the socket outlives this call.
     *
     * @throws Exception when the client could not be started at all
     */
    fun start()

    /** Best-effort close: safe on a never-started transport, and never throws. */
    fun close()
}

/** Creates the transport for a channel, given its already-wired inbound event handler. */
fun interface FeishuWsTransportFactory {
    fun create(channel: ChannelSpec, handler: ImService.P2MessageReceiveV1Handler): FeishuWsTransport
}

/** Production factory: wraps the official oapi-sdk WebSocket client. */
object SdkFeishuWsTransportFactory : FeishuWsTransportFactory {
    private val logger = LoggerFactory.getLogger(SdkFeishuWsTransportFactory::class.java)

    /** Resolved once instead of on every stop; the SDK keeps disconnect() non-public. */
    private val disconnectMethod: Method? by lazy {
        runCatching {
            WsClient::class.java.getDeclaredMethod("disconnect").apply { isAccessible = true }
        }.getOrNull()
    }

    override fun create(channel: ChannelSpec, handler: ImService.P2MessageReceiveV1Handler): FeishuWsTransport {
        val appId = channel.appId
        val appSecret = channel.appSecret
        if (appId.isNullOrBlank() || appSecret.isNullOrBlank()) {
            throw IllegalArgumentException("WebSocket mode requires appId and appSecret for channel: ${channel.id}")
        }
        val eventDispatcher = EventDispatcher.newBuilder("", "")
            .onP2MessageReceiveV1(handler)
            .build()
        val client = WsClient.Builder(appId, appSecret)
            .eventHandler(eventDispatcher)
            .autoReconnect(true)
            .build()
        return SdkTransport(client)
    }

    private class SdkTransport(private val client: WsClient) : FeishuWsTransport {
        override fun start() {
            client.start()
        }

        override fun close() {
            val method = disconnectMethod ?: run {
                logger.warn("Feishu SDK exposes no disconnect(); leaving the socket to the daemon thread")
                return
            }
            try {
                method.invoke(client)
            } catch (e: Exception) {
                logger.error("Failed to disconnect WebSocket: ${e.message}", e)
            }
        }
    }
}

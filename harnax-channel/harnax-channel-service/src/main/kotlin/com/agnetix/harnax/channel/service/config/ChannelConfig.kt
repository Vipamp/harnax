package com.agnetix.harnax.channel.service.config

import com.agnetix.harnax.channel.feishu.FeishuAdaptor
import com.agnetix.harnax.channel.sdk.message.ChannelMessage
import com.agnetix.harnax.channel.sdk.message.MessageType
import com.agnetix.harnax.channel.sdk.session.ChannelSessionManager
import com.agnetix.harnax.channel.wechat.WechatAdaptor
import io.netty.channel.ChannelOption
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.client.RestClient
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Channel service configuration.
 */
@Configuration
class ChannelConfig(
    @Value("\${channel.proxy.connect-timeout-ms:5000}")
    private val connectTimeoutMs: Int,
    @Value("\${channel.proxy.response-timeout-ms:120000}")
    private val responseTimeoutMs: Int,
) {

    private val log = LoggerFactory.getLogger(ChannelConfig::class.java)

    @Bean
    fun webClient(channelRouterApiKey: ChannelRouterApiKey): WebClient {
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
            .responseTimeout(Duration.ofMillis(responseTimeoutMs.toLong()))

        log.info("Creating WebClient with connectTimeout={}ms, responseTimeout={}ms", connectTimeoutMs, responseTimeoutMs)

        return WebClient.builder()
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .codecs { config -> config.defaultCodecs().maxInMemorySize(16 * 1024 * 1024) } // 16MB for image payloads
            .filter(apiKeyFilter(channelRouterApiKey.rawKey))
            .build()
    }

    /**
     * RestClient for batch (non-streaming) requests to router.
     * Uses synchronous HTTP with JWT auth interceptor.
     */
    @Bean
    fun restClient(channelRouterApiKey: ChannelRouterApiKey): RestClient {
        val factory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofMillis(connectTimeoutMs.toLong()))
            setReadTimeout(Duration.ofMillis(responseTimeoutMs.toLong()))
        }
        log.info("Creating RestClient with connectTimeout={}ms, readTimeout={}ms", connectTimeoutMs, responseTimeoutMs)
        return RestClient.builder()
            .requestFactory(factory)
            .requestInterceptor { request, body, execution ->
                request.headers.add("X-Api-Key", channelRouterApiKey.rawKey)
                execution.execute(request, body)
            }
            .build()
    }

    private fun apiKeyFilter(apiKey: String): ExchangeFilterFunction = ExchangeFilterFunction { request, next ->
        val mutated = ClientRequest.from(request)
        mutated.header("X-Api-Key", apiKey)
        next.exchange(mutated.build())
    }

    /**
     * Provide an in-memory ChannelSessionManager for channel service.
     * Stores session history in memory using ConcurrentHashMap.
     */
    @Bean
    fun channelSessionManager(): ChannelSessionManager {
        log.info("Creating InMemoryChannelSessionManager for channel service")
        return InMemoryChannelSessionManager()
    }

    /**
     * Registers the WeChat adaptor Bean.
     * WechatAdaptor has no Spring annotations, so it is explicitly created as a singleton here.
     */
    @Bean
    fun wechatAdaptor(): WechatAdaptor = WechatAdaptor()

    /**
     * Registers the Feishu adaptor Bean.
     * FeishuAdaptor has no Spring annotations, so it is explicitly created as a singleton here.
     */
    @Bean
    fun feishuAdaptor(): FeishuAdaptor = FeishuAdaptor()

    /**
     * In-memory implementation of ChannelSessionManager.
     * Uses ConcurrentHashMap to store session history.
     * Key format: "{channelId}:{sessionId}"
     */
    class InMemoryChannelSessionManager : ChannelSessionManager {
        private val log = LoggerFactory.getLogger(InMemoryChannelSessionManager::class.java)
        private val sessions = ConcurrentHashMap<String, MutableList<ChannelMessage>>()

        companion object {
            // Max messages kept per session to prevent unbounded growth
            private const val MAX_MESSAGES_PER_SESSION = 500

            // Max total sessions tracked; oldest are evicted when exceeded
            private const val MAX_TOTAL_SESSIONS = 10_000
        }

        override suspend fun getHistory(channelId: Long, sessionId: String, limit: Int): List<ChannelMessage> {
            val key = "$channelId:$sessionId"
            val messages = sessions[key] ?: emptyList()
            log.debug("Getting history for key={}, size={}", key, messages.size)
            return messages.takeLast(limit)
        }

        override suspend fun addMessage(channelId: Long, message: ChannelMessage) {
            val key = "$channelId:${message.sessionId}"
            // Strip image data from stored messages to avoid memory bloat in history
            val stored = if (message.imageUrls.isNotEmpty()) {
                message.copy(
                    content = if (message.messageType == MessageType.IMAGE) "[image sent]" else message.content,
                    imageUrls = emptyList(),
                )
            } else if (message.messageType == MessageType.IMAGE && message.content.startsWith("data:image")) {
                message.copy(content = "[image sent]")
            } else {
                message
            }

            // Evict oldest sessions if capacity is exceeded
            if (sessions.size >= MAX_TOTAL_SESSIONS && !sessions.containsKey(key)) {
                val oldestKey = sessions.keys.firstOrNull()
                if (oldestKey != null) {
                    sessions.remove(oldestKey)
                    log.debug("Evicted oldest session to stay within limit: key={}", oldestKey)
                }
            }

            val list = sessions.computeIfAbsent(key) { mutableListOf() }
            synchronized(list) {
                list.add(stored)
                // Trim oldest messages when exceeding per-session limit
                while (list.size > MAX_MESSAGES_PER_SESSION) {
                    list.removeAt(0)
                }
            }
            log.debug("Added message to key={}, total={}", key, list.size)
        }

        override suspend fun clearHistory(channelId: Long, sessionId: String) {
            val key = "$channelId:$sessionId"
            sessions.remove(key)
            log.debug("Cleared history for key={}", key)
        }
    }
}

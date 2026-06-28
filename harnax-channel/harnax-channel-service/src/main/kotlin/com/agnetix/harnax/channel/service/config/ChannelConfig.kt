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
     * 注册微信适配器 Bean。
     * WechatAdaptor 未加 Spring 注解，在这里显式创建单例。
     */
    @Bean
    fun wechatAdaptor(): WechatAdaptor = WechatAdaptor()

    /**
     * 注册飞书适配器 Bean。
     * FeishuAdaptor 未加 Spring 注解，在这里显式创建单例。
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
            sessions.computeIfAbsent(key) { mutableListOf() }.add(stored)
            log.debug("Added message to key={}, total={}", key, sessions[key]?.size)
        }

        override suspend fun clearHistory(channelId: Long, sessionId: String) {
            val key = "$channelId:$sessionId"
            sessions.remove(key)
            log.debug("Cleared history for key={}", key)
        }
    }
}

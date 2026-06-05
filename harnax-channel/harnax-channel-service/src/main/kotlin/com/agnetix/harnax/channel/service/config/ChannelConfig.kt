package com.agnetix.harnax.channel.service.config

import com.agnetix.harnax.channel.feishu.FeishuAdaptor
import com.agnetix.harnax.channel.sdk.message.ChannelMessage
import com.agnetix.harnax.channel.sdk.session.ChannelSessionManager
import com.agnetix.harnax.channel.wechat.WechatAdaptor
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient
import java.util.concurrent.ConcurrentHashMap

/**
 * Channel service configuration.
 */
@Configuration
class ChannelConfig {

    private val log = LoggerFactory.getLogger(ChannelConfig::class.java)

    @Bean
    fun webClient(): WebClient = WebClient.builder()
        .build()

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
            sessions.computeIfAbsent(key) { mutableListOf() }.add(message)
            log.debug("Added message to key={}, total={}", key, sessions[key]?.size)
        }

        override suspend fun clearHistory(channelId: Long, sessionId: String) {
            val key = "$channelId:$sessionId"
            sessions.remove(key)
            log.debug("Cleared history for key={}", key)
        }
    }
}

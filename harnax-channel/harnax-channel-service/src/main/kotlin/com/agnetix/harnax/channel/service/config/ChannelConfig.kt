package com.agnetix.harnax.channel.service.config

import com.agnetix.harnax.channel.dingtalk.DingtalkAdaptor
import com.agnetix.harnax.channel.feishu.FeishuAdaptor
import com.agnetix.harnax.channel.sdk.dispatch.ChannelTurnExecutor
import com.agnetix.harnax.channel.sdk.monitor.ChannelMetricsSink
import com.agnetix.harnax.channel.sdk.monitor.MicrometerChannelMetricsSink
import com.agnetix.harnax.channel.sdk.session.ChannelSessionManager
import com.agnetix.harnax.channel.service.client.RouterCircuitBreaker
import com.agnetix.harnax.channel.service.session.InMemoryChannelSessionManager
import com.agnetix.harnax.channel.wechat.WechatAdaptor
import com.agnetix.harnax.channel.wecom.WecomAdaptor
import io.micrometer.core.instrument.MeterRegistry
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

/**
 * Channel service configuration.
 */
@Configuration
class ChannelConfig(
    @Value("\${channel.proxy.connect-timeout-ms:5000}")
    private val connectTimeoutMs: Int,
    @Value("\${channel.proxy.response-timeout-ms:120000}")
    private val responseTimeoutMs: Int,
    @Value("\${channel.turn.pool-size:24}")
    private val turnPoolSize: Int,
    @Value("\${channel.turn.per-channel-concurrency:4}")
    private val turnPerChannelConcurrency: Int,
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

    /**
     * Circuit guarding router calls, so an agent-service outage sheds load instead of
     * parking every channel worker on a 10-minute read timeout.
     */
    @Bean
    fun routerCircuitBreaker(
        @Value("\${channel.router.breaker.enabled:true}")
        enabled: Boolean,
        @Value("\${channel.router.breaker.failure-threshold:5}")
        failureThreshold: Int,
        @Value("\${channel.router.breaker.open-duration-ms:30000}")
        openDurationMs: Long,
    ): RouterCircuitBreaker {
        log.info(
            "Creating RouterCircuitBreaker enabled={}, failureThreshold={}, openDuration={}ms",
            enabled,
            failureThreshold,
            openDurationMs,
        )
        return RouterCircuitBreaker(
            enabled = enabled,
            failureThreshold = failureThreshold,
            openDuration = Duration.ofMillis(openDurationMs),
        )
    }

    private fun apiKeyFilter(apiKey: String): ExchangeFilterFunction = ExchangeFilterFunction { request, next ->
        val mutated = ClientRequest.from(request)
        mutated.header("X-Api-Key", apiKey)
        next.exchange(mutated.build())
    }

    /**
     * Runtime metrics sink for the channel transports. The implementation ships with the SDK;
     * this only binds it to the registry actuator already provides.
     */
    @Bean
    fun channelMetricsSink(registry: MeterRegistry): ChannelMetricsSink = MicrometerChannelMetricsSink(registry)

    /**
     * Bounded execution surface for inbound messages.
     *
     * Replaces the previous per-channel use of the global `Dispatchers.IO`: one slow agent turn
     * used to be able to occupy the shared 64-thread pool and stall every other channel.
     * Closed on shutdown so the JVM can exit without waiting for in-flight turns.
     */
    @Bean(destroyMethod = "close")
    fun channelTurnExecutor(
        sink: ChannelMetricsSink,
    ): ChannelTurnExecutor {
        log.info(
            "Creating ChannelTurnExecutor poolSize={}, perChannelConcurrency={}",
            turnPoolSize,
            turnPerChannelConcurrency,
        )
        return ChannelTurnExecutor(
            threadPoolSize = turnPoolSize,
            perChannelConcurrency = turnPerChannelConcurrency,
            sink = sink,
        )
    }

    /**
     * In-memory conversation history for the channel service.
     *
     * Bounded on purpose: this process is long-lived and every group chat it has ever served used
     * to stay resident forever.
     */
    @Bean
    fun channelSessionManager(
        @Value("\${channel.session.max-sessions:10000}") maxSessions: Int,
        @Value("\${channel.session.max-messages:500}") maxMessages: Int,
        @Value("\${channel.session.idle-ttl-minutes:120}") idleTtlMinutes: Long,
    ): ChannelSessionManager {
        log.info(
            "Creating InMemoryChannelSessionManager maxSessions={}, maxMessages={}, idleTtl={}min",
            maxSessions,
            maxMessages,
            idleTtlMinutes,
        )
        return InMemoryChannelSessionManager(
            maxSessions = maxSessions,
            maxMessagesPerSession = maxMessages,
            idleTtl = Duration.ofMinutes(idleTtlMinutes),
        )
    }

    /**
     * Registers the WeChat adaptor Bean.
     * WechatAdaptor has no Spring annotations, so it is explicitly created as a singleton here.
     */
    @Bean
    fun wechatAdaptor(
        turnExecutor: ChannelTurnExecutor,
        sink: ChannelMetricsSink,
    ): WechatAdaptor = WechatAdaptor(turnExecutor = turnExecutor, metricsSink = sink)

    /**
     * Registers the Feishu adaptor Bean.
     * FeishuAdaptor has no Spring annotations, so it is explicitly created as a singleton here.
     */
    @Bean
    fun feishuAdaptor(
        turnExecutor: ChannelTurnExecutor,
        sink: ChannelMetricsSink,
    ): FeishuAdaptor = FeishuAdaptor(turnExecutor = turnExecutor, metricsSink = sink)

    /**
     * Registers the DingTalk adaptor Bean.
     * DingtalkAdaptor has no Spring annotations, so it is explicitly created as a singleton here.
     */
    @Bean
    fun dingtalkAdaptor(
        turnExecutor: ChannelTurnExecutor,
        sink: ChannelMetricsSink,
    ): DingtalkAdaptor = DingtalkAdaptor(turnExecutor = turnExecutor, metricsSink = sink)

    /**
     * Registers the WeCom adaptor Bean.
     * WecomAdaptor has no Spring annotations, so it is explicitly created as a singleton here.
     */
    @Bean
    fun wecomAdaptor(
        turnExecutor: ChannelTurnExecutor,
        sink: ChannelMetricsSink,
    ): WecomAdaptor = WecomAdaptor(turnExecutor = turnExecutor, metricsSink = sink)
}

package com.agnetix.harnax.router.config

import com.agnetix.harnax.auth.InternalTokenProvider
import com.agnetix.harnax.router.service.ApiCallLogService
import com.agnetix.harnax.router.service.IdempotencyService
import com.agnetix.harnax.router.service.InstanceCircuitBreaker
import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionInfoClient
import com.agnetix.harnax.router.service.SessionMappingService
import com.agnetix.harnax.router.service.impl.CaffeineIdempotencyService
import com.agnetix.harnax.router.service.impl.CaffeineSessionMappingService
import com.agnetix.harnax.router.service.impl.LocalInstanceRegistry
import com.agnetix.harnax.router.service.impl.RedisIdempotencyService
import com.agnetix.harnax.router.service.impl.RedisInstanceRegistry
import com.agnetix.harnax.router.service.impl.RedisSessionMappingService
import io.netty.channel.ChannelOption
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import java.time.Duration

@Configuration
@EnableScheduling
class RouterConfig(
    @Value($$"${router.proxy.connect-timeout-ms:5000}")
    private val connectTimeoutMs: Int,
    @Value($$"${router.proxy.read-timeout-ms:60000}")
    private val readTimeoutMs: Int,
    @Value($$"${router.proxy.max-in-memory-size-mb:16}")
    private val maxInMemorySizeMb: Int,
    @Value($$"${router.proxy.max-connections:200}")
    private val maxConnections: Int,
    @Value($$"${router.proxy.pending-acquire-timeout-ms:10000}")
    private val pendingAcquireTimeoutMs: Int,
    private val tokenProvider: InternalTokenProvider,
) {

    @Bean
    fun webClient(): WebClient {
        val connectionProvider = ConnectionProvider.builder("router-pool")
            .maxConnections(maxConnections)
            .pendingAcquireTimeout(Duration.ofMillis(pendingAcquireTimeoutMs.toLong()))
            .pendingAcquireMaxCount(500)
            .maxIdleTime(Duration.ofSeconds(60))
            .maxLifeTime(Duration.ofMinutes(5))
            .evictInBackground(Duration.ofSeconds(30))
            .metrics(true)
            .build()

        val httpClient = HttpClient.create(connectionProvider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
            .responseTimeout(Duration.ofMillis(readTimeoutMs.toLong()))
            .doOnConnected { conn ->
                conn.addHandlerLast(io.netty.handler.timeout.ReadTimeoutHandler(readTimeoutMs / 1000))
                    .addHandlerLast(io.netty.handler.timeout.WriteTimeoutHandler(30))
            }

        return WebClient.builder()
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .codecs { config -> config.defaultCodecs().maxInMemorySize(maxInMemorySizeMb * 1024 * 1024) }
            .filter(authFilter("agent:invoke"))
            .build()
    }

    private fun authFilter(scope: String): ExchangeFilterFunction =
        ExchangeFilterFunction { request, next ->
            val headers = tokenProvider.authHeaders(scope)
            val mutated = ClientRequest.from(request)
            headers.forEach { (key, value) -> mutated.header(key, value) }
            next.exchange(mutated.build())
        }

    @Bean
    fun circuitBreaker(
        @Value($$"${router.circuit-breaker.failure-threshold:3}") failureThreshold: Int,
        @Value($$"${router.circuit-breaker.open-duration-ms:30000}") openDurationMs: Long,
    ): InstanceCircuitBreaker = InstanceCircuitBreaker(
        failureThreshold = failureThreshold,
        openDurationMs = openDurationMs,
    )

    // ==================== Redis cache mode beans ====================

    @Bean
    @ConditionalOnProperty(name = ["router.cache.type"], havingValue = "redis")
    fun instanceRegistry(
        redisTemplate: RedisTemplate<String, Any>,
        @Value($$"${router.health.heartbeat-timeout-ms:30000}") heartbeatTimeoutMs: Long,
    ): InstanceRegistry = RedisInstanceRegistry(redisTemplate, heartbeatTimeoutMs)

    @Bean
    @ConditionalOnProperty(name = ["router.cache.type"], havingValue = "redis")
    fun sessionMappingService(
        instanceRegistry: InstanceRegistry,
        redisTemplate: RedisTemplate<String, Any>,
        @Value($$"${router.health.heartbeat-timeout-ms:30000}") heartbeatTimeoutMs: Long,
    ): SessionMappingService = RedisSessionMappingService(instanceRegistry, redisTemplate, heartbeatTimeoutMs)

    @Bean
    @ConditionalOnProperty(name = ["router.cache.type"], havingValue = "redis")
    fun idempotencyService(
        redisTemplate: RedisTemplate<String, Any>,
        @Value($$"${router.idempotency.ttl-seconds:60}") ttlSeconds: Long,
    ): IdempotencyService = RedisIdempotencyService(redisTemplate, ttlSeconds)

    // ==================== Local cache mode beans ====================

    @Bean
    @ConditionalOnProperty(name = ["router.cache.type"], havingValue = "local", matchIfMissing = true)
    fun localInstanceRegistry(
        @Value($$"${router.health.heartbeat-timeout-ms:30000}") heartbeatTimeoutMs: Long,
    ): InstanceRegistry = LocalInstanceRegistry(heartbeatTimeoutMs)

    @Bean
    @ConditionalOnProperty(name = ["router.cache.type"], havingValue = "local", matchIfMissing = true)
    fun localSessionMappingService(
        instanceRegistry: InstanceRegistry,
        @Value($$"${router.health.heartbeat-timeout-ms:30000}") heartbeatTimeoutMs: Long,
    ): SessionMappingService = CaffeineSessionMappingService(instanceRegistry, heartbeatTimeoutMs)

    @Bean
    @ConditionalOnProperty(name = ["router.cache.type"], havingValue = "local", matchIfMissing = true)
    fun localIdempotencyService(): IdempotencyService = CaffeineIdempotencyService()

    // ==================== API Call Log ====================

    @Bean
    fun apiCallLogFilter(
        apiCallLogService: ApiCallLogService,
        sessionInfoClient: SessionInfoClient,
    ): ApiCallLogFilter = ApiCallLogFilter(apiCallLogService, sessionInfoClient)
}

package com.agnetix.harnax.router.config

import com.agnetix.harnax.router.mapper.AgentInstanceMapper
import com.agnetix.harnax.router.mapper.SessionMappingMapper
import com.agnetix.harnax.router.service.IdempotencyService
import com.agnetix.harnax.router.service.InstanceCircuitBreaker
import com.agnetix.harnax.router.service.InstanceRegistry
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
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import java.time.Duration

@Configuration
@EnableScheduling
class RouterConfig(
    @Value("${router.proxy.connect-timeout-ms:5000}")
    private val connectTimeoutMs: Int,
    @Value("${router.proxy.read-timeout-ms:60000}")
    private val readTimeoutMs: Int,
    @Value("${router.proxy.max-in-memory-size-mb:16}")
    private val maxInMemorySizeMb: Int,
    @Value("${router.proxy.max-connections:200}")
    private val maxConnections: Int,
    @Value("${router.proxy.pending-acquire-timeout-ms:10000}")
    private val pendingAcquireTimeoutMs: Int,
) {

    @Bean
    fun webClient(): WebClient {
        val connectionProvider = ConnectionProvider.builder("router-pool")
            .maxConnections(maxConnections)
            .pendingAcquireTimeout(Duration.ofMillis(pendingAcquireTimeoutMs.toLong()))
            .pendingAcquireMaxCount(500) // Limit pending requests
            .maxIdleTime(Duration.ofSeconds(60))
            .maxLifeTime(Duration.ofMinutes(5))
            .evictInBackground(Duration.ofSeconds(30))
            .metrics(true) // Enable connection pool metrics
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
            .build()
    }

    /**
     * Local circuit breaker (per-node state for performance).
     * Used in both local and redis cache modes.
     */
    @Bean
    fun circuitBreaker(
        @Value("${router.circuit-breaker.failure-threshold:3}") failureThreshold: Int,
        @Value("${router.circuit-breaker.open-duration-ms:30000}") openDurationMs: Long,
    ): InstanceCircuitBreaker {
        return InstanceCircuitBreaker(
            failureThreshold = failureThreshold,
            openDurationMs = openDurationMs,
        )
    }

    // ==================== Redis cache mode beans ====================

    @Bean
    @ConditionalOnProperty(name = ["router.cache.type"], havingValue = "redis")
    fun instanceRegistry(
        agentInstanceMapper: AgentInstanceMapper,
        redisTemplate: RedisTemplate<String, Any>,
        @Value("${router.health.heartbeat-timeout-ms:30000}") heartbeatTimeoutMs: Long,
    ): InstanceRegistry {
        return RedisInstanceRegistry(agentInstanceMapper, redisTemplate, heartbeatTimeoutMs)
    }

    @Bean
    @ConditionalOnProperty(name = ["router.cache.type"], havingValue = "redis")
    fun sessionMappingService(
        sessionMappingMapper: SessionMappingMapper,
        instanceRegistry: InstanceRegistry,
        redisTemplate: RedisTemplate<String, Any>,
    ): SessionMappingService {
        return RedisSessionMappingService(sessionMappingMapper, instanceRegistry, redisTemplate)
    }

    @Bean
    @ConditionalOnProperty(name = ["router.cache.type"], havingValue = "redis")
    fun idempotencyService(
        redisTemplate: RedisTemplate<String, Any>,
        @Value("${router.idempotency.ttl-seconds:60}") ttlSeconds: Long,
    ): IdempotencyService {
        return RedisIdempotencyService(redisTemplate, ttlSeconds)
    }

    // ==================== Local cache mode beans ====================

    @Bean
    @ConditionalOnProperty(name = ["router.cache.type"], havingValue = "local", matchIfMissing = true)
    fun localInstanceRegistry(
        agentInstanceMapper: AgentInstanceMapper,
        @Value("${router.health.heartbeat-timeout-ms:30000}") heartbeatTimeoutMs: Long,
        @Value("${router.cache.instance-ttl-seconds:3}") instanceCacheTtlSeconds: Long,
    ): InstanceRegistry {
        return LocalInstanceRegistry(agentInstanceMapper, heartbeatTimeoutMs, instanceCacheTtlSeconds)
    }

    @Bean
    @ConditionalOnProperty(name = ["router.cache.type"], havingValue = "local", matchIfMissing = true)
    fun localSessionMappingService(
        sessionMappingMapper: SessionMappingMapper,
        instanceRegistry: InstanceRegistry,
        @Value("${router.cache.session-ttl-seconds:300}") sessionCacheTtlSeconds: Long,
    ): SessionMappingService {
        return CaffeineSessionMappingService(sessionMappingMapper, instanceRegistry, sessionCacheTtlSeconds)
    }

    @Bean
    @ConditionalOnProperty(name = ["router.cache.type"], havingValue = "local", matchIfMissing = true)
    fun localIdempotencyService(): IdempotencyService {
        return CaffeineIdempotencyService()
    }
}

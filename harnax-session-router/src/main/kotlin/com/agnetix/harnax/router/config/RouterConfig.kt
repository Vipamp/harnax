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
import com.agnetix.harnax.router.service.impl.LocalInstanceCircuitBreaker
import com.agnetix.harnax.router.service.impl.LocalInstanceRegistry
import com.agnetix.harnax.router.service.impl.RedisCircuitBreaker
import com.agnetix.harnax.router.service.impl.RedisIdempotencyService
import com.agnetix.harnax.router.service.impl.RedisInstanceRegistry
import com.agnetix.harnax.router.service.impl.RedisSessionMappingService
import io.netty.channel.ChannelOption
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.Ordered
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.web.client.RestClient
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.filter.CorsFilter
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
    @Value($$"${router.proxy.write-timeout-seconds:30}")
    private val writeTimeoutSeconds: Int,
    @Value($$"${router.cors.allowed-origins:http://localhost:*}")
    private val corsAllowedOrigins: String,
    private val tokenProvider: InternalTokenProvider,
) {

    private val log = LoggerFactory.getLogger(RouterConfig::class.java)

    @Bean
    fun webClient(): WebClient {
        // ReadTimeoutHandler takes seconds, so convert readTimeoutMs -> readTimeoutSeconds.
        val readTimeoutSeconds = (readTimeoutMs / 1000).coerceAtLeast(1)

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
                conn.addHandlerLast(io.netty.handler.timeout.ReadTimeoutHandler(readTimeoutSeconds))
                    .addHandlerLast(io.netty.handler.timeout.WriteTimeoutHandler(writeTimeoutSeconds))
            }

        return WebClient.builder()
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .codecs { config -> config.defaultCodecs().maxInMemorySize(maxInMemorySizeMb * 1024 * 1024) }
            .filter(authFilter())
            .build()
    }

    /**
     * RestClient for batch (non-streaming) requests to agent-service.
     */
    @Bean
    fun restClient(): RestClient {
        val factory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofMillis(connectTimeoutMs.toLong()))
            setReadTimeout(Duration.ofMillis(readTimeoutMs.toLong()))
        }
        return RestClient.builder()
            .requestFactory(factory)
            .requestInterceptor { request, body, execution ->
                tokenProvider.authHeaders().forEach { (key, value) -> request.headers.add(key, value) }
                execution.execute(request, body)
            }
            .build()
    }

    private fun authFilter(): ExchangeFilterFunction = ExchangeFilterFunction { request, next ->
        val headers = tokenProvider.authHeaders()
        log.debug("[authFilter] Adding auth headers for request: {} {} headers={}", request.method(), request.url(), headers.keys)
        val mutated = ClientRequest.from(request)
        headers.forEach { (key, value) -> mutated.header(key, value) }
        next.exchange(mutated.build())
    }

    @Bean
    @ConditionalOnProperty(name = ["router.cache.type"], havingValue = "redis", matchIfMissing = false)
    @Primary
    fun redisCircuitBreaker(
        redisTemplate: RedisTemplate<String, Any>,
        @Value($$"${router.circuit-breaker.failure-threshold:3}") failureThreshold: Int,
        @Value($$"${router.circuit-breaker.open-duration-ms:30000}") openDurationMs: Long,
    ): InstanceCircuitBreaker = RedisCircuitBreaker(redisTemplate, failureThreshold, openDurationMs)

    @Bean
    @ConditionalOnProperty(name = ["router.cache.type"], havingValue = "local", matchIfMissing = true)
    fun localInstanceCircuitBreaker(
        @Value($$"${router.circuit-breaker.failure-threshold:3}") failureThreshold: Int,
        @Value($$"${router.circuit-breaker.open-duration-ms:30000}") openDurationMs: Long,
    ): InstanceCircuitBreaker = LocalInstanceCircuitBreaker(failureThreshold, openDurationMs)

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

    // ==================== CORS ====================

    @Bean
    fun corsFilter(): FilterRegistrationBean<CorsFilter> {
        val origins = corsAllowedOrigins.split(",").map { it.trim() }
        log.info("CORS allowed origins: {}", origins)
        val config = CorsConfiguration().apply {
            allowedOriginPatterns = origins
            allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
            allowedHeaders = listOf("*")
            exposedHeaders = listOf("Content-Type", "X-Request-Id")
            allowCredentials = true
            maxAge = 3600
        }
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", config)
        val bean = FilterRegistrationBean(CorsFilter(source))
        bean.order = Ordered.HIGHEST_PRECEDENCE
        return bean
    }

    // ==================== API Call Log ====================

    @Bean
    fun apiCallLogFilter(
        apiCallLogService: ApiCallLogService,
        sessionInfoClient: SessionInfoClient,
    ): ApiCallLogFilter = ApiCallLogFilter(apiCallLogService, sessionInfoClient)
}

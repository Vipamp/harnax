package com.agnetix.harnax.router.config

import io.netty.channel.ChannelOption
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.scheduling.annotation.EnableScheduling
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
}

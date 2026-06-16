package com.agnetix.harnax.router.config

import io.netty.channel.ChannelOption
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
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
) {

    @Bean
    fun webClient(): WebClient {
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
            .responseTimeout(Duration.ofMillis(readTimeoutMs.toLong()))

        return WebClient.builder()
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .codecs { config -> config.defaultCodecs().maxInMemorySize(maxInMemorySizeMb * 1024 * 1024) }
            .build()
    }
}

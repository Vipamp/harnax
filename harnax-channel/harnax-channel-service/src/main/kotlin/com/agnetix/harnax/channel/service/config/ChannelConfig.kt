package com.agnetix.harnax.channel.service.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

/**
 * Channel service configuration.
 */
@Configuration
class ChannelConfig {

    @Bean
    fun webClient(): WebClient = WebClient.builder()
        .build()
}

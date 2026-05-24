package com.agnetix.harnax.router.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.web.reactive.function.client.WebClient

/**
 * Session Router configuration.
 */
@Configuration
@EnableScheduling
class RouterConfig {

    @Bean
    fun webClient(): WebClient = WebClient.builder()
        .build()
}

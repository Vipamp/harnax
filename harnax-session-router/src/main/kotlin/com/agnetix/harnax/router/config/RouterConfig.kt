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
        .codecs { config -> config.defaultCodecs().maxInMemorySize(16 * 1024 * 1024) } // 16MB for image payloads
        .build()
}

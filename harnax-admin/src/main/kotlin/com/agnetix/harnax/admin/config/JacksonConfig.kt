package com.agnetix.harnax.admin.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * Jackson JSON serialization configuration
 *
 * Configure Kotlin support to resolve isXXX field getter method recognition issue.
 * Jackson 3.x writes dates as ISO-8601 by default (WRITE_DATES_AS_TIMESTAMPS removed).
 * Java time support is built into Jackson 3.x databind (no separate JavaTimeModule needed).
 */
@Configuration
class JacksonConfig {

    @Bean
    fun objectMapper(): ObjectMapper = jacksonObjectMapper()
}

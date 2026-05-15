package com.vipamp.vipclaw.admin.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder

/**
 * Jackson JSON serialization configuration
 *
 * Configure Kotlin support to resolve isXXX field getter method recognition issue
 */
@Configuration
class JacksonConfig {

    @Bean
    fun objectMapper(): ObjectMapper = Jackson2ObjectMapperBuilder.json()
        // Register Kotlin module - resolve isXXX field serialization issue
        .modulesToInstall(KotlinModule.Builder().build())
        // Register Java 8 time module
        .modulesToInstall(JavaTimeModule())
        // Serialization configuration
        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build()
}

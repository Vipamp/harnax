package com.agnetix.harnax.scheduler.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.web.client.RestClient
import java.time.Duration

@Configuration
class SchedulerConfig {

    /**
     * RestClient bean for general HTTP calls (used by RouterClient internally).
     */
    @Bean
    fun restClient(): RestClient = RestClient.builder()
        .requestFactory(
            org.springframework.http.client.SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofSeconds(10))
                setReadTimeout(Duration.ofSeconds(60))
            },
        )
        .build()

    /**
     * Async thread pool for manual task execution.
     */
    @Bean(name = ["taskExecutor"])
    fun taskExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 2
        maxPoolSize = 10
        queueCapacity = 50
        setThreadNamePrefix("scheduler-async-")
        initialize()
    }
}

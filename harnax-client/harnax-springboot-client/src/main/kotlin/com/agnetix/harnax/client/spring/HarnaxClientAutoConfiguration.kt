package com.agnetix.harnax.client.spring

import com.agnetix.harnax.client.HarnaxClient
import com.agnetix.harnax.client.HarnaxClientConfig
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.reactive.function.client.WebClient

/**
 * Spring Boot auto-configuration for Harnax client.
 *
 * Automatically creates a [HarnaxClient] bean when the following properties are set:
 * ```yaml
 * harnax:
 *   client:
 *     base-url: http://router-host:8081
 *     api-key: your-api-key
 * ```
 */
@AutoConfiguration
@EnableConfigurationProperties(HarnaxClientProperties::class)
class HarnaxClientAutoConfiguration {

    @Bean
    fun harnaxClient(properties: HarnaxClientProperties): HarnaxClient {
        val config = HarnaxClientConfig(
            baseUrl = properties.baseUrl,
            apiKey = properties.apiKey,
            connectTimeout = properties.connectTimeout,
            readTimeout = properties.readTimeout,
            streamTimeout = properties.streamTimeout,
        )

        val restClient = buildRestClient(config)
        val webClient = buildWebClient(config)

        return SpringHarnaxClient(config, restClient, webClient)
    }

    private fun buildRestClient(config: HarnaxClientConfig): RestClient {
        val factory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(config.connectTimeout)
            setReadTimeout(config.readTimeout)
        }
        return RestClient.builder()
            .requestFactory(factory)
            .requestInterceptor { request, body, execution ->
                request.headers.add("X-Api-Key", config.apiKey)
                execution.execute(request, body)
            }
            .build()
    }

    private fun buildWebClient(config: HarnaxClientConfig): WebClient = WebClient.builder()
        .baseUrl(config.normalizedBaseUrl)
        .defaultHeader("X-Api-Key", config.apiKey)
        .build()
}

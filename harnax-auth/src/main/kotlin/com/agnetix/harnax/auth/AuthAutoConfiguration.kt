package com.agnetix.harnax.auth

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import tools.jackson.databind.ObjectMapper

@AutoConfiguration
@EnableConfigurationProperties(AuthProperties::class)
class AuthAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "harnax.auth", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun internalTokenProvider(properties: AuthProperties): InternalTokenProvider {
        val secret = properties.internal.sharedSecret
        require(secret.length >= 32) {
            "harnax.auth.internal.shared-secret must be at least 32 characters"
        }
        return InternalTokenProvider(
            serviceId = properties.serviceId,
            sharedSecret = secret,
            tokenTtlSeconds = properties.internal.tokenTtlSeconds,
        )
    }

    @Bean
    @ConditionalOnProperty(prefix = "harnax.auth", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun externalApiKeyValidator(
        properties: AuthProperties,
        apiKeyStoreProvider: ObjectProvider<ApiKeyStore>,
    ): ExternalApiKeyValidator? {
        if (!properties.external.enabled) return null
        val store = apiKeyStoreProvider.ifAvailable ?: return null
        return ExternalApiKeyValidator(store)
    }

    @Bean
    @ConditionalOnProperty(prefix = "harnax.auth", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun unifiedAuthFilter(
        tokenProvider: InternalTokenProvider,
        properties: AuthProperties,
        objectMapper: ObjectMapper,
        externalApiKeyValidator: ExternalApiKeyValidator?,
    ): UnifiedAuthFilter = UnifiedAuthFilter(
        tokenProvider = tokenProvider,
        enabled = properties.enabled,
        objectMapper = objectMapper,
        externalApiKeyValidator = externalApiKeyValidator,
        extraSkipPaths = properties.skipPaths,
    )

    @Bean
    @ConditionalOnProperty(prefix = "harnax.auth", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun internalAuthorizationInterceptor(objectMapper: ObjectMapper): InternalAuthorizationInterceptor = InternalAuthorizationInterceptor(objectMapper)

    @Bean
    @ConditionalOnProperty(prefix = "harnax.auth", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun rateLimitInterceptor(
        rateLimitCheckerProvider: ObjectProvider<RateLimitChecker>,
        objectMapper: ObjectMapper,
    ): RateLimitInterceptor? {
        val checker = rateLimitCheckerProvider.ifAvailable ?: return null
        return RateLimitInterceptor(checker, objectMapper)
    }

    @Bean
    @ConditionalOnProperty(prefix = "harnax.auth", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun authWebMvcConfigurer(
        internalInterceptor: InternalAuthorizationInterceptor,
        rateLimitInterceptor: RateLimitInterceptor?,
    ): WebMvcConfigurer = object : WebMvcConfigurer {
        override fun addInterceptors(registry: InterceptorRegistry) {
            registry.addInterceptor(internalInterceptor)
            if (rateLimitInterceptor != null) {
                registry.addInterceptor(rateLimitInterceptor)
            }
        }
    }
}

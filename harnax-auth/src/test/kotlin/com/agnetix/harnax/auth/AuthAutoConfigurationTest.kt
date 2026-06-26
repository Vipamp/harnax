package com.agnetix.harnax.auth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

class AuthAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AuthAutoConfiguration::class.java))
        .withUserConfiguration(ObjectMapperConfig::class.java)

    @Nested
    inner class DefaultConfiguration {
        @Test
        fun `creates InternalTokenProvider bean by default`() {
            contextRunner
                .withPropertyValues("harnax.auth.internal.shared-secret=this-is-a-very-secure-shared-secret-key-for-testing")
                .run { context ->
                    assertTrue(context.containsBean("internalTokenProvider"))
                }
        }

        @Test
        fun `creates UnifiedAuthFilter bean by default`() {
            contextRunner
                .withPropertyValues("harnax.auth.internal.shared-secret=this-is-a-very-secure-shared-secret-key-for-testing")
                .run { context ->
                    assertTrue(context.containsBean("unifiedAuthFilter"))
                }
        }

        @Test
        fun `creates InternalAuthorizationInterceptor bean`() {
            contextRunner
                .withPropertyValues("harnax.auth.internal.shared-secret=this-is-a-very-secure-shared-secret-key-for-testing")
                .run { context ->
                    assertTrue(context.containsBean("internalAuthorizationInterceptor"))
                }
        }

        @Test
        fun `does not create ExternalApiKeyValidator when external disabled`() {
            contextRunner
                .withPropertyValues(
                    "harnax.auth.internal.shared-secret=this-is-a-very-secure-shared-secret-key-for-testing",
                    "harnax.auth.external.enabled=false",
                )
                .run { context ->
                    assertNull(context.getBeanProvider(ExternalApiKeyValidator::class.java).getIfAvailable())
                }
        }

        @Test
        fun `does not create ExternalApiKeyValidator when no ApiKeyStore bean`() {
            contextRunner
                .withPropertyValues(
                    "harnax.auth.internal.shared-secret=this-is-a-very-secure-shared-secret-key-for-testing",
                    "harnax.auth.external.enabled=true",
                )
                .run { context ->
                    assertNull(context.getBeanProvider(ExternalApiKeyValidator::class.java).getIfAvailable())
                }
        }

        @Test
        fun `creates ExternalApiKeyValidator when enabled with ApiKeyStore`() {
            contextRunner
                .withUserConfiguration(TestApiKeyStoreConfig::class.java)
                .withPropertyValues(
                    "harnax.auth.internal.shared-secret=this-is-a-very-secure-shared-secret-key-for-testing",
                    "harnax.auth.external.enabled=true",
                )
                .run { context ->
                    assertTrue(context.containsBean("externalApiKeyValidator"))
                }
        }
    }

    @Nested
    inner class DisabledAuth {
        @Test
        fun `no beans created when auth disabled`() {
            contextRunner
                .withPropertyValues(
                    "harnax.auth.enabled=false",
                    "harnax.auth.internal.shared-secret=this-is-a-very-secure-shared-secret-key-for-testing",
                )
                .run { context ->
                    assertFalse(context.containsBean("internalTokenProvider"))
                    assertFalse(context.containsBean("unifiedAuthFilter"))
                    assertFalse(context.containsBean("internalAuthorizationInterceptor"))
                }
        }
    }

    @Nested
    inner class SecretValidation {
        @Test
        fun `fails when secret is too short`() {
            contextRunner
                .withPropertyValues("harnax.auth.internal.shared-secret=too-short")
                .run { context ->
                    assertNotNull(context.startupFailure, "Should fail with short secret")
                }
        }
    }

    @Nested
    inner class RateLimitInterceptorBean {
        @Test
        fun `no RateLimitInterceptor when no RateLimitChecker bean`() {
            contextRunner
                .withPropertyValues("harnax.auth.internal.shared-secret=this-is-a-very-secure-shared-secret-key-for-testing")
                .run { context ->
                    assertNull(context.getBeanProvider(RateLimitInterceptor::class.java).getIfAvailable())
                }
        }

        @Test
        fun `creates RateLimitInterceptor when RateLimitChecker bean exists`() {
            contextRunner
                .withUserConfiguration(TestRateLimitCheckerConfig::class.java)
                .withPropertyValues("harnax.auth.internal.shared-secret=this-is-a-very-secure-shared-secret-key-for-testing")
                .run { context ->
                    assertTrue(context.containsBean("rateLimitInterceptor"))
                }
        }
    }

    @Nested
    inner class WebMvcConfigurerBean {
        @Test
        fun `creates authWebMvcConfigurer`() {
            contextRunner
                .withPropertyValues("harnax.auth.internal.shared-secret=this-is-a-very-secure-shared-secret-key-for-testing")
                .run { context ->
                    assertTrue(context.containsBean("authWebMvcConfigurer"))
                }
        }
    }

    @Configuration
    class ObjectMapperConfig {
        @Bean
        fun objectMapper(): ObjectMapper = ObjectMapper()
    }

    @Configuration
    class TestApiKeyStoreConfig {
        @Bean
        fun apiKeyStore(): ApiKeyStore = org.mockito.Mockito.mock(ApiKeyStore::class.java)
    }

    @Configuration
    class TestRateLimitCheckerConfig {
        @Bean
        fun rateLimitChecker(): RateLimitChecker = org.mockito.Mockito.mock(RateLimitChecker::class.java)
    }
}

package com.agnetix.harnax.scheduler.config

import com.agnetix.harnax.auth.InternalTokenProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import java.time.Duration

@Configuration
class SchedulerConfig {

    /**
     * The key this service verifies admin's internal JWTs with, and the one it signs its own outbound calls
     * with — the same three `harnax.auth` keys every other Harnax service reads.
     *
     * It is declared by hand because [com.agnetix.harnax.auth.AuthAutoConfiguration] cannot supply it here:
     * that module's `internalTokenProvider` bean (AuthAutoConfiguration.kt:16-28) is
     * `@ConditionalOnProperty(prefix = "harnax.auth", name = ["enabled"], havingValue = "true", matchIfMissing = true)`
     * and this service runs with `harnax.auth.enabled: false`, so the bean is not created — it is absent, not
     * created-and-inert. Enabling the flag is not the fix: the same condition also builds `unifiedAuthFilter`,
     * which would bring external API keys, rate limiting and `@InternalOnly` into a service whose design is
     * "verify one service bearer, nothing else" (spec §2.3, and §9 F1 for the full version).
     *
     * The requirement below is the auto-configuration's, copied rather than dropped: a short secret here does
     * not weaken one endpoint, it silently makes every replica refuse every forward from admin, which is a
     * boot-time failure and should look like one. The key itself comes from `application.yml`, so the empty
     * default only bites a context started without that file — which is exactly what
     * `SchedulerBeanGraphTest` hands it explicitly.
     */
    @Bean
    fun internalTokenProvider(
        @Value("\${harnax.auth.service-id:scheduler}") serviceId: String,
        @Value("\${harnax.auth.internal.shared-secret:}") sharedSecret: String,
        @Value("\${harnax.auth.internal.token-ttl-seconds:300}") tokenTtlSeconds: Long,
    ): InternalTokenProvider {
        require(sharedSecret.length >= MIN_SECRET_LENGTH) {
            "harnax.auth.internal.shared-secret must be at least $MIN_SECRET_LENGTH characters"
        }
        return InternalTokenProvider(
            serviceId = serviceId,
            sharedSecret = sharedSecret,
            tokenTtlSeconds = tokenTtlSeconds,
        )
    }

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

    companion object {
        private const val MIN_SECRET_LENGTH = 32
    }
}

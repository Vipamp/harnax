package com.agnetix.harnax.router.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Refuses to boot a cluster node whose signing key is the value published in this repository.
 *
 * The placeholder is convenient locally and fatal in a deployment: any caller who can read the repo
 * can mint a token that this router accepts as an internal service, which is the same as having no
 * authentication at all — while the monitor endpoints, `@InternalOnly` registration and the API-key
 * path all rely on that secret for their decision.
 *
 * Only `redis` mode is checked. That is the mode meant to be deployed and to share state; a single
 * local node holding its routing table in memory gains nothing from a strong secret and losing its
 * startup over one would just push people to disable authentication instead.
 */
@Component
class PlaceholderSecretCheck(
    @Value("\${router.cache.type:local}")
    private val cacheType: String,
    @Value("\${harnax.auth.enabled:true}")
    private val authEnabled: Boolean,
    @Value("\${harnax.auth.internal.shared-secret:}")
    private val sharedSecret: String,
    @Value("\${admin.internal-api.secret:}")
    private val adminApiSecret: String,
) {

    private val log = LoggerFactory.getLogger(PlaceholderSecretCheck::class.java)

    @PostConstruct
    fun verify() {
        if (!authEnabled || !cacheType.equals("redis", ignoreCase = true)) {
            return
        }

        val placeholders = buildList {
            if (sharedSecret == PLACEHOLDER) add("harnax.auth.internal.shared-secret (HARNAX_AUTH_SECRET)")
            if (adminApiSecret == PLACEHOLDER) add("admin.internal-api.secret (ADMIN_INTERNAL_API_SECRET)")
        }
        if (placeholders.isEmpty()) {
            return
        }

        log.error(
            "Refusing to start a cluster router with the repository's placeholder secret — set {} to a " +
                "unique value of at least 32 characters (docker: fill docker-new/.env, see .env.example).",
            placeholders.joinToString(" and "),
        )
        error(
            "Placeholder authentication secret in redis mode: ${placeholders.joinToString(", ")}. " +
                "Anyone able to read the repository could present a valid internal token to this router.",
        )
    }

    companion object {
        /** The fallback shipped in `application*.yml`. */
        private const val PLACEHOLDER = "change-me-in-production-min-32-chars!!"
    }
}

package com.agnetix.harnax.auth

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "harnax.auth")
data class AuthProperties(
    val enabled: Boolean = true,
    val serviceId: String = "",
    val internal: InternalProperties = InternalProperties(),
    val external: ExternalProperties = ExternalProperties(),
    /** Extra path prefixes to skip authentication (e.g. public auth endpoints, swagger) */
    val skipPaths: List<String> = emptyList(),
)

data class InternalProperties(
    val sharedSecret: String = "",
    val tokenTtlSeconds: Long = 300,
)

data class ExternalProperties(
    val enabled: Boolean = false,
)

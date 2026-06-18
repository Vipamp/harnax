package com.agnetix.harnax.auth

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "harnax.auth")
data class AuthProperties(
    val enabled: Boolean = true,
    val serviceId: String = "",
    val internal: InternalProperties = InternalProperties(),
    val external: ExternalProperties = ExternalProperties(),
)

data class InternalProperties(
    val sharedSecret: String = "",
    val tokenTtlSeconds: Long = 300,
)

data class ExternalProperties(
    val enabled: Boolean = false,
)

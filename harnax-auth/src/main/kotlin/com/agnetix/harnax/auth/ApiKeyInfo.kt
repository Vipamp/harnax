package com.agnetix.harnax.auth

import java.time.Instant

/**
 * API Key 信息模型
 */
data class ApiKeyInfo(
    val name: String,
    val keyHash: String,
    val scopes: Set<String>,
    val tenantId: Long?,
    val rateLimitPerMinute: Int?,
    val enabled: Boolean,
    val expiresAt: Instant?,
) {
    fun isExpired(): Boolean = expiresAt?.isBefore(Instant.now()) ?: false
}

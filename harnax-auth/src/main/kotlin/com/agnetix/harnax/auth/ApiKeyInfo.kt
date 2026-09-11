package com.agnetix.harnax.auth

import java.time.Instant

/**
 * API Key 信息模型
 */
data class ApiKeyInfo(
    val name: String,
    /** Owner of the key (`api_key.user_id`); null for SYSTEM keys, which have no human behind them. */
    val userId: Long? = null,
    val keyHash: String,
    val scopes: Set<String>,
    val tenantId: Long?,
    val rateLimitPerMinute: Int?,
    val enabled: Boolean,
    val expiresAt: Instant?,
) {
    fun isExpired(): Boolean = expiresAt?.isBefore(Instant.now()) ?: false
}

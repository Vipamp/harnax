package com.agnetix.harnax.auth

import java.security.MessageDigest

/**
 * 外部 API Key 校验器
 */
class ExternalApiKeyValidator(
    private val apiKeyStore: ApiKeyStore,
) {
    fun validate(apiKey: String): AuthContext {
        val keyHash = sha256(apiKey)
        val keyInfo = apiKeyStore.findByKeyHash(keyHash)
            ?: throw SecurityException("Invalid API key")

        if (!keyInfo.enabled) {
            throw SecurityException("API key is disabled")
        }

        if (keyInfo.isExpired()) {
            throw SecurityException("API key has expired")
        }

        return AuthContext(
            callerId = keyInfo.name,
            scopes = keyInfo.scopes,
            callerType = CallerType.EXTERNAL_API,
            tenantId = keyInfo.tenantId,
            rateLimitPerMinute = keyInfo.rateLimitPerMinute,
        )
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

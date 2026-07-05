package com.agnetix.harnax.router.service

import com.agnetix.harnax.auth.ApiKeyInfo
import com.agnetix.harnax.auth.ApiKeyStore
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Optional
import java.util.concurrent.TimeUnit

@Component
@Primary
class RemoteApiKeyStore(
    private val adminClientService: AdminClientService,
) : ApiKeyStore {

    private val log = LoggerFactory.getLogger(RemoteApiKeyStore::class.java)

    /**
     * Uses Optional<ApiKeyInfo> to cache both hits and misses (null → empty Optional).
     * Atomic loading via Caffeine.get(key) { loader } prevents cache stampede.
     */
    private val cache = Caffeine.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .build<String, Optional<ApiKeyInfo>>()

    override fun findByKeyHash(keyHash: String): ApiKeyInfo? = cache.get(keyHash) { hash ->
        val info = fetchFromAdmin(hash)
        Optional.ofNullable(info)
    }.orElse(null)

    private fun fetchFromAdmin(hash: String): ApiKeyInfo? {
        return try {
            val data = runBlocking { adminClientService.validateApiKey(hash) } ?: return null
            ApiKeyInfo(
                name = data.name,
                keyHash = data.keyHash,
                scopes = data.scopes.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet(),
                tenantId = data.tenantId,
                rateLimitPerMinute = data.rateLimit,
                enabled = data.enabled,
                expiresAt = data.expiresAt?.let {
                    try {
                        Instant.parse(it)
                    } catch (_: Exception) {
                        LocalDateTime.parse(it).atZone(ZoneId.systemDefault()).toInstant()
                    }
                },
            )
        } catch (e: Exception) {
            log.warn("[Router→Admin] Failed to validate API key: ${e.message}")
            null
        }
    }
}

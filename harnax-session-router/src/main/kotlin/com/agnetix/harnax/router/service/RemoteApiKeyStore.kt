package com.agnetix.harnax.router.service

import com.agnetix.harnax.auth.ApiKeyInfo
import com.agnetix.harnax.auth.ApiKeyStore
import com.agnetix.harnax.auth.InternalTokenProvider
import com.agnetix.harnax.common.dto.ResultVo
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate
import java.time.Instant
import java.util.concurrent.TimeUnit

@Component
class RemoteApiKeyStore(
    @Value("\${admin.service.url:http://localhost:8080}")
    private val adminUrl: String,
    private val tokenProvider: InternalTokenProvider,
) : ApiKeyStore {

    private val log = LoggerFactory.getLogger(RemoteApiKeyStore::class.java)
    private val restTemplate = RestTemplate()

    private val cache = Caffeine.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .build<String, ApiKeyInfo?>()

    override fun findByKeyHash(keyHash: String): ApiKeyInfo? {
        cache.asMap()[keyHash]?.let { return it }

        val info = fetchFromAdmin(keyHash) ?: return null
        cache.put(keyHash, info)
        return info
    }

    private fun fetchFromAdmin(hash: String): ApiKeyInfo? {
        return try {
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_JSON
            tokenProvider.authHeaders("admin:apikey").forEach { (key, value) ->
                headers.set(key, value)
            }

            val body = mapOf("keyHash" to hash)
            val request = HttpEntity(body, headers)

            val response = restTemplate.exchange(
                "$adminUrl/api/internal/api-keys/validate",
                org.springframework.http.HttpMethod.POST,
                request,
                object : ParameterizedTypeReference<ResultVo<ApiKeyValidateResponse?>>() {},
            )

            val data = response.body?.data ?: return null

            ApiKeyInfo(
                name = data.name,
                keyHash = data.keyHash,
                scopes = data.scopes.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet(),
                tenantId = data.tenantId,
                rateLimitPerMinute = data.rateLimit,
                enabled = data.enabled,
                expiresAt = data.expiresAt?.let { Instant.parse(it) },
            )
        } catch (e: Exception) {
            log.warn("Failed to validate API key from admin: ${e.message}")
            null
        }
    }

    data class ApiKeyValidateResponse(
        val name: String = "",
        val keyHash: String = "",
        val scopes: String = "",
        val tenantId: Long? = null,
        val rateLimit: Int = 60,
        val enabled: Boolean = true,
        val expiresAt: String? = null,
    )
}

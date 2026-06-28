package com.agnetix.harnax.router.service

import com.agnetix.harnax.auth.ApiKeyInfo
import com.agnetix.harnax.auth.ApiKeyStore
import com.agnetix.harnax.common.dto.ResultVo
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Primary
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

@Component
@Primary
class RemoteApiKeyStore(
    @Value("\${admin.service.url:http://localhost:8080}")
    private val adminUrl: String,
    @Value("\${admin.internal-api.secret:}")
    private val adminSecret: String,
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
            headers.set("Authorization", "Bearer $adminSecret")

            val body = mapOf("keyHash" to hash)
            val request = HttpEntity(body, headers)

            val response = restTemplate.exchange(
                "$adminUrl/api/admin/internal/api-keys/validate",
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
                expiresAt = data.expiresAt?.let {
                    try {
                        Instant.parse(it)
                    } catch (_: Exception) {
                        LocalDateTime.parse(it).atZone(ZoneId.systemDefault()).toInstant()
                    }
                },
            )
        } catch (e: HttpClientErrorException) {
            val status = e.statusCode.value()
            val body = e.responseBodyAsString.take(200)
            if (status == 401) {
                log.error(
                    "[Router→Admin] Authentication failed (401) calling $adminUrl/api/admin/internal/api-keys/validate. " +
                        "Check admin.internal-api.secret matches admin's config. Response: $body",
                )
            } else {
                log.warn("[Router→Admin] HTTP $status calling $adminUrl/api/admin/internal/api-keys/validate. Response: $body")
            }
            null
        } catch (e: Exception) {
            log.warn("[Router→Admin] Failed to validate API key from $adminUrl: ${e.message}")
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

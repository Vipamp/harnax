package com.agnetix.harnax.router.service

import com.agnetix.harnax.auth.InternalTokenProvider
import com.agnetix.harnax.common.dto.ResultVo
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate
import java.util.concurrent.TimeUnit

data class SessionInfo(
    val sessionId: String = "",
    val agentId: Long? = null,
    val agentName: String? = null,
    val modelId: Long? = null,
    val modelName: String? = null,
    val tenantId: Long? = null,
)

@Component
class SessionInfoClient(
    @Value("\${admin.service.url:http://localhost:8080}")
    private val adminUrl: String,
    private val tokenProvider: InternalTokenProvider,
) {

    private val log = LoggerFactory.getLogger(SessionInfoClient::class.java)
    private val restTemplate = RestTemplate()

    private val cache = Caffeine.newBuilder()
        .maximumSize(5000)
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .build<String, SessionInfo?>()

    fun getSessionInfo(sessionId: String): SessionInfo? {
        return cache.get(sessionId) { sid ->
            try {
                val headers = HttpHeaders()
                tokenProvider.authHeaders("admin:session").forEach { (key, value) ->
                    headers.set(key, value)
                }

                val request = HttpEntity<Void>(headers)

                val response = restTemplate.exchange(
                    "$adminUrl/api/internal/sessions/$sid/info",
                    HttpMethod.GET,
                    request,
                    object : ParameterizedTypeReference<ResultVo<SessionInfo?>>() {},
                )

                response.body?.data
            } catch (e: Exception) {
                log.warn("Failed to get session info from admin for $sid: ${e.message}")
                null
            }
        }
    }
}

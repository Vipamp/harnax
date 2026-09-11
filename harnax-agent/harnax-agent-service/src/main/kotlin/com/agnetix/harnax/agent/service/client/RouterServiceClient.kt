package com.agnetix.harnax.agent.service.client

import com.agnetix.harnax.auth.AuthRestTemplateInterceptor
import com.agnetix.harnax.auth.InternalTokenProvider
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate

/**
 * Centralized HTTP client for all calls from agent-service to session-router.
 *
 * Encapsulates RestTemplate setup (with internal auth interceptor) and
 * all router API endpoints, so callers never deal with raw HTTP details.
 */
@Component
class RouterServiceClient(
    @Value($$"${router.service.url}") private val routerUrl: String,
    private val tokenProvider: InternalTokenProvider,
) {

    private val log = LoggerFactory.getLogger(RouterServiceClient::class.java)
    private val restTemplate = RestTemplate()

    @PostConstruct
    fun init() {
        restTemplate.interceptors.add(AuthRestTemplateInterceptor(tokenProvider))
    }

    /**
     * Register this agent-service instance with the session-router.
     *
     * @return true if registration succeeded, false otherwise
     */
    fun registerInstance(instanceId: String, host: String, port: Int): Boolean = try {
        val body = restTemplate.postForObject(
            "$routerUrl/api/router/instance/register?instanceId=$instanceId&host=$host&port=$port",
            null,
            Map::class.java,
        )
        if (!isRouterSuccess(body)) {
            log.error(
                "Router rejected registration for instance '$instanceId': ${routerMessage(body)}",
            )
            false
        } else {
            true
        }
    } catch (e: Exception) {
        log.warn("Failed to register instance '$instanceId' with router at $routerUrl: ${e.message}")
        false
    }

    /**
     * Send a heartbeat to the session-router for the given instance.
     *
     * @return true if the router still knows this instance; false means the caller must
     *         re-register, which also covers a plain connectivity failure.
     */
    fun sendHeartbeat(instanceId: String): Boolean = try {
        val body = restTemplate.postForObject(
            "$routerUrl/api/router/instance/heartbeat?instanceId=$instanceId",
            null,
            Map::class.java,
        )
        if (!isRouterSuccess(body)) {
            log.warn(
                "Router did not accept heartbeat for instance '$instanceId' (${routerMessage(body)})" +
                    " - registration is gone, will re-register",
            )
            false
        } else {
            true
        }
    } catch (e: Exception) {
        log.warn("Heartbeat failed for instance '$instanceId': ${e.message}")
        false
    }

    /** Returns the configured router base URL (useful for logging). */
    fun getRouterUrl(): String = routerUrl

    /**
     * Router endpoints report business failures as HTTP 200 with a non-200 `code` in the
     * ResultVo envelope, so the HTTP status alone is not enough.
     */
    private fun isRouterSuccess(body: Map<*, *>?): Boolean = (body?.get("code") as? Number)?.toInt() == 200

    private fun routerMessage(body: Map<*, *>?): String = body?.get("message")?.toString() ?: "no response body"
}

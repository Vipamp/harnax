package com.agnetix.harnax.scheduler.client

import com.agnetix.harnax.agent.protocol.ChatAgentRequest
import com.agnetix.harnax.agent.protocol.ChatResponse
import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.agent.protocol.CommandResponse
import com.agnetix.harnax.agent.protocol.CommandType
import com.agnetix.harnax.common.dto.ResultVo
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Duration

@Component
class RouterClient(
    @Value($$"${scheduler.router-url:http://localhost:8081}") private val routerUrl: String,
    @Value($$"${scheduler.api-key:}") private val configuredApiKey: String,
    @Value($$"${scheduler.admin-url:http://localhost:8080}") private val adminUrl: String,
    @Value($$"${scheduler.admin-secret:}") private val adminSecret: String,
    @Value($$"${scheduler.timeout-seconds:300}") private val timeoutSeconds: Int,
    /**
     * Ceiling for the session-cleanup read timeout. `scheduler.timeout-seconds` is the execution's own
     * clock and stays the one [chat] runs on; see [clearSessionReadTimeoutSeconds] for why the cleanup
     * gets a budget of its own instead.
     */
    @Value($$"${scheduler.clear-session-timeout-seconds:60}") private val clearSessionTimeoutSeconds: Int,
    /**
     * Ceiling for the [sendCommand] read timeout, for the reasons in [sendCommand] — chiefly that admin
     * stops listening for the stop's answer long before an execution is over.
     */
    @Value($$"${scheduler.command-timeout-seconds:10}") private val commandTimeoutSeconds: Int,
) {
    private val log = LoggerFactory.getLogger(RouterClient::class.java)

    /** Resolved API key: configuredApiKey if set, otherwise auto-fetched from admin. */
    private lateinit var apiKey: String

    @PostConstruct
    fun init() {
        apiKey = if (configuredApiKey.isNotBlank()) {
            log.info("[Scheduler] Using configured API key from environment")
            configuredApiKey
        } else {
            log.info("[Scheduler] API key not configured, auto-fetching SYSTEM key from admin at {}", adminUrl)
            fetchSystemKeyFromAdmin()
        }
    }

    private fun fetchSystemKeyFromAdmin(): String {
        val client = RestClient.builder().baseUrl(adminUrl).build()
        val maxRetries = 5
        val retryDelayMs = 3000L

        for (attempt in 1..maxRetries) {
            try {
                val response: Map<String, Any?>? = client.post()
                    .uri("/api/admin/internal/api-keys/system-key")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer $adminSecret")
                    .body(mapOf("serviceName" to "scheduler"))
                    .retrieve()
                    .body(object : ParameterizedTypeReference<Map<String, Any?>>() {})

                val data = response?.get("data") as? Map<*, *>
                val rawKey = data?.get("rawKey") as? String
                if (rawKey != null) {
                    log.info("[Scheduler] SYSTEM API key fetched successfully, keyPrefix={}", data["keyPrefix"])
                    return rawKey
                }

                // Admin returned null data — key not yet initialized
                log.warn("[Scheduler] Admin returned no system key for scheduler (attempt {}/{}). initSystemKeys may not have completed yet.", attempt, maxRetries)
            } catch (e: Exception) {
                log.warn("[Scheduler] Failed to fetch system key from admin (attempt {}/{}): {}", attempt, maxRetries, e.message)
            }

            if (attempt < maxRetries) {
                Thread.sleep(retryDelayMs)
            }
        }

        throw IllegalStateException(
            "Failed to fetch SYSTEM API key from admin after $maxRetries attempts. " +
                "Ensure admin is running and initSystemKeys() has completed.",
        )
    }

    private val restClient: RestClient by lazy { requestClient(timeoutSeconds.toLong()) }

    /** [clearSession]'s own template, on the capped clock: see [clearSessionReadTimeoutSeconds]. */
    private val cleanupClient: RestClient by lazy {
        requestClient(clearSessionReadTimeoutSeconds(timeoutSeconds, clearSessionTimeoutSeconds))
    }

    /**
     * [sendCommand]'s own template, on the clock the operator configures. Why it is not [restClient] is
     * the chain budget in [sendCommand].
     */
    private val commandClient: RestClient by lazy { requestClient(commandTimeoutSeconds.toLong()) }

    private fun requestClient(readTimeoutSeconds: Long): RestClient {
        val factory = org.springframework.http.client.SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(10))
            setReadTimeout(Duration.ofSeconds(readTimeoutSeconds))
        }
        return RestClient.builder()
            .requestFactory(factory)
            .build()
    }

    fun chat(sessionId: String, message: String): ChatResponse {
        val request = ChatAgentRequest(sessionId = sessionId, message = message)
        val url = "$routerUrl/api/router/agent/chat"

        log.info("[Scheduler→Router] POST {} - sessionId: {}", url, sessionId)
        val startTime = System.currentTimeMillis()

        val resultVo: ResultVo<ChatResponse>? = try {
            restClient.post()
                .uri(url)
                .header("X-Api-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(object : ParameterizedTypeReference<ResultVo<ChatResponse>>() {})
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            log.error("[Scheduler←Router] Exception for session={}, elapsed={}ms: {}", sessionId, elapsed, e.message, e)
            throw RuntimeException("Failed to call router: ${e.message}", e)
        }

        val elapsed = System.currentTimeMillis() - startTime

        if (resultVo == null || resultVo.code != 200 || resultVo.data == null) {
            val errorMsg = resultVo?.message ?: "No response from router"
            log.error("[Scheduler←Router] Error for session={}, elapsed={}ms: {}", sessionId, elapsed, errorMsg)
            throw RuntimeException("Router returned error: $errorMsg")
        }

        log.info("[Scheduler\u2190Router] Success for session={}, elapsed={}ms", sessionId, elapsed)
        return resultVo.data!!
    }

    /**
     * Send a command to a session through the router.
     * @return what the call learned — see [CommandDelivery]. Deliberately not a boolean: "nothing is
     * running" and "we could not ask" must not arrive as the same answer, or a router blip lets the
     * scheduler close a live execution out as stopped.
     *
     * On [commandClient], and the ceiling has one constraint behind it: this call sits in the middle of a
     * request a user is waiting on. Admin forwards `/stop` with a 30s read timeout
     * (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SchedulerClientImpl.kt`), so
     * the whole chain — this call's 10s connect allowance plus its read timeout, plus the two or three
     * `agent_task_log` writes `SchedulerServiceImpl.stopTask` does around it — has to answer inside those
     * 30s. 10s leaves room for all of that; the 300s execution clock on `restClient` did not, and the
     * failure had two halves: the user saw a failed stop at 30s while this thread kept parking to 300s
     * with the outcome still unknown.
     *
     * A timeout here is a transport failure and takes the [CommandDelivery.Unanswered] branch below like
     * any other exception — never a [CommandDelivery.Missed]. That is the reason this budget exists
     * rather than being folded into the execution's: giving up sooner must not let a slow router read as
     * "no live execution", or the stop would close out a run that is still producing a result.
     */
    fun sendCommand(sessionId: String, command: CommandType): CommandDelivery {
        val url = "$routerUrl/api/router/agent/command"
        log.info("[Scheduler→Router] POST {} - command: {}", url, command)
        val response = try {
            commandClient.post()
                .uri(url)
                .header("X-Api-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(CommandAgentRequest(sessionId = sessionId, command = command))
                .retrieve()
                .body(object : ParameterizedTypeReference<ResultVo<CommandResponse>>() {})
        } catch (e: Exception) {
            log.warn(
                "[Scheduler←Router] Command {} for session={} never got through ({}): whether an execution is live is unknown",
                command,
                sessionId,
                e.message,
            )
            return CommandDelivery.Unanswered(e.message ?: e.javaClass.simpleName)
        }

        return CommandDelivery.from(response).also { delivery ->
            when (delivery) {
                CommandDelivery.Delivered ->
                    log.info("[Scheduler←Router] Command {} delivered for session={}", command, sessionId)

                is CommandDelivery.Missed -> log.warn(
                    "[Scheduler←Router] Command {} found no live execution for session={}: {}",
                    command,
                    sessionId,
                    delivery.message ?: "no message",
                )

                is CommandDelivery.Unanswered -> log.warn(
                    "[Scheduler←Router] Command {} for session={} got no usable answer: {}",
                    command,
                    sessionId,
                    delivery.reason,
                )
            }
        }
    }

    /**
     * Clear agent session cache on agent-service.
     * Called after task session completes to clean up cached agent.
     *
     * Best-effort, and on a clock of its own ([cleanupClient]): this is the tail of an execution, so every
     * second it spends is a second the container's `stop_grace_period` has to cover for the whole run.
     */
    fun clearSession(sessionId: String) {
        val url = "$routerUrl/api/router/agent/session/$sessionId"
        log.info("[Scheduler\u2192Router] DELETE {} - clearing session", url)
        try {
            cleanupClient.delete()
                .uri(url)
                .header("X-Api-Key", apiKey)
                .retrieve()
                .toBodilessEntity()
            log.info("[Scheduler\u2190Router] Cleared session: {}", sessionId)
        } catch (e: Exception) {
            log.warn("[Scheduler\u2190Router] Failed to clear session={}, error={}", sessionId, e.message)
        }
    }

    companion object {
        /**
         * How long [clearSession] may take on top of the router call. It gets a budget of its own because
         * it is the tail of an execution: sharing the chat timeout, one run can hold its Quartz worker for
         * `2 x scheduler.timeout-seconds`, and `stop_grace_period` — which is derived from the chat
         * timeout plus the cleanup plus the write-back — would cut the JVM off mid-DELETE and leave a
         * settled log row whose lock row still reads 0. The cleanup is already best-effort, so a shorter
         * timeout changes no semantics.
         */
        internal fun clearSessionReadTimeoutSeconds(timeoutSeconds: Int, capSeconds: Int): Long = minOf(capSeconds.toLong(), timeoutSeconds.toLong())
    }
}

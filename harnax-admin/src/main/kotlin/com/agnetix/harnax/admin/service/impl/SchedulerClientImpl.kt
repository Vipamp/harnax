package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.service.SchedulerClient
import com.agnetix.harnax.auth.AuthRestTemplateInterceptor
import com.agnetix.harnax.auth.InternalTokenProvider
import com.agnetix.harnax.common.dto.ResultVo
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.time.Duration

@Service
class SchedulerClientImpl(
    @Value("\${harnax.scheduler.url:http://localhost:8084}") private val schedulerUrls: String,
    private val tokenProvider: InternalTokenProvider,
) : SchedulerClient {

    private val log = LoggerFactory.getLogger(SchedulerClientImpl::class.java)

    /** Parse comma-separated URLs for multi-instance deployment */
    private val urls: List<String> by lazy {
        schedulerUrls.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    private val restClient: RestClient by lazy {
        val factory = org.springframework.http.client.SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(5))
            setReadTimeout(Duration.ofSeconds(30))
        }
        RestClient.builder()
            .requestFactory(factory)
            // The scheduler does NOT run our auth filter (harnax.auth.enabled=false there); this
            // bearer is forward-compat only. S3 adds an internal-token interceptor that validates it.
            .requestInterceptor(AuthRestTemplateInterceptor(tokenProvider))
            .build()
    }

    override fun triggerTask(id: Long): ResultVo<Void> {
        // Send to first available instance only (execution guard handles cluster dedup)
        val url = urls.firstOrNull() ?: return ResultVo.error("No scheduler URL configured")
        return postToInstance(url, "/api/scheduler/tasks/$id/trigger")
    }

    override fun startTask(id: Long): ResultVo<Void> = broadcast("/api/scheduler/tasks/$id/start")

    override fun pauseTask(id: Long): ResultVo<Void> = broadcast("/api/scheduler/tasks/$id/pause")

    override fun reloadTasks(): ResultVo<Void> = broadcast("/api/scheduler/reload")

    override fun stopTask(logId: Long): ResultVo<Void> = broadcast("/api/scheduler/tasks/logs/$logId/stop")

    /**
     * DEBT (recorded, not fixed): the `anySuccess` fold below is not a design, it is the reason this
     * client cannot go to a second instance as it stands. With N > 1 it lies three ways:
     * 1. **40903 becomes a 200.** A node with `scheduler.enabled=false` answers every scheduling write
     *    with `CODE_SCHEDULER_DISABLED`; one healthy node answers 200, and the fold reports success. The
     *    disabled instance — the thing that code exists to surface — silently disappears.
     * 2. **A half-failed reload is reported as a clean sync.** `/reload` returns an error when active
     *    tasks could not be registered, and `CODE_SCHEDULER_SYNC_FAILED` (40902) is admin's way of
     *    saying "saved, but no scheduler took it" precisely because that must be distinguishable from
     *    "not saved". The fold lets instance A's success cover instance B's drift, so 40902 never fires
     *    and B keeps serving stale jobs.
     * 3. **A stop is applied N times, and repeat INTERRUPT now has load-bearing semantics.** Since G5 an
     *    instance answers a command for a session it has nothing live on with a *miss*, and
     *    `stopTask` turns that miss into an immediate 4 -> 5 close-out. So the second node to receive
     *    the same stop is no longer harmless: it observes "nothing is running" (the first node just
     *    cancelled it), writes 5 itself, and steals the close-out from the thread that owns the
     *    execution — whose real result then matches no guarded UPDATE and is dropped. This is the
     *    single-URL world hiding an actual bug.
     *
     * `triggerTask` above is already unicast; these four are not, because pre-S2 each node keeps its own
     * RAM store and start/pause/reload genuinely must reach every one of them. **Before S2 gives the
     * cluster a shared JDBC store, this has to become unicast or an explicit two-instance call with
     * per-node results reported** — the store stops being per-node, and so does the need to broadcast.
     * Unreachable today by construction: compose sets one `HARNAX_SCHEDULER_URL` (`http://scheduler:8084`),
     * so the `urls.size == 1` early return always wins and nothing is ever folded. Scaling that service
     * name instead leans on Docker's DNS round-robin, which picks one arbitrary instance per call — a
     * different problem, and one this client does not see either.
     */
    private fun broadcast(path: String): ResultVo<Void> {
        if (urls.size == 1) return postToInstance(urls[0], path)

        var lastError: String? = null
        var anySuccess = false
        for (url in urls) {
            val result = postToInstance(url, path)
            if (result.code == 200) {
                anySuccess = true
            } else {
                lastError = result.message
                log.warn("Scheduler instance {} failed for {}: {}", url, path, result.message)
            }
        }
        return if (anySuccess) ResultVo.success() else ResultVo.error(lastError ?: "All scheduler instances failed")
    }

    private fun postToInstance(baseUrl: String, path: String): ResultVo<Void> = try {
        val url = "$baseUrl$path"
        log.info("Proxying request to scheduler: {}", url)

        val result = restClient.post()
            .uri(url)
            .contentType(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(object : ParameterizedTypeReference<ResultVo<Void>>() {})

        result ?: ResultVo.error("No response from scheduler")
    } catch (e: Exception) {
        log.error("Failed to proxy request to scheduler {}: {}", baseUrl, e.message, e)
        ResultVo.error("Scheduler service unavailable: ${e.message}")
    }
}

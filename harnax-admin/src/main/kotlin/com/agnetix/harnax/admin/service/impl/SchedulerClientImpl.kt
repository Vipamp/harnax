package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.service.SchedulerClient
import com.agnetix.harnax.auth.AuthRestTemplateInterceptor
import com.agnetix.harnax.auth.InternalTokenProvider
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.entity.dto.AgentTaskOwner
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

    /**
     * One address in normal operation. The comma-separated form stays parsed — an operator who configured
     * two URLs must not break on this deploy — but only the first of them is ever used.
     */
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

    override fun triggerTask(id: Long): ResultVo<Void> = postToScheduler("/api/scheduler/tasks/$id/trigger")

    override fun startTask(id: Long): ResultVo<Void> = postToScheduler("/api/scheduler/tasks/$id/start")

    override fun pauseTask(id: Long): ResultVo<Void> = postToScheduler("/api/scheduler/tasks/$id/pause")

    override fun reloadTasks(): ResultVo<Void> = postToScheduler("/api/scheduler/reload")

    override fun stopTask(logId: Long): ResultVo<Void> = postToScheduler("/api/scheduler/tasks/logs/$logId/stop")

    /**
     * Contract C5, and the only read here. It reuses this client's one [RestClient] — same bearer from the
     * shared [InternalTokenProvider], same first instance of `harnax.scheduler.url`, same 5s connect and
     * 30s read ceilings. A shorter ceiling for this one call would mean its own request factory, its own
     * auth interceptor and its own copy of the URL list, and the path does not buy anything with it: every
     * non-answer lands in the same place, as one OAuth MCP tool missing for that execution.
     *
     * Nothing here throws. An unreachable scheduler is a [ResultVo] the resolver turns into a WARN naming
     * the task, which is the whole difference between "an OAuth tool quietly disappeared" and a task run
     * failed by the service that resolves its tools.
     */
    override fun taskOwner(id: Long): ResultVo<AgentTaskOwner?> = getFromScheduler("/api/scheduler/agent-tasks/$id/owner")

    private fun getFromScheduler(path: String): ResultVo<AgentTaskOwner?> {
        val baseUrl = urls.firstOrNull() ?: return ResultVo.error("No scheduler URL configured")
        val url = "$baseUrl$path"
        return try {
            log.debug("Reading task owner from scheduler: {}", url)
            restClient.get()
                .uri(url)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(object : ParameterizedTypeReference<ResultVo<AgentTaskOwner?>>() {})
                ?: ResultVo.error("No response from scheduler")
        } catch (e: Exception) {
            // Deliberately not rethrown; see the method above. The response body of a non-2xx is not read
            // into the message, so nothing of a task's own data lands in a log line here.
            log.warn("Failed to read task owner from scheduler {}: {}", url, e.message)
            ResultVo.error("Scheduler service unavailable: ${e.message}")
        }
    }

    /**
     * One call, one instance. Start/pause/reload land in the shared Quartz store and a manual trigger is
     * a one-shot the shared execution guard dedups, so in neither case is there per-node state left to
     * notify — and no `anySuccess`-style folding to invent business codes for.
     * A single instance failing therefore means exactly one thing: the definition is saved and nothing
     * has scheduled it, which is what 40902 tells the caller.
     *
     * Folding also carried a live bug for [stopTask]: the second instance to take the same stop answered
     * "nothing live", closed the log out itself and took the real result away from the owning thread.
     */
    private fun postToScheduler(path: String): ResultVo<Void> {
        val url = urls.firstOrNull() ?: return ResultVo.error("No scheduler URL configured")
        return postToInstance(url, path)
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

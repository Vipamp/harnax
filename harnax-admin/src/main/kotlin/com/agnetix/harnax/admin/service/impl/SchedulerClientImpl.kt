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
     * One call, one instance. Every scheduling write lands in the shared Quartz store, so there is no
     * per-node state left to notify — and no `anySuccess`-style folding to invent business codes for.
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

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
            // The scheduler runs UnifiedAuthFilter: every call needs a fresh typ=internal bearer.
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

    /** Broadcast to all scheduler instances; succeed if at least one succeeds */
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

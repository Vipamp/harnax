package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.service.SchedulerClient
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
    @Value("\${harnax.scheduler.url:http://localhost:8084}") private val schedulerUrl: String,
) : SchedulerClient {

    private val log = LoggerFactory.getLogger(SchedulerClientImpl::class.java)

    private val restClient: RestClient by lazy {
        val factory = org.springframework.http.client.SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(5))
            setReadTimeout(Duration.ofSeconds(30))
        }
        RestClient.builder()
            .requestFactory(factory)
            .build()
    }

    override fun triggerTask(id: Long): ResultVo<Void> = proxyToScheduler("/api/scheduler/tasks/$id/trigger")

    override fun startTask(id: Long): ResultVo<Void> = proxyToScheduler("/api/scheduler/tasks/$id/start")

    override fun pauseTask(id: Long): ResultVo<Void> = proxyToScheduler("/api/scheduler/tasks/$id/pause")

    private fun proxyToScheduler(path: String): ResultVo<Void> = try {
        val url = "$schedulerUrl$path"
        log.info("Proxying request to scheduler: {}", url)

        val result = restClient.post()
            .uri(url)
            .contentType(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(object : ParameterizedTypeReference<ResultVo<Void>>() {})

        result ?: ResultVo.error("No response from scheduler")
    } catch (e: Exception) {
        log.error("Failed to proxy request to scheduler: {}", e.message, e)
        ResultVo.error("Scheduler service unavailable: ${e.message}")
    }
}

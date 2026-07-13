package com.agnetix.harnax.client.spring

import com.agnetix.harnax.client.HarnaxClient
import com.agnetix.harnax.client.HarnaxClientConfig
import com.agnetix.harnax.client.dto.*
import com.agnetix.harnax.client.exception.HarnaxClientException
import com.agnetix.harnax.client.sse.SseEventParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.core.ParameterizedTypeReference
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.web.client.RestClient
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux

/**
 * Spring Boot based Harnax client implementation.
 *
 * Uses [RestClient] for synchronous (non-streaming) requests and
 * [WebClient] for SSE streaming requests.
 */
class SpringHarnaxClient(
    private val config: HarnaxClientConfig,
    private val restClient: RestClient,
    private val webClient: WebClient,
) : HarnaxClient {

    private val log = LoggerFactory.getLogger(SpringHarnaxClient::class.java)
    private val sseParser = SseEventParser()

    companion object {
        private const val API_PREFIX = "/api/router/agent"
    }

    // ==================== Chat ====================

    override suspend fun chat(request: ChatRequest): ResultVo<ChatResponse> {
        val url = "${config.normalizedBaseUrl}$API_PREFIX/chat"
        log.info("[SpringClient] chat request for session: ${request.sessionId}")
        return withContext(Dispatchers.IO) {
            restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(object : ParameterizedTypeReference<ResultVo<ChatResponse>>() {})
                ?: ResultVo.error("No response from server")
        }
    }

    override fun chatStream(request: ChatRequest): Flow<ChatEvent> {
        val url = "${config.normalizedBaseUrl}$API_PREFIX/chat/stream"
        log.info("[SpringClient] chatStream request for session: ${request.sessionId}")
        return ssePost(url, request)
    }

    // ==================== Command ====================

    override suspend fun command(request: CommandRequest): ResultVo<CommandResponse> {
        val url = "${config.normalizedBaseUrl}$API_PREFIX/command"
        log.debug("[SpringClient] command request for session: ${request.sessionId}")
        return withContext(Dispatchers.IO) {
            restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(object : ParameterizedTypeReference<ResultVo<CommandResponse>>() {})
                ?: ResultVo.error("No response from server")
        }
    }

    // ==================== Confirm ====================

    override fun confirm(request: ConfirmRequest): Flow<ChatEvent> {
        val url = "${config.normalizedBaseUrl}$API_PREFIX/confirm"
        log.debug("[SpringClient] confirm request for session: ${request.sessionId}")
        return ssePost(url, request)
    }

    // ==================== Session ====================

    override suspend fun clearSession(sessionId: String): ResultVo<String> {
        val url = "${config.normalizedBaseUrl}$API_PREFIX/session/$sessionId"
        log.debug("[SpringClient] clearSession: $sessionId")
        return withContext(Dispatchers.IO) {
            restClient.delete()
                .uri(url)
                .retrieve()
                .body(object : ParameterizedTypeReference<ResultVo<String>>() {})
                ?: ResultVo.error("No response from server")
        }
    }

    override suspend fun getHistory(sessionId: String): ResultVo<List<ChatEvent>> {
        val url = "${config.normalizedBaseUrl}$API_PREFIX/chat/history/$sessionId"
        log.debug("[SpringClient] getHistory: $sessionId")
        return withContext(Dispatchers.IO) {
            restClient.get()
                .uri(url)
                .retrieve()
                .body(object : ParameterizedTypeReference<ResultVo<List<ChatEvent>>>() {})
                ?: ResultVo.error("No response from server")
        }
    }

    override suspend fun getPlans(sessionId: String): ResultVo<List<Any>> {
        val url = "${config.normalizedBaseUrl}$API_PREFIX/session/$sessionId/plans"
        log.debug("[SpringClient] getPlans: $sessionId")
        return withContext(Dispatchers.IO) {
            restClient.get()
                .uri(url)
                .retrieve()
                .body(object : ParameterizedTypeReference<ResultVo<List<Any>>>() {})
                ?: ResultVo.error("No response from server")
        }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun getCurrentPlan(sessionId: String): ResultVo<Any?> {
        val url = "${config.normalizedBaseUrl}$API_PREFIX/session/$sessionId/current-plan"
        log.debug("[SpringClient] getCurrentPlan: $sessionId")
        return withContext(Dispatchers.IO) {
            val raw = restClient.get()
                .uri(url)
                .retrieve()
                .body(object : ParameterizedTypeReference<ResultVo<Any>>() {})
            (raw as? ResultVo<Any?>) ?: ResultVo.error("No response from server")
        }
    }

    // ==================== Workspace ====================

    override suspend fun listFiles(sessionId: String, path: String): ResultVo<List<FileInfo>> {
        val url = "${config.normalizedBaseUrl}$API_PREFIX/workspace/$sessionId/files"
        log.debug("[SpringClient] listFiles: session=$sessionId, path=$path")
        return withContext(Dispatchers.IO) {
            restClient.get()
                .uri { it.path(url).queryParam("path", path).build() }
                .retrieve()
                .body(object : ParameterizedTypeReference<ResultVo<List<FileInfo>>>() {})
                ?: ResultVo.error("No response from server")
        }
    }

    override suspend fun readFile(sessionId: String, path: String): ResultVo<FileContent> {
        val url = "${config.normalizedBaseUrl}$API_PREFIX/workspace/$sessionId/read"
        log.debug("[SpringClient] readFile: session=$sessionId, path=$path")
        return withContext(Dispatchers.IO) {
            restClient.get()
                .uri { it.path(url).queryParam("path", path).build() }
                .retrieve()
                .body(object : ParameterizedTypeReference<ResultVo<FileContent>>() {})
                ?: ResultVo.error("No response from server")
        }
    }

    override suspend fun getWorkspaceStatus(sessionIds: List<String>): ResultVo<Map<String, WorkspaceStatus>> {
        val ids = sessionIds.joinToString(",")
        val url = "${config.normalizedBaseUrl}$API_PREFIX/workspace/status"
        log.debug("[SpringClient] getWorkspaceStatus (multi): $ids")
        return withContext(Dispatchers.IO) {
            restClient.get()
                .uri { it.path(url).queryParam("sessionIds", ids).build() }
                .retrieve()
                .body(object : ParameterizedTypeReference<ResultVo<Map<String, WorkspaceStatus>>>() {})
                ?: ResultVo.error("No response from server")
        }
    }

    override suspend fun getWorkspaceStatus(sessionId: String): ResultVo<WorkspaceStatus> {
        val url = "${config.normalizedBaseUrl}$API_PREFIX/workspace/$sessionId/status"
        log.debug("[SpringClient] getWorkspaceStatus (single): $sessionId")
        return withContext(Dispatchers.IO) {
            restClient.get()
                .uri(url)
                .retrieve()
                .body(object : ParameterizedTypeReference<ResultVo<WorkspaceStatus>>() {})
                ?: ResultVo.error("No response from server")
        }
    }

    override suspend fun uploadFile(
        sessionId: String,
        path: String,
        fileName: String,
        bytes: ByteArray,
    ): ResultVo<UploadResult> {
        val url = "${config.normalizedBaseUrl}$API_PREFIX/workspace/$sessionId/upload"
        log.info("[SpringClient] uploadFile: session=$sessionId, fileName=$fileName")

        return withContext(Dispatchers.IO) {
            val fileResource = object : ByteArrayResource(bytes) {
                override fun getFilename(): String = fileName
            }
            val builder = MultipartBodyBuilder()
            builder.part("file", fileResource)
            builder.part("path", path)

            restClient.post()
                .uri(url)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(builder.build())
                .retrieve()
                .body(object : ParameterizedTypeReference<ResultVo<UploadResult>>() {})
                ?: ResultVo.error("No response from server")
        }
    }

    override suspend fun downloadFile(sessionId: String, path: String): DownloadResult {
        val url = "${config.normalizedBaseUrl}$API_PREFIX/workspace/$sessionId/download"
        log.info("[SpringClient] downloadFile: session=$sessionId, path=$path")

        return withContext(Dispatchers.IO) {
            val response = restClient.get()
                .uri { it.path(url).queryParam("path", path).build() }
                .retrieve()
                .toEntity(ByteArray::class.java)

            val bodyBytes = response.body ?: throw HarnaxClientException("Empty download response")
            val contentType = response.headers.contentType?.toString() ?: "application/octet-stream"
            val contentDisposition = response.headers.getFirst("Content-Disposition") ?: ""
            val fileName = extractFileName(contentDisposition, path)
            DownloadResult(bytes = bodyBytes, contentType = contentType, fileName = fileName)
        }
    }

    // ==================== Internal helpers ====================

    /**
     * Send a POST request and stream the SSE response as Flow<ChatEvent>.
     * Uses WebClient (reactive) to handle text/event-stream, then converts Flux to Flow.
     */
    private fun ssePost(url: String, body: Any): Flow<ChatEvent> {
        val flux: Flux<ChatEvent> = webClient.post()
            .uri(url)
            .contentType(MediaType.APPLICATION_JSON)
            .header("Accept", "text/event-stream")
            .bodyValue(body)
            .retrieve()
            .bodyToFlux(String::class.java)
            .filter { it.isNotBlank() }
            .mapNotNull { data -> sseParser.parseJson(data) }

        return flux.asFlow()
    }

    private fun extractFileName(contentDisposition: String, fallbackPath: String): String {
        val match = Regex("filename=\"?([^\"]+)\"?").find(contentDisposition)
        return match?.groupValues?.get(1) ?: java.io.File(fallbackPath).name
    }
}

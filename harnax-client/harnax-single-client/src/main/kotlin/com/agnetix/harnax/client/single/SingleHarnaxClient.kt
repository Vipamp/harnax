package com.agnetix.harnax.client.single

import com.agnetix.harnax.client.HarnaxClient
import com.agnetix.harnax.client.HarnaxClientConfig
import com.agnetix.harnax.client.dto.*
import com.agnetix.harnax.client.exception.*
import com.agnetix.harnax.client.single.sse.JdkSseStreamReader
import com.agnetix.harnax.client.sse.SseEventParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import tools.jackson.core.type.TypeReference
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Pure HTTP client for Harnax Router API.
 *
 * Based on JDK 21 [java.net.http.HttpClient], no Spring dependency required.
 * Suitable for any JVM project.
 *
 * Usage:
 * ```
 * val client = SingleHarnaxClient.builder()
 *     .baseUrl("http://router-host:8081")
 *     .apiKey("your-api-key")
 *     .build()
 * ```
 */
class SingleHarnaxClient private constructor(
    private val config: HarnaxClientConfig,
    private val httpClient: HttpClient,
) : HarnaxClient,
    AutoCloseable {

    private val log = LoggerFactory.getLogger(SingleHarnaxClient::class.java)
    private val sseParser = SseEventParser()
    private val objectMapper = sseParser.getObjectMapper()
    private val sseReader = JdkSseStreamReader(httpClient, sseParser, config)

    companion object {
        private const val API_PREFIX = "/api/router/agent"
        private const val CONTENT_TYPE_JSON = "application/json"

        fun builder(): SingleClientBuilder = SingleClientBuilder()
    }

    internal constructor(config: HarnaxClientConfig) : this(
        config = config,
        httpClient = HttpClient.newBuilder()
            .connectTimeout(config.connectTimeout)
            .build(),
    )

    // ==================== Chat ====================

    override suspend fun chat(request: ChatRequest): ResultVo<ChatResponse> {
        val url = "${config.normalizedBaseUrl}$API_PREFIX/chat"
        log.info("[SingleClient] chat request for session: ${request.sessionId}")
        return postJson(url, request, object : TypeReference<ResultVo<ChatResponse>>() {})
    }

    override fun chatStream(request: ChatRequest): Flow<ChatEvent> {
        val url = "${config.normalizedBaseUrl}$API_PREFIX/chat/stream"
        log.info("[SingleClient] chatStream request for session: ${request.sessionId}")
        return sseReader.streamPost(url, request)
    }

    // ==================== Command ====================

    override suspend fun command(request: CommandRequest): ResultVo<CommandResponse> {
        val url = "${config.normalizedBaseUrl}$API_PREFIX/command"
        log.debug("[SingleClient] command request for session: ${request.sessionId}")
        return postJson(url, request, object : TypeReference<ResultVo<CommandResponse>>() {})
    }

    // ==================== Confirm ====================

    override fun confirm(request: ConfirmRequest): Flow<ChatEvent> {
        val url = "${config.normalizedBaseUrl}$API_PREFIX/confirm"
        log.debug("[SingleClient] confirm request for session: ${request.sessionId}")
        return sseReader.streamPost(url, request)
    }

    // ==================== Session ====================

    override suspend fun clearSession(sessionId: String): ResultVo<String> {
        val url = "${config.normalizedBaseUrl}$API_PREFIX/session/$sessionId"
        log.debug("[SingleClient] clearSession: $sessionId")
        return deleteJson(url, object : TypeReference<ResultVo<String>>() {})
    }

    override suspend fun getHistory(sessionId: String): ResultVo<List<ChatEvent>> {
        val url = "${config.normalizedBaseUrl}$API_PREFIX/chat/history/$sessionId"
        log.debug("[SingleClient] getHistory: $sessionId")
        return getJson(url, object : TypeReference<ResultVo<List<ChatEvent>>>() {})
    }

    override suspend fun getPlans(sessionId: String): ResultVo<List<Any>> {
        val url = "${config.normalizedBaseUrl}$API_PREFIX/session/$sessionId/plans"
        log.debug("[SingleClient] getPlans: $sessionId")
        return getJson(url, object : TypeReference<ResultVo<List<Any>>>() {})
    }

    override suspend fun getCurrentPlan(sessionId: String): ResultVo<Any?> {
        val url = "${config.normalizedBaseUrl}$API_PREFIX/session/$sessionId/current-plan"
        log.debug("[SingleClient] getCurrentPlan: $sessionId")
        return getJson(url, object : TypeReference<ResultVo<Any?>>() {})
    }

    // ==================== Workspace ====================

    override suspend fun listFiles(sessionId: String, path: String): ResultVo<List<FileInfo>> {
        val url = "${config.normalizedBaseUrl}$API_PREFIX/workspace/$sessionId/files?path=${encodeParam(path)}"
        log.debug("[SingleClient] listFiles: session=$sessionId, path=$path")
        return getJson(url, object : TypeReference<ResultVo<List<FileInfo>>>() {})
    }

    override suspend fun readFile(sessionId: String, path: String): ResultVo<FileContent> {
        val url = "${config.normalizedBaseUrl}$API_PREFIX/workspace/$sessionId/read?path=${encodeParam(path)}"
        log.debug("[SingleClient] readFile: session=$sessionId, path=$path")
        return getJson(url, object : TypeReference<ResultVo<FileContent>>() {})
    }

    override suspend fun getWorkspaceStatus(sessionIds: List<String>): ResultVo<Map<String, WorkspaceStatus>> {
        val ids = sessionIds.joinToString(",")
        val url = "${config.normalizedBaseUrl}$API_PREFIX/workspace/status?sessionIds=${encodeParam(ids)}"
        log.debug("[SingleClient] getWorkspaceStatus (multi): $ids")
        return getJson(url, object : TypeReference<ResultVo<Map<String, WorkspaceStatus>>>() {})
    }

    override suspend fun getWorkspaceStatus(sessionId: String): ResultVo<WorkspaceStatus> {
        val url = "${config.normalizedBaseUrl}$API_PREFIX/workspace/$sessionId/status"
        log.debug("[SingleClient] getWorkspaceStatus (single): $sessionId")
        return getJson(url, object : TypeReference<ResultVo<WorkspaceStatus>>() {})
    }

    override suspend fun uploadFile(
        sessionId: String,
        path: String,
        fileName: String,
        bytes: ByteArray,
    ): ResultVo<UploadResult> {
        val url = "${config.normalizedBaseUrl}$API_PREFIX/workspace/$sessionId/upload"
        log.info("[SingleClient] uploadFile: session=$sessionId, fileName=$fileName")

        val boundary = "----HarnaxClient${System.currentTimeMillis()}"
        val body = buildMultipartBody(boundary, path, fileName, bytes)

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(config.readTimeout)
            .header("X-Api-Key", config.apiKey)
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()

        return withContext(Dispatchers.IO) {
            executeAndParse(request, object : TypeReference<ResultVo<UploadResult>>() {})
        }
    }

    override suspend fun downloadFile(sessionId: String, path: String): DownloadResult {
        val url = "${config.normalizedBaseUrl}$API_PREFIX/workspace/$sessionId/download?path=${encodeParam(path)}"
        log.info("[SingleClient] downloadFile: session=$sessionId, path=$path")

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(config.readTimeout)
            .header("X-Api-Key", config.apiKey)
            .GET()
            .build()

        return withContext(Dispatchers.IO) {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() != 200) {
                throw HarnaxServerException.from(response.statusCode(), String(response.body()))
            }
            val contentType = response.headers().firstValue("Content-Type").orElse("application/octet-stream")
            val contentDisposition = response.headers().firstValue("Content-Disposition").orElse("")
            val fileName = extractFileName(contentDisposition, path)
            DownloadResult(bytes = response.body(), contentType = contentType, fileName = fileName)
        }
    }

    // ==================== Internal HTTP helpers ====================

    private suspend fun <T> getJson(url: String, typeRef: TypeReference<T>): T = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(config.readTimeout)
            .header("X-Api-Key", config.apiKey)
            .header("Accept", CONTENT_TYPE_JSON)
            .GET()
            .build()
        executeAndParse(request, typeRef)
    }

    private suspend fun <T> postJson(url: String, body: Any, typeRef: TypeReference<T>): T = withContext(Dispatchers.IO) {
        val json = objectMapper.writeValueAsString(body)
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(config.readTimeout)
            .header("X-Api-Key", config.apiKey)
            .header("Content-Type", CONTENT_TYPE_JSON)
            .header("Accept", CONTENT_TYPE_JSON)
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()
        executeAndParse(request, typeRef)
    }

    private suspend fun <T> deleteJson(url: String, typeRef: TypeReference<T>): T = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(config.readTimeout)
            .header("X-Api-Key", config.apiKey)
            .header("Accept", CONTENT_TYPE_JSON)
            .DELETE()
            .build()
        executeAndParse(request, typeRef)
    }

    private suspend fun <T> executeAndParse(request: HttpRequest, typeRef: TypeReference<T>): T {
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: java.net.http.HttpConnectTimeoutException) {
            throw HarnaxConnectionException("Connection timeout: ${request.uri()}", e)
        } catch (e: java.net.http.HttpTimeoutException) {
            throw HarnaxConnectionException("Request timeout: ${request.uri()}", e)
        } catch (e: java.net.ConnectException) {
            throw HarnaxConnectionException("Connection refused: ${request.uri()}", e)
        } catch (e: Exception) {
            throw HarnaxClientException("HTTP request failed: ${e.message}", e)
        }

        if (response.statusCode() !in 200..299) {
            throw HarnaxServerException.from(response.statusCode(), response.body())
        }

        return try {
            objectMapper.readValue(response.body(), typeRef)
        } catch (e: Exception) {
            throw HarnaxClientException("Failed to parse response: ${e.message}", e)
        }
    }

    private fun buildMultipartBody(boundary: String, path: String, fileName: String, fileBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val crlf = "\r\n"

        // path part
        out.write("--$boundary$crlf".toByteArray())
        out.write("Content-Disposition: form-data; name=\"path\"$crlf$crlf".toByteArray())
        out.write(path.toByteArray())
        out.write(crlf.toByteArray())

        // file part
        out.write("--$boundary$crlf".toByteArray())
        out.write("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"$crlf".toByteArray())
        out.write("Content-Type: application/octet-stream$crlf$crlf".toByteArray())
        out.write(fileBytes)
        out.write(crlf.toByteArray())

        // closing boundary
        out.write("--$boundary--$crlf".toByteArray())
        return out.toByteArray()
    }

    private fun extractFileName(contentDisposition: String, fallbackPath: String): String {
        val match = Regex("filename=\"?([^\"]+)\"?").find(contentDisposition)
        return match?.groupValues?.get(1) ?: java.io.File(fallbackPath).name
    }

    private fun encodeParam(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8)

    /**
     * Release resources held by the underlying HttpClient.
     * The JDK HttpClient uses an internal executor that should be
     * shut down when the client is no longer needed.
     */
    override fun close() {
        httpClient.close()
    }
}

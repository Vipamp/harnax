package com.agnetix.harnax.client.single.sse

import com.agnetix.harnax.client.HarnaxClientConfig
import com.agnetix.harnax.client.dto.ChatEvent
import com.agnetix.harnax.client.exception.HarnaxClientException
import com.agnetix.harnax.client.exception.HarnaxConnectionException
import com.agnetix.harnax.client.sse.SseEventParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * SSE stream reader using JDK HttpClient.
 *
 * Opens an HTTP connection with Accept: text/event-stream,
 * reads the response line by line, and parses each "data:" line
 * into [ChatEvent] objects emitted as a Kotlin [Flow].
 */
class JdkSseStreamReader(
    private val httpClient: HttpClient,
    private val parser: SseEventParser,
    private val config: HarnaxClientConfig,
) {

    private val log = LoggerFactory.getLogger(JdkSseStreamReader::class.java)
    private val objectMapper = parser.getObjectMapper()

    /**
     * Send a POST request and stream the SSE response as a Flow of ChatEvent.
     *
     * @param url  Full URL to POST to
     * @param body Request body object (will be serialized to JSON)
     * @return Flow of ChatEvent parsed from SSE data lines
     */
    fun streamPost(url: String, body: Any): Flow<ChatEvent> = flow {
        val json = objectMapper.writeValueAsString(body)
        log.debug("[SseReader] POST $url")

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(config.streamTimeout)
            .header("X-Api-Key", config.apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()

        val response: HttpResponse<java.io.InputStream>
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: java.net.http.HttpConnectTimeoutException) {
            throw HarnaxConnectionException("SSE connection timeout: $url", e)
        } catch (e: java.net.ConnectException) {
            throw HarnaxConnectionException("SSE connection refused: $url", e)
        } catch (e: Exception) {
            throw HarnaxClientException("SSE request failed: ${e.message}", e)
        }

        if (response.statusCode() !in 200..299) {
            val errorBody = try {
                response.body().bufferedReader().readText()
            } catch (_: Exception) {
                ""
            }
            throw com.agnetix.harnax.client.exception.HarnaxServerException.from(
                response.statusCode(),
                errorBody,
            )
        }

        val reader = BufferedReader(InputStreamReader(response.body()))
        try {
            reader.lineSequence().forEach { line ->
                if (line.isBlank()) return@forEach
                val event = parser.parseLine(line)
                if (event != null) {
                    emit(event)
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            log.warn("[SseReader] SSE stream interrupted: ${e.message}")
        } finally {
            try {
                reader.close()
            } catch (_: Exception) { /* ignore */ }
        }
    }.flowOn(Dispatchers.IO)
}

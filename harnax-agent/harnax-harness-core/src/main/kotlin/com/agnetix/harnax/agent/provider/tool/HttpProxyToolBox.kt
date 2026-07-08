package com.agnetix.harnax.agent.provider.tool

import io.agentscope.core.message.TextBlock
import io.agentscope.core.message.ToolResultBlock
import io.agentscope.core.tool.AgentTool
import io.agentscope.core.tool.ToolCallParam
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class HttpProxyToolBox(
    private val toolName: String,
    private val toolDescription: String,
    private val httpUrl: String,
    private val httpMethod: String = "POST",
    private val httpHeaders: Map<String, String> = emptyMap(),
    private val inputSchemaJson: String = "{}",
    private val timeoutSeconds: Int = 30,
) : AgentTool {

    private val log = LoggerFactory.getLogger(HttpProxyToolBox::class.java)
    private val objectMapper = ObjectMapper()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(timeoutSeconds.toLong()))
        .build()

    override fun getName(): String = toolName

    override fun getDescription(): String = toolDescription

    override fun getParameters(): Map<String, Any> = try {
        @Suppress("UNCHECKED_CAST")
        objectMapper.readValue(inputSchemaJson, Map::class.java) as Map<String, Any>
    } catch (e: Exception) {
        log.warn("Failed to parse input schema for tool '{}', using empty schema", toolName, e)
        emptyMap()
    }

    override fun callAsync(param: ToolCallParam): Mono<ToolResultBlock> = Mono.fromCallable {
        try {
            val requestBody = objectMapper.writeValueAsString(param.input)
            val toolId = param.toolUseBlock.id

            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(httpUrl))
                .timeout(Duration.ofSeconds(timeoutSeconds.toLong()))
                .header("Content-Type", "application/json")

            httpHeaders.forEach { (key, value) ->
                requestBuilder.header(key, value)
            }

            when (httpMethod.uppercase()) {
                "POST" -> requestBuilder.POST(HttpRequest.BodyPublishers.ofString(requestBody))
                "PUT" -> requestBuilder.PUT(HttpRequest.BodyPublishers.ofString(requestBody))
                "GET" -> requestBuilder.GET()
                "DELETE" -> requestBuilder.DELETE()
                else -> requestBuilder.POST(HttpRequest.BodyPublishers.ofString(requestBody))
            }

            val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())

            val resultText = if (response.statusCode() in 200..299) {
                response.body()
            } else {
                "HTTP error ${response.statusCode()}: ${response.body()}"
            }

            ToolResultBlock.of(
                toolId,
                toolName,
                TextBlock.builder().text(resultText).build(),
            )
        } catch (e: Exception) {
            log.error("HTTP tool '{}' call failed: {}", toolName, e.message, e)
            val toolId = param.toolUseBlock?.id
            ToolResultBlock.of(
                toolId,
                toolName,
                TextBlock.builder().text("Error: HTTP tool call failed: ${e.message}").build(),
            )
        }
    }
}

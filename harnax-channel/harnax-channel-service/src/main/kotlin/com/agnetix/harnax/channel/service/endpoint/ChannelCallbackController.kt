package com.agnetix.harnax.channel.service.endpoint

import com.agnetix.harnax.channel.sdk.adaptor.AgentAdaptor
import com.agnetix.harnax.channel.sdk.adaptor.ChannelCallbackPipeline
import com.agnetix.harnax.channel.sdk.message.ChannelRequest
import com.agnetix.harnax.channel.sdk.service.ChannelChatService
import com.agnetix.harnax.channel.sdk.session.ChannelSessionManager
import com.agnetix.harnax.channel.service.manager.ChannelAdaptorRegistry
import com.agnetix.harnax.channel.service.mapper.ChannelEntityConverter
import com.agnetix.harnax.mapper.ChannelMapper
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets

/**
 * Receives platform HTTP callbacks for channels in `webhook` communication mode.
 *
 * This is the inbound half of webhook mode. The sending half has always existed —
 * `FeishuAdaptor.webhookMode` posts replies — but nothing ever received the platform's events, so
 * a channel configured for webhook mode accepted the configuration, started no listener, and then
 * went quiet with nothing in the logs to say why.
 *
 * Deliberately thin: verification, decoding and the reply body belong to the adaptor, because the
 * per-platform callback contracts differ more than a controller can absorb (see
 * [com.agnetix.harnax.channel.sdk.adaptor.ChannelAdaptor.handleCallback]).
 *
 * Security notes:
 * - The path is exempt from `UnifiedAuthFilter` (configured through `harnax.auth.skip-paths`)
 *   because a platform cannot present a service token. Authentication is therefore the platform's
 *   own signature, and an adaptor that cannot verify one must refuse the event — which is why the
 *   Feishu webhook requires an Encrypt Key even though the WebSocket mode does not.
 * - It is a public, unauthenticated endpoint, so the request body is capped: an unbounded read
 *   would let anyone make this service allocate memory at will.
 * - Unknown callback keys answer 404 and unverifiable payloads answer 4xx, never 5xx, so a probe
 *   cannot tell the difference between "no channel here" and "a channel exists but rejected me".
 */
@RestController
@RequestMapping("/api/channel/callback")
class ChannelCallbackController(
    private val channelMapper: ChannelMapper,
    private val adaptorRegistry: ChannelAdaptorRegistry,
    private val agentAdaptor: AgentAdaptor,
    private val sessionManager: ChannelSessionManager,
    private val chatService: ChannelChatService,
) {

    private val logger = LoggerFactory.getLogger(ChannelCallbackController::class.java)

    @PostMapping("/{callbackKey}")
    fun callback(
        @PathVariable callbackKey: String,
        request: HttpServletRequest,
    ): ResponseEntity<String> {
        val channel = try {
            channelMapper.selectByCallbackKey(callbackKey)
        } catch (e: Exception) {
            logger.error("Callback key lookup failed for key=$callbackKey: ${e.message}", e)
            return rejected(500, "channel lookup failed")
        } ?: return rejected(404, "unknown callback key")

        if (channel.enabled != 1 || channel.status != 1) {
            logger.info("Callback for disabled channel id={}, key={}", channel.id, callbackKey)
            return rejected(403, "channel is disabled")
        }

        val spec = try {
            ChannelEntityConverter.toSpec(channel)
        } catch (e: Exception) {
            logger.warn("Channel id={} has invalid configuration: {}", channel.id, e.message)
            return rejected(400, "channel configuration is invalid")
        }

        if (!spec.communicationMode.equals(CallbackModes.WEBHOOK, ignoreCase = true)) {
            // The listener owns this channel's inbound traffic; a callback would double-handle it.
            logger.warn(
                "Callback for channel id={} in ${spec.communicationMode} mode ignored; only webhook channels receive HTTP callbacks",
                channel.id,
            )
            return rejected(404, "channel does not use callback mode")
        }

        val adaptor = try {
            adaptorRegistry.get(spec.type)
        } catch (e: IllegalArgumentException) {
            logger.warn("No adaptor registered for channel type {}", spec.type.code)
            return rejected(404, "unsupported channel type")
        }

        if (!adaptor.supportsCallback()) {
            logger.warn(
                "Channel id={} is configured for webhook mode but ${spec.type.code} has no callback contract; " +
                    "switch it to the long-connection mode",
                channel.id,
            )
            return rejected(501, "this channel type has no HTTP callback; use long-connection mode")
        }

        val body = readBody(request)
        if (body == null) {
            return rejected(413, "callback body too large")
        }

        val result = try {
            adaptor.handleCallback(requestOf(request, body), spec, ChannelCallbackPipeline(agentAdaptor, sessionManager, chatService))
        } catch (e: Throwable) {
            logger.error("Callback handling failed for channel id=${channel.id}: ${e.message}", e)
            return rejected(400, "callback could not be processed")
        }

        // A 5xx here would be this service's own fault reported as the platform's: Feishu's SDK
        // answers an undecodable or mis-signed event with 500, and the platform treats 5xx as
        // "not delivered" and retries it forever. Nothing a 4xx cannot say.
        val status = if (result.status >= 500) 400 else result.status
        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(result.body.ifBlank { "{\"msg\":\"ok\"}" })
    }

    private fun requestOf(
        request: HttpServletRequest,
        body: String,
    ): ChannelRequest {
        val headers = request.headerNames.toList()
            .mapNotNull { name -> request.getHeader(name)?.let { name to it } }
            .toMap()
        return ChannelRequest.builder()
            .headers(headers)
            .body(body)
            .method(request.method)
            .path(request.requestURI)
            .remoteAddr(request.remoteAddr)
            .contentType(request.contentType)
            .build()
    }

    /**
     * Reads at most [MAX_CALLBACK_BODY_BYTES], or returns null when the body is over the limit.
     *
     * Oversized is refused rather than truncated: half a signed event would fail verification with
     * a misleading error, and this endpoint is unauthenticated, so an unbounded read is a memory
     * attack surface.
     */
    private fun readBody(request: HttpServletRequest): String? {
        val declared = request.contentLength
        if (declared > MAX_CALLBACK_BODY_BYTES) {
            logger.warn("Callback body of $declared bytes exceeds the $MAX_CALLBACK_BODY_BYTES limit")
            return null
        }
        val bytes = request.inputStream.readNBytes(MAX_CALLBACK_BODY_BYTES + 1)
        if (bytes.size > MAX_CALLBACK_BODY_BYTES) {
            logger.warn("Callback body exceeds the $MAX_CALLBACK_BODY_BYTES limit")
            return null
        }
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun rejected(status: Int, reason: String): ResponseEntity<String> = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body("{\"msg\":\"$reason\"}")

    companion object {
        /** Callback bodies are signed JSON events; a megabyte is far above the largest real one. */
        private const val MAX_CALLBACK_BODY_BYTES = 1024 * 1024
    }
}

/**
 * The one communication mode that means "the platform calls us", shared by this controller and
 * [com.agnetix.harnax.channel.service.bootstrap.ChannelBootstrapRunner] so the two cannot disagree
 * about which channels need a listener.
 */
object CallbackModes {
    const val WEBHOOK = "webhook"
}

package com.agnetix.harnax.router.controller

import com.agnetix.harnax.agent.protocol.ChatAgentRequest
import com.agnetix.harnax.agent.protocol.ChatEvent
import com.agnetix.harnax.agent.protocol.ChatResponse
import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.agent.protocol.CommandResponse
import com.agnetix.harnax.agent.protocol.ConfirmAgentRequest
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.router.proxy.SessionRouterService
import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import reactor.core.publisher.Flux

/**
 * Agent Proxy Controller.
 * Proxies chat, command, and session management requests to agent-service instances.
 * Both internal services and external API key callers can access these endpoints.
 */
@RestController
@RequestMapping("/api/router/agent")
class AgentProxyController(
    private val sessionRouterService: SessionRouterService,
) {

    private val log = LoggerFactory.getLogger(AgentProxyController::class.java)

    companion object {
        // Request attribute key for passing sessionId to the ApiCallLogFilter.
        // Controller sets this after @RequestBody parsing; filter reads it in the finally block.
        const val SESSION_ID_ATTR = "router.sessionId"
    }

    /**
     * Proxy a direct (non-streaming) chat request to the correct agent-service instance.
     */
    @PostMapping("/chat")
    suspend fun proxyChat(
        @RequestBody request: ChatAgentRequest,
        httpRequest: HttpServletRequest,
    ): ResultVo<ChatResponse> {
        httpRequest.setAttribute(SESSION_ID_ATTR, request.sessionId)
        log.info("[Router] Received chat proxy request for session: ${request.sessionId}, message='${request.message.take(50)}'")
        return sessionRouterService.proxyChatRequest(request)
    }

    /**
     * Proxy an SSE streaming chat request.
     * Accepts AgentRequest in body, returns Flux<ChatEvent> as text/event-stream.
     */
    @PostMapping("/chat/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun proxyChatStream(
        @RequestBody request: ChatAgentRequest,
        httpRequest: HttpServletRequest,
    ): Flux<ChatEvent> {
        httpRequest.setAttribute(SESSION_ID_ATTR, request.sessionId)
        log.info("[Router] Received stream proxy request for session: ${request.sessionId}")
        return sessionRouterService.proxyStreamRequest(request)
            .doOnNext { event ->
                log.info("[Router→Channel] Forwarding event to channel for session=${request.sessionId}: ${event.javaClass.simpleName}")
            }
    }

    /**
     * Proxy a command request to the correct agent-service instance.
     */
    @PostMapping("/command")
    suspend fun proxyCommand(
        @RequestBody request: CommandAgentRequest,
        httpRequest: HttpServletRequest,
    ): ResultVo<CommandResponse> {
        httpRequest.setAttribute(SESSION_ID_ATTR, request.sessionId)
        log.debug("Received command proxy request for session: ${request.sessionId}")
        return sessionRouterService.proxyCommandRequest(request)
    }

    /**
     * Proxy a confirm request (SSE streaming) to the correct agent-service instance.
     */
    @PostMapping("/confirm", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun proxyConfirm(
        @RequestBody request: ConfirmAgentRequest,
        httpRequest: HttpServletRequest,
    ): Flux<ChatEvent> {
        httpRequest.setAttribute(SESSION_ID_ATTR, request.sessionId)
        log.debug("Received confirm proxy request for session: ${request.sessionId}")
        return sessionRouterService.proxyConfirmStreamRequest(request)
    }

    /**
     * Proxy a clear session request to the correct agent-service instance.
     */
    @DeleteMapping("/session/{sessionId}")
    suspend fun proxyClearSession(
        @PathVariable sessionId: String,
        httpRequest: HttpServletRequest,
    ): ResultVo<String> {
        httpRequest.setAttribute(SESSION_ID_ATTR, sessionId)
        log.debug("Received clear session proxy request for session: $sessionId")
        return sessionRouterService.proxyClearSession(sessionId)
    }

    /**
     * Proxy a load history request to the correct agent-service instance.
     */
    @GetMapping("/chat/history/{sessionId}")
    suspend fun proxyLoadHistory(
        @PathVariable sessionId: String,
        httpRequest: HttpServletRequest,
    ): ResultVo<List<Any>> {
        httpRequest.setAttribute(SESSION_ID_ATTR, sessionId)
        log.debug("Received load history proxy request for session: $sessionId")
        return sessionRouterService.proxyLoadHistory(sessionId)
    }

    /**
     * Proxy a load plans request to the correct agent-service instance.
     */
    @GetMapping("/session/{sessionId}/plans")
    suspend fun proxyLoadPlans(
        @PathVariable sessionId: String,
        httpRequest: HttpServletRequest,
    ): ResultVo<List<Any>> {
        httpRequest.setAttribute(SESSION_ID_ATTR, sessionId)
        log.debug("Received load plans proxy request for session: $sessionId")
        return sessionRouterService.proxyLoadPlans(sessionId)
    }

    /**
     * Proxy a load current plan request to the correct agent-service instance.
     */
    @GetMapping("/session/{sessionId}/current-plan")
    suspend fun proxyLoadCurrentPlan(
        @PathVariable sessionId: String,
        httpRequest: HttpServletRequest,
    ): ResultVo<Any?> {
        httpRequest.setAttribute(SESSION_ID_ATTR, sessionId)
        log.debug("Received load current plan proxy request for session: $sessionId")
        return sessionRouterService.proxyLoadCurrentPlan(sessionId)
    }

    // ---- Workspace proxy endpoints ----

    /**
     * Proxy a workspace file listing request.
     */
    @GetMapping("/workspace/{sessionId}/files")
    fun proxyWorkspaceListFiles(
        @PathVariable sessionId: String,
        @RequestParam(defaultValue = "/workspace") path: String,
        httpRequest: HttpServletRequest,
    ): ResultVo<List<Map<String, Any>>> = runBlocking {
        httpRequest.setAttribute(SESSION_ID_ATTR, sessionId)
        log.debug("Received workspace list proxy request for session: $sessionId, path: $path")
        sessionRouterService.proxyWorkspaceListFiles(sessionId, path)
    }

    /**
     * Proxy a workspace file read request.
     */
    @GetMapping("/workspace/{sessionId}/read")
    fun proxyWorkspaceReadFile(
        @PathVariable sessionId: String,
        @RequestParam path: String,
        httpRequest: HttpServletRequest,
    ): ResultVo<Map<String, Any>> = runBlocking {
        httpRequest.setAttribute(SESSION_ID_ATTR, sessionId)
        log.debug("Received workspace read proxy request for session: $sessionId, path: $path")
        sessionRouterService.proxyWorkspaceReadFile(sessionId, path)
    }

    /**
     * Proxy a workspace status request for one or multiple sessions.
     */
    @GetMapping("/workspace/status")
    fun proxyWorkspaceStatus(
        @RequestParam(required = false) sessionIds: String?,
        httpRequest: HttpServletRequest,
    ): ResultVo<Map<String, Map<String, Any>>> = runBlocking {
        log.info("[AgentProxyController] Received workspace status request for sessions: $sessionIds")
        val firstId = sessionIds?.split(",")?.firstOrNull()?.trim() ?: ""
        httpRequest.setAttribute(SESSION_ID_ATTR, firstId)
        if (sessionIds.isNullOrBlank()) {
            log.warn("[AgentProxyController] sessionIds is null or blank")
            return@runBlocking ResultVo.error("Please provide sessionIds parameter")
        }
        log.info("[AgentProxyController] Calling sessionRouterService.proxyWorkspaceStatus")
        val result = sessionRouterService.proxyWorkspaceStatus(sessionIds)
        log.info("[AgentProxyController] Returning result: code=${result.code}, data=${result.data}")
        result
    }

    /**
     * Proxy a workspace status request for a single session (path-based).
     */
    @GetMapping("/workspace/{sessionId}/status")
    fun proxyWorkspaceStatusSingle(
        @PathVariable sessionId: String,
        httpRequest: HttpServletRequest,
    ): ResultVo<Map<String, Any>> = runBlocking {
        log.info("[AgentProxyController] Received workspace status request for session: $sessionId")
        httpRequest.setAttribute(SESSION_ID_ATTR, sessionId)
        val result = sessionRouterService.proxyWorkspaceStatus(sessionId)
        val singleResult = result.data?.get(sessionId) ?: mapOf("active" to false)
        ResultVo.success(singleResult)
    }

    /**
     * Proxy a workspace file upload request.
     */
    @PostMapping("/workspace/{sessionId}/upload")
    fun proxyWorkspaceUpload(
        @PathVariable sessionId: String,
        @RequestParam(defaultValue = "/workspace") path: String,
        @RequestParam("file") file: MultipartFile,
        httpRequest: HttpServletRequest,
    ): ResultVo<Map<String, Any>> = runBlocking {
        httpRequest.setAttribute(SESSION_ID_ATTR, sessionId)
        log.info("[AgentProxyController] Received workspace upload request for session: $sessionId, fileName: ${file.originalFilename}")
        val fileBytes = file.bytes
        val fileName = file.originalFilename ?: "uploaded-file"
        sessionRouterService.proxyWorkspaceUpload(sessionId, path, fileName, fileBytes)
    }

    /**
     * Proxy a workspace file download request.
     */
    @GetMapping("/workspace/{sessionId}/download")
    fun proxyWorkspaceDownload(
        @PathVariable sessionId: String,
        @RequestParam path: String,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<ByteArray> = runBlocking {
        httpRequest.setAttribute(SESSION_ID_ATTR, sessionId)
        log.info("[AgentProxyController] Received workspace download request for session: $sessionId, path: $path")
        val result = sessionRouterService.proxyWorkspaceDownload(sessionId, path)
        if (result != null) {
            val (bytes, contentType) = result
            val fileName = java.io.File(path).name
            ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$fileName\"")
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(bytes.size.toLong())
                .body(bytes)
        } else {
            ResponseEntity.status(404).build()
        }
    }
}

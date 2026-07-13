package com.agnetix.harnax.client

import com.agnetix.harnax.client.dto.*
import kotlinx.coroutines.flow.Flow

/**
 * Unified Harnax client interface for calling Router API.
 *
 * Implementations:
 * - `SingleHarnaxClient` (harnax-single-client): pure JDK HttpClient, no Spring dependency
 * - `SpringHarnaxClient` (harnax-springboot-client): Spring RestClient + WebClient
 *
 * Streaming methods return Kotlin [Flow] to stay platform-neutral.
 * Each implementation adapts Flow to its underlying HTTP transport.
 */
interface HarnaxClient {

    // ==================== Chat ====================

    /**
     * Send a non-streaming chat request and get the aggregated response.
     */
    suspend fun chat(request: ChatRequest): ResultVo<ChatResponse>

    /**
     * Send a streaming chat request and receive SSE events as a Flow.
     */
    fun chatStream(request: ChatRequest): Flow<ChatEvent>

    // ==================== Command ====================

    /**
     * Send a command request (e.g. /clear, /stop, /compact).
     */
    suspend fun command(request: CommandRequest): ResultVo<CommandResponse>

    // ==================== Confirm ====================

    /**
     * Send a tool confirmation request (SSE streaming).
     */
    fun confirm(request: ConfirmRequest): Flow<ChatEvent>

    // ==================== Session ====================

    /**
     * Clear session data (delete session).
     */
    suspend fun clearSession(sessionId: String): ResultVo<String>

    /**
     * Load chat history for a session.
     */
    suspend fun getHistory(sessionId: String): ResultVo<List<ChatEvent>>

    /**
     * Load all plans for a session.
     */
    suspend fun getPlans(sessionId: String): ResultVo<List<Any>>

    /**
     * Load the current plan for a session.
     */
    suspend fun getCurrentPlan(sessionId: String): ResultVo<Any?>

    // ==================== Workspace ====================

    /**
     * List files in a workspace directory.
     */
    suspend fun listFiles(sessionId: String, path: String = "/workspace"): ResultVo<List<FileInfo>>

    /**
     * Read a file from workspace.
     */
    suspend fun readFile(sessionId: String, path: String): ResultVo<FileContent>

    /**
     * Get workspace status for multiple sessions.
     */
    suspend fun getWorkspaceStatus(sessionIds: List<String>): ResultVo<Map<String, WorkspaceStatus>>

    /**
     * Get workspace status for a single session.
     */
    suspend fun getWorkspaceStatus(sessionId: String): ResultVo<WorkspaceStatus>

    /**
     * Upload a file to workspace.
     */
    suspend fun uploadFile(
        sessionId: String,
        path: String,
        fileName: String,
        bytes: ByteArray,
    ): ResultVo<UploadResult>

    /**
     * Download a file from workspace.
     */
    suspend fun downloadFile(sessionId: String, path: String): DownloadResult
}

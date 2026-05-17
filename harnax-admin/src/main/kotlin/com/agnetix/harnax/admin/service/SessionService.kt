package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.SessionChatUpdateRequest
import com.agnetix.harnax.admin.dto.SessionCreateRequest
import com.agnetix.harnax.admin.dto.SessionResponse
import com.agnetix.harnax.admin.entity.Session
import com.agnetix.harnax.ascopagent.dto.SessionConfigResponse

/**
 * Session service interface
 */
interface SessionService {

    /**
     * Query session list with pagination
     *
     * @param keyword  Fuzzy search field
     * @param status   Status filter field
     * @param pageNum  Current page number
     * @param pageSize Page size
     * @return Paginated result
     */
    fun page(keyword: String?, status: Int?, pageNum: Int, pageSize: Int): Page<Session>

    /**
     * Get single session details
     *
     * @param id Session ID
     * @return Session entity
     */
    fun getSession(id: Long): Session?

    /**
     * Create session
     *
     * @param request Session create request object
     * @return Create result
     */
    fun createSession(request: SessionCreateRequest): Boolean

    /**
     * Update session
     *
     * @param id      Session ID
     * @param request Session update request object
     * @return Update result
     */
    fun updateSession(id: Long, request: SessionCreateRequest): Boolean

    /**
     * Update session configuration
     *
     * @param sessionId Session ID
     * @param request   Session config update request object
     */
    fun updateSessionChatConfig(sessionId: String, request: SessionChatUpdateRequest)

    /**
     * Get session configuration
     *
     * @param sessionId Session ID
     * @return Session config response object
     */
    fun getSessionChatConfig(sessionId: String): SessionConfigResponse

    /**
     * Toggle session enable status
     *
     * @param id     Session ID
     * @param status Enable status (0:disabled, 1:enabled)
     * @return Update result
     */
    fun toggleSessionStatus(id: Long, status: Int): Boolean

    /**
     * Delete session
     *
     * @param id Session ID
     * @return Delete result
     */
    fun deleteSession(id: Long): Boolean

    /**
     * Check if session name exists
     *
     * @param title Session name
     * @return Whether exists
     */
    fun existsByTitle(title: String): Boolean

    /**
     * Convert Session entity to response DTO (including complete skill and MCP information)
     *
     * @param session Session entity
     * @return Response DTO
     */
    fun convertToResponse(session: Session): SessionResponse
}

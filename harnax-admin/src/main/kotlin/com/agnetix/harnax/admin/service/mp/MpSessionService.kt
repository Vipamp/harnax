package com.agnetix.harnax.admin.service.mp

import com.agnetix.harnax.admin.dto.mp.MpCreateSessionRequest
import com.agnetix.harnax.admin.dto.mp.MpSessionResponse
import com.agnetix.harnax.admin.dto.mp.MpUpdateSessionRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.entity.MpSession
import com.agnetix.harnax.mapper.MpChatMessageMapper
import com.agnetix.harnax.mapper.MpSessionMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.UUID

/**
 * Mobile session management service.
 */
@Service
class MpSessionService(
    private val mpSessionMapper: MpSessionMapper,
    private val mpChatMessageMapper: MpChatMessageMapper,
) {

    private val log = LoggerFactory.getLogger(MpSessionService::class.java)

    /**
     * Get all active sessions for the given user.
     */
    fun listSessions(userId: Long): List<MpSessionResponse> {
        val sessions = mpSessionMapper.selectByUserId(userId)
        return sessions.map { toResponse(it) }
    }

    /**
     * Create a new session for the given user.
     */
    fun createSession(userId: Long, request: MpCreateSessionRequest): MpSessionResponse {
        val routerSessionId = request.routerSessionId ?: UUID.randomUUID().toString()

        val session = MpSession().apply {
            this.userId = userId
            this.sessionName = request.sessionName
            this.routerSessionId = routerSessionId
            this.status = 1
            this.createTime = LocalDateTime.now()
            this.updateTime = LocalDateTime.now()
        }

        mpSessionMapper.insert(session)
        log.info("Created mobile session: id={}, userId={}, routerSessionId={}", session.id, userId, routerSessionId)
        return toResponse(session)
    }

    /**
     * Update session name. Only the owner can update.
     */
    fun updateSession(userId: Long, sessionId: Long, request: MpUpdateSessionRequest): MpSessionResponse {
        val session = mpSessionMapper.selectByIdAndUserId(sessionId, userId)
            ?: throw BizException("Session not found or access denied")

        session.sessionName = request.sessionName
        session.updateTime = LocalDateTime.now()
        mpSessionMapper.updateById(session)

        log.info("Updated mobile session: id={}, userId={}", sessionId, userId)
        return toResponse(session)
    }

    /**
     * Delete (archive) a session. Only the owner can delete.
     */
    fun deleteSession(userId: Long, sessionId: Long) {
        val session = mpSessionMapper.selectByIdAndUserId(sessionId, userId)
            ?: throw BizException("Session not found or access denied")

        mpSessionMapper.deleteById(sessionId)
        mpChatMessageMapper.deleteBySessionId(sessionId)
        log.info("Deleted mobile session: id={}, userId={}", sessionId, userId)
    }

    private fun toResponse(session: MpSession): MpSessionResponse {
        val messageCount = mpChatMessageMapper.countBySessionId(session.id)
        return MpSessionResponse(
            id = session.id,
            sessionName = session.sessionName,
            routerSessionId = session.routerSessionId,
            status = session.status,
            messageCount = messageCount,
            createTime = session.createTime,
            updateTime = session.updateTime,
        )
    }
}

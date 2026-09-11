package com.agnetix.harnax.admin.service.mp

import com.agnetix.harnax.admin.dto.mp.MpCreateSessionRequest
import com.agnetix.harnax.admin.dto.mp.MpSessionResponse
import com.agnetix.harnax.admin.dto.mp.MpUpdateSessionRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.entity.MpSession
import com.agnetix.harnax.entity.Session
import com.agnetix.harnax.mapper.AgentMapper
import com.agnetix.harnax.mapper.MpChatMessageMapper
import com.agnetix.harnax.mapper.MpSessionMapper
import com.agnetix.harnax.mapper.SessionMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.UUID

@Service
class MpSessionService(
    private val mpSessionMapper: MpSessionMapper,
    private val mpChatMessageMapper: MpChatMessageMapper,
    private val agentMapper: AgentMapper,
    private val sessionMapper: SessionMapper,
) {

    private val log = LoggerFactory.getLogger(MpSessionService::class.java)

    fun listSessions(userId: Long): List<MpSessionResponse> {
        val sessions = mpSessionMapper.selectByUserId(userId)
        return sessions.map { toResponse(it) }
    }

    fun createSession(userId: Long, tenantId: Long?, request: MpCreateSessionRequest): MpSessionResponse {
        val agentId = request.agentId
            ?: throw BizException("Agent ID is required")
        val agent = agentMapper.selectById(agentId)
            ?: throw BizException("Agent not found")
        if (tenantId != null && agent.tenantId != tenantId) {
            // Same wording as a missing agent, so another tenant's ids stay unlisted.
            throw BizException("Agent not found")
        }
        if (agent.active != 1 || agent.status != 1) {
            throw BizException("Agent is not available")
        }

        val routerSessionId = "mp-${UUID.randomUUID()}"

        val session = Session().apply {
            this.sessionId = routerSessionId
            this.agentId = agent.id
            this.name = agent.name
            this.title = request.sessionName
            this.sessionDescription = ""
            this.description = agent.description
            this.systemPrompt = agent.systemPrompt
            this.modelId = agent.modelId
            this.tenantId = tenantId ?: agent.tenantId
            this.enableThink = 0
            this.enableSearch = 0
            this.enablePlan = 0
            this.status = 1
            this.isPublic = 0
            this.owner = userId.toString()
            this.creator = userId.toString()
            this.active = 1
            this.createTime = LocalDateTime.now()
            this.updateTime = LocalDateTime.now()
        }
        sessionMapper.insert(session)

        val mpSession = MpSession().apply {
            this.userId = userId
            this.sessionName = request.sessionName
            this.routerSessionId = routerSessionId
            this.agentId = agent.id
            this.status = 1
            this.createTime = LocalDateTime.now()
            this.updateTime = LocalDateTime.now()
        }
        mpSessionMapper.insert(mpSession)

        log.info(
            "Created mobile session: id={}, userId={}, routerSessionId={}, agentId={}",
            mpSession.id,
            userId,
            routerSessionId,
            agent.id,
        )
        return toResponse(mpSession, agent.name)
    }

    fun updateSession(userId: Long, sessionId: Long, request: MpUpdateSessionRequest): MpSessionResponse {
        val session = mpSessionMapper.selectByIdAndUserId(sessionId, userId)
            ?: throw BizException("Session not found or access denied")

        session.sessionName = request.sessionName
        session.updateTime = LocalDateTime.now()
        mpSessionMapper.updateById(session)

        val routerSession = sessionMapper.selectBySessionIdAndStatus(session.routerSessionId, 1)
        routerSession?.let {
            it.title = request.sessionName
            it.name = request.sessionName
            it.updateTime = LocalDateTime.now()
            sessionMapper.updateById(it)
        }

        log.info("Updated mobile session: id={}, userId={}", sessionId, userId)
        return toResponse(session)
    }

    @org.springframework.transaction.annotation.Transactional
    fun deleteSession(userId: Long, sessionId: Long) {
        val session = mpSessionMapper.selectByIdAndUserId(sessionId, userId)
            ?: throw BizException("Session not found or access denied")

        val routerSession = sessionMapper.selectBySessionIdAndStatus(session.routerSessionId, 1)
        routerSession?.let { sessionMapper.deleteById(it.id) }

        mpSessionMapper.deleteById(sessionId)
        mpChatMessageMapper.deleteBySessionId(sessionId)
        log.info("Deleted mobile session: id={}, userId={}", sessionId, userId)
    }

    private fun toResponse(session: MpSession, agentName: String? = null): MpSessionResponse {
        val messageCount = mpChatMessageMapper.countBySessionId(session.id)
        val resolvedAgentName = agentName ?: run {
            if (session.agentId > 0) {
                agentMapper.selectById(session.agentId)?.name ?: ""
            } else {
                ""
            }
        }
        return MpSessionResponse(
            id = session.id,
            sessionName = session.sessionName,
            routerSessionId = session.routerSessionId,
            agentId = session.agentId,
            agentName = resolvedAgentName,
            status = session.status,
            messageCount = messageCount,
            createTime = session.createTime,
            updateTime = session.updateTime,
        )
    }
}

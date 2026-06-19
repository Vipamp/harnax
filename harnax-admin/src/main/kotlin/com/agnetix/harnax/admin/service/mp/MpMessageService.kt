package com.agnetix.harnax.admin.service.mp

import com.agnetix.harnax.admin.dto.mp.MpChatMessageDto
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.entity.MpChatMessage
import com.agnetix.harnax.mapper.MpChatMessageMapper
import com.agnetix.harnax.mapper.MpSessionMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * Mobile chat message persistence service.
 */
@Service
class MpMessageService(
    private val mpChatMessageMapper: MpChatMessageMapper,
    private val mpSessionMapper: MpSessionMapper,
) {

    private val log = LoggerFactory.getLogger(MpMessageService::class.java)

    /**
     * Get message history for a session. Verifies ownership via userId.
     */
    fun getHistory(userId: Long, sessionId: Long): List<MpChatMessageDto> {
        verifySessionOwnership(userId, sessionId)
        val messages = mpChatMessageMapper.selectBySessionId(sessionId)
        return messages.map { toDto(it) }
    }

    /**
     * Batch save messages for a session. Verifies ownership via userId.
     */
    fun saveMessages(userId: Long, sessionId: Long, messages: List<MpChatMessageDto>) {
        verifySessionOwnership(userId, sessionId)
        if (messages.isEmpty()) return

        val entities = messages.map { dto ->
            MpChatMessage().apply {
                this.sessionId = sessionId
                this.role = dto.role
                this.content = dto.content
                this.segmentsJson = dto.segmentsJson
                this.tokenUsageJson = dto.tokenUsageJson
                this.imageUrlsJson = dto.imageUrlsJson
                this.createTime = LocalDateTime.now()
            }
        }

        mpChatMessageMapper.batchInsert(entities)
        log.info("Saved {} messages for session={}, userId={}", entities.size, sessionId, userId)
    }

    /**
     * Delete all messages for a session. Verifies ownership via userId.
     */
    fun deleteHistory(userId: Long, sessionId: Long) {
        verifySessionOwnership(userId, sessionId)
        mpChatMessageMapper.deleteBySessionId(sessionId)
        log.info("Deleted message history for session={}, userId={}", sessionId, userId)
    }

    private fun verifySessionOwnership(userId: Long, sessionId: Long) {
        val session = mpSessionMapper.selectByIdAndUserId(sessionId, userId)
            ?: throw BizException("Session not found or access denied")
    }

    private fun toDto(entity: MpChatMessage): MpChatMessageDto = MpChatMessageDto(
        role = entity.role,
        content = entity.content,
        segmentsJson = entity.segmentsJson,
        tokenUsageJson = entity.tokenUsageJson,
        imageUrlsJson = entity.imageUrlsJson,
    )
}

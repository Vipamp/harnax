package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.entity.ToolCallLogEntity
import com.agnetix.harnax.mapper.ToolCallLogMapper
import com.agnetix.harnax.tools.sdk.adaptor.ToolCallInfo
import com.agnetix.harnax.tools.sdk.adaptor.ToolCallLogAdaptor
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.ZoneId

/**
 * ToolCallLogAdaptor Implementation
 * Saves tool call log information to database
 */
@Component
class ToolCallLogAdaptorImpl(
    private val toolCallLogMapper: ToolCallLogMapper,
    private val objectMapper: ObjectMapper,
) : ToolCallLogAdaptor {

    private val log = LoggerFactory.getLogger(ToolCallLogAdaptorImpl::class.java)

    override fun emit(toolCallInfo: ToolCallInfo) {
        try {
            // Convert ToolCallInfo to ToolCallLogEntity
            val entity = convertToEntity(toolCallInfo)

            // Save to database
            val result = toolCallLogMapper.insert(entity)

            if (result > 0) {
                log.debug(
                    "Tool call log saved successfully: agentId={}, sessionId={}, toolName={}, duration={}ms",
                    toolCallInfo.agentId,
                    toolCallInfo.sessionId,
                    toolCallInfo.toolName,
                    toolCallInfo.duration,
                )
            } else {
                log.warn(
                    "Failed to save tool call log: agentId={}, toolName={}",
                    toolCallInfo.agentId,
                    toolCallInfo.toolName,
                )
            }
        } catch (e: Exception) {
            log.error(
                "Error saving tool call log: agentId={}, sessionId={}, toolName={}",
                toolCallInfo.agentId,
                toolCallInfo.sessionId,
                toolCallInfo.toolName,
                e,
            )
            // Do not throw exception to avoid affecting main flow
        }
    }

    /**
     * Convert ToolCallInfo to ToolCallLogEntity
     */
    private fun convertToEntity(toolCallInfo: ToolCallInfo): ToolCallLogEntity {
        val entity = ToolCallLogEntity()
        entity.agentId = toolCallInfo.agentId
        entity.sessionId = toolCallInfo.sessionId
        entity.toolName = toolCallInfo.toolName

        // Convert args Map to JSON string
        try {
            entity.args = objectMapper.writeValueAsString(toolCallInfo.args)
        } catch (e: JacksonException) {
            log.warn(
                "Failed to serialize tool call args to JSON: toolName={}",
                toolCallInfo.toolName,
                e,
            )
            entity.args = "{}"
        }

        entity.result = toolCallInfo.result

        // Set success flag
        entity.success = if (toolCallInfo.success) 1 else 0

        // Convert timestamp to LocalDateTime
        val startDateTime = Instant.ofEpochMilli(toolCallInfo.startTime)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
        entity.startTime = startDateTime

        val endDateTime = Instant.ofEpochMilli(toolCallInfo.endTime)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
        entity.endTime = endDateTime

        entity.duration = toolCallInfo.duration

        // Set record timestamp (using end time)
        entity.ts = endDateTime

        return entity
    }
}

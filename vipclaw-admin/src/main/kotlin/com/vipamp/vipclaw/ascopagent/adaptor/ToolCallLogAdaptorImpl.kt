package com.vipamp.vipclaw.ascopagent.adaptor

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.vipamp.vipclaw.admin.entity.ToolCallLogEntity
import com.vipamp.vipclaw.admin.mapper.ToolCallLogMapper
import com.vipamp.vipclaw.agent.adaptor.ToolCallInfo
import com.vipamp.vipclaw.agent.adaptor.ToolCallLogAdaptor
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId

/**
 * ToolCallLogAdaptor 实现类
 * 将工具调用日志信息保存到数据库
 *
 * @Author: heqingsong
 * @Date: 2026/4/14
 * @Project: vipclaw
 */
@Component
class ToolCallLogAdaptorImpl(
    private val toolCallLogMapper: ToolCallLogMapper,
    private val objectMapper: ObjectMapper
) : ToolCallLogAdaptor {

    private val log = LoggerFactory.getLogger(ToolCallLogAdaptorImpl::class.java)

    override fun emit(toolCallInfo: ToolCallInfo) {
        try {
            // 将 ToolCallInfo 转换为 ToolCallLogEntity 实体
            val entity = convertToEntity(toolCallInfo)

            // 保存到数据库
            val result = toolCallLogMapper.insert(entity)

            if (result > 0) {
                log.debug("Tool call log saved successfully: agentId={}, sessionId={}, toolName={}, duration={}ms",
                    toolCallInfo.agentId, toolCallInfo.sessionId,
                    toolCallInfo.toolName, toolCallInfo.duration)
            } else {
                log.warn("Failed to save tool call log: agentId={}, toolName={}",
                    toolCallInfo.agentId, toolCallInfo.toolName)
            }
        } catch (e: Exception) {
            log.error("Error saving tool call log: agentId={}, sessionId={}, toolName={}",
                toolCallInfo.agentId, toolCallInfo.sessionId, toolCallInfo.toolName, e)
            // 不抛出异常，避免影响主流程
        }
    }

    /**
     * 将 ToolCallInfo 转换为 ToolCallLogEntity 实体
     */
    private fun convertToEntity(toolCallInfo: ToolCallInfo): ToolCallLogEntity {
        val entity = ToolCallLogEntity()
        entity.agentId = toolCallInfo.agentId
        entity.sessionId = toolCallInfo.sessionId
        entity.toolName = toolCallInfo.toolName

        // 将 args Map 转换为 JSON 字符串
        try {
            entity.args = objectMapper.writeValueAsString(toolCallInfo.args)
        } catch (e: JsonProcessingException) {
            log.warn("Failed to serialize tool call args to JSON: toolName={}",
                toolCallInfo.toolName, e)
            entity.args = "{}"
        }

        entity.result = toolCallInfo.result

        // 设置是否成功标志
        entity.success = if (toolCallInfo.success) 1 else 0

        // 将时间戳转换为 LocalDateTime
        val startDateTime = Instant.ofEpochMilli(toolCallInfo.startTime)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
        entity.startTime = startDateTime

        val endDateTime = Instant.ofEpochMilli(toolCallInfo.endTime)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
        entity.endTime = endDateTime

        entity.duration = toolCallInfo.duration

        // 设置记录时间戳（使用结束时间）
        entity.ts = endDateTime

        return entity
    }
}

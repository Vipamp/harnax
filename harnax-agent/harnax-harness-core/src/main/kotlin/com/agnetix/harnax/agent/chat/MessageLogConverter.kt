package com.agnetix.harnax.agent.chat

import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import io.agentscope.core.message.ToolResultBlock
import io.agentscope.core.message.ToolUseBlock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * @Author: heqingsong
 * @Date: 2026/4/16
 * @Description: MessageLogConverter
 * @Project: harnax
 */
object MessageLogConverter {

    /** Msg.timestamp 的规范格式：yyyy-MM-dd HH:mm:ss.SSS（系统时区） */
    private val TS_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    /**
     * 解析 Msg 上持久化的原始时间戳（字符串）为 epoch millis。
     * 若为空或解析失败，回退到当前时间，避免历史消息全部显示为加载时刻。
     */
    private fun parseTimestamp(msg: Msg): Long {
        val ts = msg.timestamp
        if (ts.isNullOrBlank()) return System.currentTimeMillis()
        return try {
            LocalDateTime.parse(ts, TS_FORMATTER)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    fun convert(msg: Msg): List<MessageLog> {
        val timestamp = parseTimestamp(msg)
        val role = msg.role
        return when (role) {
            MsgRole.SYSTEM -> listOf(SystemMessageLog(message = msg.textContent, timestamp = timestamp))
            MsgRole.USER -> listOf(UserMessageLog(message = msg.textContent, timestamp = timestamp))
            MsgRole.TOOL -> {
                msg.getContentBlocks(ToolResultBlock::class.java)
                    .map { ToolResultMessageLog(it.name, MsgExtractHelper.extractToolOutput(it), timestamp = timestamp) }
                    .toList()
            }

            MsgRole.ASSISTANT -> {
                val thinking = MsgExtractHelper.extractThinking(msg) ?: ""
                val text = MsgExtractHelper.extractText(msg) ?: ""
                val toolResultBLocks = msg.getContentBlocks(ToolUseBlock::class.java)
                val toolUseLogs = toolResultBLocks.map {
                    ToolUseLog(it.name, input = it.input as Map<String, Any>)
                }.toList()
                listOf(AssistantMessageLog(thinking, text, toolUseLogs, timestamp = timestamp))
            }
        }
    }
}

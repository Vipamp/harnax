package com.vipamp.vipclaw.ascopagent.adaptor

import com.vipamp.vipclaw.admin.entity.ProcessLogEntity
import com.vipamp.vipclaw.admin.mapper.ProcessLogMapper
import com.vipamp.vipclaw.agent.adaptor.ProcessLog
import com.vipamp.vipclaw.agent.adaptor.ProcessLogAdaptor
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId

/**
 * ProcessLogAdaptor 实现类
 * 将处理日志信息保存到数据库
 *
 * @Author: heqingsong
 * @Date: 2026/4/12
 * @Project: vipclaw
 */
@Component
class ProcessLogAdaptorImpl(
    private val processLogMapper: ProcessLogMapper,
) : ProcessLogAdaptor {

    private val log = LoggerFactory.getLogger(ProcessLogAdaptorImpl::class.java)

    override fun emitLog(processLog: ProcessLog) {
        try {
            // 将 ProcessLog 转换为 ProcessLogEntity 实体
            val entity = convertToEntity(processLog)

            // 保存到数据库
            val result = processLogMapper.insert(entity)

            if (result > 0) {
                log.debug(
                    "Process log saved successfully: agentId={}, agentName={}, sessionId={}, type={}",
                    processLog.agentId,
                    processLog.agentName,
                    processLog.sessionId,
                    processLog.type,
                )
            } else {
                log.warn(
                    "Failed to save process log: agentId={}, agentName={}",
                    processLog.agentId,
                    processLog.agentName,
                )
            }
        } catch (e: Exception) {
            log.error(
                "Error saving process log: agentId={}, agentName={}, sessionId={}",
                processLog.agentId,
                processLog.agentName,
                processLog.sessionId,
                e,
            )
            // 不抛出异常，避免影响主流程
        }
    }

    /**
     * 将 ProcessLog 转换为 ProcessLogEntity 实体
     */
    private fun convertToEntity(processLog: ProcessLog): ProcessLogEntity {
        val entity = ProcessLogEntity()
        entity.agentId = processLog.agentId
        entity.agentName = processLog.agentName
        entity.sessionId = processLog.sessionId
        entity.message = processLog.message
        entity.logType = processLog.type.name

        // 如果有异常，记录堆栈信息
        if (processLog.throwable != null) {
            entity.stackTrace = getStackTrace(processLog.throwable) ?: ""
        }

        // 将时间戳转换为 LocalDateTime
        val dateTime = Instant.ofEpochMilli(processLog.timestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
        entity.ts = dateTime

        return entity
    }

    /**
     * 获取异常堆栈信息
     */
    private fun getStackTrace(throwable: Throwable?): String? {
        if (throwable == null) {
            return null
        }

        return StringWriter().use { sw ->
            PrintWriter(sw).use { pw ->
                throwable.printStackTrace(pw)
                sw.toString()
            }
        }
    }
}

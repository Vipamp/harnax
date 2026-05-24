package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.agent.adaptor.ProcessLog
import com.agnetix.harnax.agent.adaptor.ProcessLogAdaptor
import com.agnetix.harnax.entity.ProcessLogEntity
import com.agnetix.harnax.mapper.ProcessLogMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId

/**
 * ProcessLogAdaptor Implementation
 * Saves process log information to database
 */
@Component
class ProcessLogAdaptorImpl(
    private val processLogMapper: ProcessLogMapper,
) : ProcessLogAdaptor {

    private val log = LoggerFactory.getLogger(ProcessLogAdaptorImpl::class.java)

    override fun emitLog(processLog: ProcessLog) {
        try {
            // Convert ProcessLog to ProcessLogEntity
            val entity = convertToEntity(processLog)

            // Save to database
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
            // Do not throw exception to avoid affecting main flow
        }
    }

    /**
     * Convert ProcessLog to ProcessLogEntity
     */
    private fun convertToEntity(processLog: ProcessLog): ProcessLogEntity {
        val entity = ProcessLogEntity()
        entity.agentId = processLog.agentId
        entity.agentName = processLog.agentName
        entity.sessionId = processLog.sessionId
        entity.message = processLog.message
        entity.logType = processLog.type.name

        // If there is an exception, record stack trace information
        if (processLog.throwable != null) {
            entity.stackTrace = getStackTrace(processLog.throwable) ?: ""
        }

        // Convert timestamp to LocalDateTime
        val dateTime = Instant.ofEpochMilli(processLog.timestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
        entity.ts = dateTime

        return entity
    }

    /**
     * Get exception stack trace
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

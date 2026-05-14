package com.vipamp.vipclaw.admin.service.impl

import com.vipamp.vipclaw.admin.dto.Page
import com.vipamp.vipclaw.admin.entity.SysJobLog
import com.vipamp.vipclaw.admin.mapper.SysJobLogMapper
import com.vipamp.vipclaw.admin.service.SysJobLogService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import kotlin.math.min

/**
 * Scheduled job log service implementation
 *
 * @author vipamp
 * @since 2026-03-16
 */
@Service
class SysJobLogServiceImpl(
    private val sysJobLogMapper: SysJobLogMapper,
) : SysJobLogService {

    private val log = LoggerFactory.getLogger(SysJobLogServiceImpl::class.java)

    override fun save(jobLog: SysJobLog): Boolean {
        log.info("Saving scheduled job log, jobId: {}, jobName: {}", jobLog.jobId, jobLog.jobName)
        val success = sysJobLogMapper.insert(jobLog) > 0
        log.info("Scheduled job log saved {}", if (success) "successfully" else "failed")
        return success
    }

    override fun getJobLogPage(
        jobId: Long?,
        jobName: String?,
        status: Int?,
        startTime: LocalDateTime?,
        endTime: LocalDateTime?,
        pageNum: Int,
        pageSize: Int,
    ): Page<SysJobLog> {
        log.info(
            "Paginated query for scheduled job log list, pageNum: {}, pageSize: {}, jobId: {}, jobName: {}, status: {}",
            pageNum,
            pageSize,
            jobId,
            jobName,
            status,
        )

        // Use MyBatis native query
        val allLogs = sysJobLogMapper.selectJobLogList(jobId, jobName, status, startTime, endTime)

        // Manual pagination
        val page = Page<SysJobLog>(pageNum.toLong(), pageSize.toLong())
        val fromIndex = (pageNum - 1) * pageSize
        val toIndex = min(fromIndex + pageSize, allLogs.size)

        page.records = if (fromIndex < allLogs.size) {
            allLogs.subList(fromIndex, toIndex)
        } else {
            emptyList()
        }
        page.total = allLogs.size.toLong()

        return page
    }
}

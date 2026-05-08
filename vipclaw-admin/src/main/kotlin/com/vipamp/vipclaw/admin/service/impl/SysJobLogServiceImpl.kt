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
 * 定时任务日志服务实现类
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
        log.info("保存定时任务日志，jobId: {}, jobName: {}", jobLog.jobId, jobLog.jobName)
        val success = sysJobLogMapper.insert(jobLog) > 0
        log.info("定时任务日志保存{}", if (success) "成功" else "失败")
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
            "分页查询定时任务日志列表，pageNum: {}, pageSize: {}, jobId: {}, jobName: {}, status: {}",
            pageNum,
            pageSize,
            jobId,
            jobName,
            status,
        )

        // 使用 MyBatis 原生查询
        val allLogs = sysJobLogMapper.selectJobLogList(jobId, jobName, status, startTime, endTime)

        // 手动分页
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

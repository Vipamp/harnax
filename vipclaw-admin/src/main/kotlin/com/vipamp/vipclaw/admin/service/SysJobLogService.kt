package com.vipamp.vipclaw.admin.service

import com.vipamp.vipclaw.admin.dto.Page
import com.vipamp.vipclaw.admin.entity.SysJobLog
import java.time.LocalDateTime

/**
 * Scheduled job log service interface
 */
interface SysJobLogService {

    /**
     * Save scheduled job log
     *
     * @param jobLog Log object
     * @return Whether successful
     */
    fun save(jobLog: SysJobLog): Boolean

    /**
     * Query scheduled job log list with pagination
     *
     * @param jobId     Job ID
     * @param jobName   Job name
     * @param status    Execution status
     * @param startTime Start time
     * @param endTime   End time
     * @param pageNum   Current page number
     * @param pageSize  Page size
     * @return Paginated result
     */
    fun getJobLogPage(jobId: Long?, jobName: String?, status: Int?, startTime: LocalDateTime?, endTime: LocalDateTime?, pageNum: Int, pageSize: Int): Page<SysJobLog>
}

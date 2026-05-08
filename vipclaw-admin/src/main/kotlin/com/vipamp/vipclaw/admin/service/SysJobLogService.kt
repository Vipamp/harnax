package com.vipamp.vipclaw.admin.service

import com.vipamp.vipclaw.admin.dto.Page
import com.vipamp.vipclaw.admin.entity.SysJobLog
import java.time.LocalDateTime

/**
 * 定时任务日志服务接口
 *
 * @author vipamp
 * @since 2026-03-16
 */
interface SysJobLogService {

    /**
     * 保存定时任务日志
     *
     * @param jobLog 日志对象
     * @return 是否成功
     */
    fun save(jobLog: SysJobLog): Boolean

    /**
     * 分页查询定时任务日志列表
     *
     * @param jobId     任务 ID
     * @param jobName   任务名称
     * @param status    执行状态
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @param pageNum   当前页码
     * @param pageSize  每页大小
     * @return 分页结果
     */
    fun getJobLogPage(jobId: Long?, jobName: String?, status: Int?, startTime: LocalDateTime?, endTime: LocalDateTime?, pageNum: Int, pageSize: Int): Page<SysJobLog>
}

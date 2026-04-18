package com.vipamp.vipclaw.admin.service.impl

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper
import com.baomidou.mybatisplus.extension.plugins.pagination.Page
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import com.vipamp.vipclaw.admin.entity.SysJobLog
import com.vipamp.vipclaw.admin.mapper.SysJobLogMapper
import com.vipamp.vipclaw.admin.service.SysJobLogService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.util.StringUtils.hasText
import java.time.LocalDateTime

/**
 * 定时任务日志服务实现类
 *
 * @author vipamp
 * @since 2026-03-16
 */
@Service
class SysJobLogServiceImpl : ServiceImpl<SysJobLogMapper, SysJobLog>(), SysJobLogService {

    private val log = LoggerFactory.getLogger(SysJobLogServiceImpl::class.java)

    override fun getJobLogPage(
        jobId: Long?,
        jobName: String?,
        status: Int?,
        startTime: LocalDateTime?,
        endTime: LocalDateTime?,
        current: Int,
        size: Int
    ): Page<SysJobLog> {
        log.info(
            "分页查询定时任务日志列表，current: {}, size: {}, jobId: {}, jobName: {}, status: {}",
            current, size, jobId, jobName, status
        )

        val page = Page<SysJobLog>(current.toLong(), size.toLong())
        val wrapper = LambdaQueryWrapper<SysJobLog>()

        // 任务ID筛选
        jobId?.let { wrapper.eq(SysJobLog::jobId, it) }

        // 任务名称模糊查询
        if (hasText(jobName)) {
            wrapper.like(SysJobLog::jobName, jobName)
        }

        // 状态筛选
        status?.let { wrapper.eq(SysJobLog::status, it) }

        // 时间范围筛选
        startTime?.let { wrapper.ge(SysJobLog::createTime, it) }
        endTime?.let { wrapper.le(SysJobLog::createTime, it) }

        wrapper.orderByDesc(SysJobLog::createTime)
        return this.page(page, wrapper)
    }
}

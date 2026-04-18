package com.vipamp.vipclaw.admin.job

import com.vipamp.vipclaw.admin.entity.SysJob
import com.vipamp.vipclaw.admin.entity.SysJobLog
import com.vipamp.vipclaw.admin.service.SysJobLogService
import org.quartz.Job
import org.quartz.JobExecutionContext
import org.quartz.JobExecutionException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDateTime

/**
 * 定时任务基类
 * 所有定时任务都需要继承此类
 *
 * @author vipamp
 * @since 2026-03-16
 */
abstract class BaseJob : Job {

    private val log = LoggerFactory.getLogger(BaseJob::class.java)

    @Autowired
    protected lateinit var jobLogService: SysJobLogService

    /**
     * 任务执行入口
     */
    @Throws(JobExecutionException::class)
    override fun execute(context: JobExecutionContext) {
        val dataMap = context.jobDetail.jobDataMap
        val sysJob = dataMap["sysJob"] as? SysJob

        if (sysJob == null) {
            log.error("任务执行失败：未获取到任务信息")
            return
        }

        // 创建日志记录
        val jobLog = SysJobLog().apply {
            jobId = sysJob.id
            jobName = sysJob.jobName
            jobGroup = sysJob.jobGroup
            invokeTarget = sysJob.jobClass
            startTime = LocalDateTime.now()
        }

        log.info("定时任务开始执行 - 任务名称: {}, 任务组: {}", sysJob.jobName, sysJob.jobGroup)

        try {
            // 执行具体任务逻辑
            doExecute(context)

            // 记录成功日志
            jobLog.status = 1
            jobLog.jobMessage = "任务执行成功"
            log.info("定时任务执行成功 - 任务名称: {}", sysJob.jobName)
        } catch (e: Exception) {
            // 记录失败日志
            jobLog.status = 0
            jobLog.jobMessage = "任务执行失败: ${e.message}"
            jobLog.exceptionInfo = getExceptionInfo(e)
            log.error("定时任务执行失败 - 任务名称: {}, 错误: {}", sysJob.jobName, e.message, e)
            throw JobExecutionException(e)
        } finally {
            jobLog.endTime = LocalDateTime.now()
            jobLogService.save(jobLog)
        }
    }

    /**
     * 具体任务执行逻辑，由子类实现
     *
     * @param context 任务执行上下文
     * @throws Exception 执行异常
     */
    @Throws(Exception::class)
    protected abstract fun doExecute(context: JobExecutionContext)

    /**
     * 获取异常信息
     *
     * @param e 异常对象
     * @return 异常信息字符串
     */
    private fun getExceptionInfo(e: Exception): String {
        val sb = StringBuilder()
        sb.append(e.toString()).append("\n")
        for (element in e.stackTrace) {
            sb.append("\tat ").append(element.toString()).append("\n")
        }
        // 限制长度，防止存储溢出
        val result = sb.toString()
        return if (result.length > 4000) result.substring(0, 4000) + "..." else result
    }
}

package com.agnetix.harnax.admin.job

import com.agnetix.harnax.admin.entity.SysJob
import com.agnetix.harnax.admin.entity.SysJobLog
import com.agnetix.harnax.admin.service.SysJobLogService
import org.quartz.Job
import org.quartz.JobExecutionContext
import org.quartz.JobExecutionException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDateTime

/**
 * Base scheduled job class
 * All scheduled jobs must extend this class
 */
abstract class BaseJob : Job {

    private val log = LoggerFactory.getLogger(BaseJob::class.java)

    @Autowired
    protected lateinit var jobLogService: SysJobLogService

    /**
     * Job execution entry point
     */
    @Throws(JobExecutionException::class)
    override fun execute(context: JobExecutionContext) {
        val dataMap = context.jobDetail.jobDataMap
        val sysJob = dataMap["sysJob"] as? SysJob

        if (sysJob == null) {
            log.error("Job execution failed: Job information not retrieved")
            return
        }

        // Create log record
        val jobLog = SysJobLog().apply {
            jobId = sysJob.id
            jobName = sysJob.jobName
            jobGroup = sysJob.jobGroup
            invokeTarget = sysJob.jobClass
            startTime = LocalDateTime.now()
        }

        log.info("Scheduled job started - Job name: {}, Job group: {}", sysJob.jobName, sysJob.jobGroup)

        try {
            // Execute specific job logic
            doExecute(context)

            // Record success log
            jobLog.status = 1
            jobLog.jobMessage = "Job executed successfully"
            log.info("Scheduled job executed successfully - Job name: {}", sysJob.jobName)
        } catch (e: Exception) {
            // Record failure log
            jobLog.status = 0
            jobLog.jobMessage = "Job execution failed: ${e.message}"
            jobLog.exceptionInfo = getExceptionInfo(e)
            log.error("Scheduled job failed - Job name: {}, Error: {}", sysJob.jobName, e.message, e)
            throw JobExecutionException(e)
        } finally {
            jobLog.endTime = LocalDateTime.now()
            jobLogService.save(jobLog)
        }
    }

    /**
     * Specific job execution logic, implemented by subclasses
     *
     * @param context Job execution context
     * @throws Exception Execution exception
     */
    @Throws(Exception::class)
    protected abstract fun doExecute(context: JobExecutionContext)

    /**
     * Get exception information
     *
     * @param e Exception object
     * @return Exception information string
     */
    private fun getExceptionInfo(e: Exception): String {
        val sb = StringBuilder()
        sb.append(e.toString()).append("\n")
        for (element in e.stackTrace) {
            sb.append("\tat ").append(element.toString()).append("\n")
        }
        // Limit length to prevent storage overflow
        val result = sb.toString()
        return if (result.length > 4000) result.substring(0, 4000) + "..." else result
    }
}

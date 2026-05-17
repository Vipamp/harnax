package com.agnetix.harnax.admin.job

import org.quartz.JobExecutionContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Sample scheduled job
 * Used to demonstrate basic usage of scheduled jobs
 */
@Component
class SampleJob : BaseJob() {

    private val log = LoggerFactory.getLogger(SampleJob::class.java)

    @Throws(Exception::class)
    override fun doExecute(context: JobExecutionContext) {
        val currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        log.info("[Sample Job] Executing, current time: {}", currentTime)

        // Write specific business logic here
        // e.g., data synchronization, report generation, scheduled cleanup, etc.

        // Simulate job execution time
        Thread.sleep(1000)

        log.info("[Sample Job] Execution completed")
    }
}

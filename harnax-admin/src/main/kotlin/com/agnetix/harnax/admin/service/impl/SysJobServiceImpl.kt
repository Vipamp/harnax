package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.SysJobCreateRequest
import com.agnetix.harnax.admin.dto.SysJobUpdateRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.SysJobService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.entity.SysJob
import com.agnetix.harnax.mapper.SysJobMapper
import com.github.pagehelper.PageHelper
import jakarta.annotation.PostConstruct
import org.quartz.*
import org.slf4j.LoggerFactory
import org.springframework.scheduling.quartz.SchedulerFactoryBean
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.StringUtils.hasText
import java.time.LocalDateTime

/**
 * Scheduled job service implementation
 */
@Service
class SysJobServiceImpl(
    private val schedulerFactoryBean: SchedulerFactoryBean,
    private val jwtUtil: JwtUtil,
    private val sysJobMapper: SysJobMapper,
) : SysJobService {

    private val log = LoggerFactory.getLogger(SysJobServiceImpl::class.java)

    /**
     * Get Scheduler instance
     */
    private fun getScheduler(): Scheduler = schedulerFactoryBean.scheduler

    override fun page(
        keyword: String?,
        jobStatus: Int?,
        pageNum: Int,
        pageSize: Int,
    ): Page<SysJob> {
        log.info(
            "Paginated query for scheduled job list, pageNum: {}, pageSize: {}, keyword: {}, jobStatus: {}",
            pageNum,
            pageSize,
            keyword,
            jobStatus,
        )
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        PageHelper.startPage<SysJob>(pageNum, pageSize)
        return Page.fromPageInfo(sysJobMapper.selectJobList(keyword, jobStatus, currentUsername))
    }

    override fun getSysJob(id: Long): SysJob? = sysJobMapper.selectById(id)

    @Transactional(rollbackFor = [Exception::class])
    override fun createJob(request: SysJobCreateRequest): Boolean {
        log.info("Creating scheduled job, jobName: {}", request.jobName)

        // Check if job name and group already exist
        val existJob = sysJobMapper.selectByNameAndGroup(
            request.jobName,
            if (hasText(request.jobGroup)) request.jobGroup else "DEFAULT",
        )
        if (existJob != null) {
            throw BizException("Job name and group already exist")
        }

        // Validate Cron expression
        if (!CronExpression.isValidExpression(request.cronExpression)) {
            throw BizException("Invalid Cron expression format")
        }

        val job = SysJob()
        job.jobName = request.jobName!!
        job.jobGroup = (if (hasText(request.jobGroup)) request.jobGroup else "DEFAULT").toString()
        job.jobClass = request.jobClass!!
        job.cronExpression = request.cronExpression!!
        job.jobStatus = 0 // Default paused
        job.concurrent = request.concurrent ?: 1
        job.description = request.description!!
        job.isPublic = 0 // Default not public
        job.active = 1

        // Set creator
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        job.creator = currentUsername!!

        val success = saveJob(job)
        log.info("Scheduled job creation {}, jobId: {}", if (success) "successful" else "failed", job.id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateJob(id: Long, request: SysJobUpdateRequest): Boolean {
        log.info("Updating scheduled job, id: {}", id)

        val job = getSysJob(id) ?: return false
        // If job name or group is modified, check for conflicts
        if (job.jobName != request.jobName ||
            job.jobGroup != (if (hasText(request.jobGroup)) request.jobGroup else "DEFAULT")
        ) {
            val existJob = sysJobMapper.selectByNameAndGroup(
                request.jobName!!,
                (if (hasText(request.jobGroup)) request.jobGroup else "DEFAULT")!!,
            )!!
            if (existJob.id != id) {
                throw BizException("Job name and group already exist")
            }
        }

        // Validate Cron expression
        if (!CronExpression.isValidExpression(request.cronExpression)) {
            throw BizException("Invalid Cron expression format")
        }

        // If job is running, stop it first
        if (job.jobStatus == 1) {
            try {
                getScheduler().deleteJob(JobKey.jobKey(job.jobName, job.jobGroup))
            } catch (e: SchedulerException) {
                log.error("Failed to stop scheduled job", e)
                throw BizException("Failed to stop scheduled job: ${e.message}")
            }
        }

        job.jobName = request.jobName
        job.jobGroup = (if (hasText(request.jobGroup)) request.jobGroup else "DEFAULT").toString()
        job.jobClass = request.jobClass!!
        job.cronExpression = request.cronExpression!!
        job.concurrent = request.concurrent ?: 1
        job.description = request.description!!
        job.jobStatus = 0 // Reset to paused state after update

        val success = updateJobById(job)
        log.info("Scheduled job update {}, id: {}", if (success) "successful" else "failed", id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun deleteJob(id: Long): Boolean {
        log.info("Deleting scheduled job, id: {}", id)

        val job = sysJobMapper.selectById(id)
            ?: throw BizException("Scheduled job not found")

        // If job is running, stop it first
        if (job.jobStatus == 1) {
            try {
                getScheduler().deleteJob(JobKey.jobKey(job.jobName, job.jobGroup))
            } catch (e: SchedulerException) {
                log.error("Failed to stop scheduled job", e)
                throw BizException("Failed to stop scheduled job: ${e.message}")
            }
        }

        return sysJobMapper.deleteById(id) > 0
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun startJob(id: Long): Boolean {
        log.info("Starting scheduled job, id: {}", id)

        val job = sysJobMapper.selectById(id)
            ?: throw BizException("Scheduled job not found")

        if (job.jobStatus == 1) {
            throw BizException("Scheduled job is already running")
        }

        return try {
            scheduleJob(job)

            // Update job status
            sysJobMapper.updateStatus(id, 1) > 0
        } catch (e: Exception) {
            log.error("Failed to start scheduled job", e)
            throw BizException("Failed to start scheduled job: ${e.message}")
        }
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun pauseJob(id: Long): Boolean {
        log.info("Pausing scheduled job, id: {}", id)

        val job = sysJobMapper.selectById(id)
            ?: throw BizException("Scheduled job not found")

        if (job.jobStatus == 0) {
            throw BizException("Scheduled job is already paused")
        }

        return try {
            getScheduler().pauseJob(JobKey.jobKey(job.jobName, job.jobGroup))
            getScheduler().deleteJob(JobKey.jobKey(job.jobName, job.jobGroup))

            // Update job status
            sysJobMapper.updateStatus(id, 0) > 0
        } catch (e: SchedulerException) {
            log.error("Failed to pause scheduled job", e)
            throw BizException("Failed to pause scheduled job: ${e.message}")
        }
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun runJobOnce(id: Long): Boolean {
        log.info("Executing scheduled job immediately, id: {}", id)

        val job = getSysJob(id) ?: return false
        return try {
            // Create temporary job to execute immediately
            val jobKey = JobKey.jobKey(job.jobName + "_ONCE", job.jobGroup)

            // First delete old job if it exists
            if (getScheduler().checkExists(jobKey)) {
                getScheduler().deleteJob(jobKey)
            }

            // Create JobDetail
            val jobDataMap = JobDataMap()
            jobDataMap.put("sysJob", job)

            val jobDetail = JobBuilder.newJob(getJobClass(job.jobClass))
                .withIdentity(jobKey)
                .usingJobData(jobDataMap)
                .build()

            // Create trigger to execute immediately
            val trigger = TriggerBuilder.newTrigger()
                .withIdentity(job.jobName + "_ONCE_TRIGGER", job.jobGroup)
                .startNow()
                .build()

            getScheduler().scheduleJob(jobDetail, trigger)
            true
        } catch (e: Exception) {
            log.error("Failed to execute scheduled job immediately", e)
            throw BizException("Failed to execute scheduled job immediately: ${e.message}")
        }
    }

    override fun loadJobsToScheduler() {
        log.info("Loading all running scheduled jobs to scheduler")

        val runningJobs = getRunningJobs()
        for (job in runningJobs) {
            try {
                scheduleJob(job)
                log.info("Scheduled job loaded: {}.{}", job.jobGroup, job.jobName)
            } catch (e: Exception) {
                log.error("Failed to load scheduled job: {}.{}", job.jobGroup, job.jobName, e)
            }
        }
        log.info("Total {} scheduled jobs loaded", runningJobs.size)
    }

    override fun getRunningJobs(): List<SysJob> = sysJobMapper.selectRunningJobs()

    /**
     * Save scheduled job
     */
    private fun saveJob(job: SysJob): Boolean {
        job.createTime = LocalDateTime.now()
        job.updateTime = LocalDateTime.now()
        return sysJobMapper.insert(job) > 0
    }

    /**
     * Update scheduled job
     */
    private fun updateJobById(job: SysJob): Boolean {
        job.updateTime = LocalDateTime.now()
        return sysJobMapper.updateById(job) > 0
    }

    /**
     * Schedule scheduled job
     *
     * @param job Scheduled job entity
     * @throws Exception Scheduling exception
     */
    @Throws(Exception::class)
    private fun scheduleJob(job: SysJob) {
        // Get job class
        val jobClass = getJobClass(job.jobClass)

        // Build JobDetail
        val jobKey = JobKey.jobKey(job.jobName, job.jobGroup)

        // If job already exists, delete it first
        if (getScheduler().checkExists(jobKey)) {
            getScheduler().deleteJob(jobKey)
        }

        val jobDataMap = JobDataMap()
        jobDataMap.put("sysJob", job)

        val jobDetail = JobBuilder.newJob(jobClass)
            .withIdentity(jobKey)
            .usingJobData(jobDataMap)
            .build()

        // Build CronTrigger
        var cronScheduleBuilder = CronScheduleBuilder.cronSchedule(job.cronExpression)

        // Based on concurrent setting
        if (job.concurrent != null && job.concurrent == 0) {
            // Disable concurrent execution
            cronScheduleBuilder = cronScheduleBuilder.withMisfireHandlingInstructionDoNothing()
        } else {
            // Allow concurrent execution
            cronScheduleBuilder = cronScheduleBuilder.withMisfireHandlingInstructionFireAndProceed()
        }

        val trigger = TriggerBuilder.newTrigger()
            .withIdentity(job.jobName + "_TRIGGER", job.jobGroup)
            .withSchedule(cronScheduleBuilder)
            .build()

        getScheduler().scheduleJob(jobDetail, trigger)
    }

    /**
     * Get job class
     *
     * @param className Full class path name
     * @return Job class
     * @throws ClassNotFoundException Class not found exception
     */
    @Throws(ClassNotFoundException::class)
    @Suppress("UNCHECKED_CAST")
    private fun getJobClass(className: String): Class<out Job> {
        val clazz = Class.forName(className)
        if (!Job::class.java.isAssignableFrom(clazz)) {
            throw BizException("Job class must implement org.quartz.Job interface")
        }
        return clazz as Class<out Job>
    }

    /**
     * Load all running jobs after application startup
     */
    @PostConstruct
    fun init() {
        try {
            // Wait for scheduler initialization to complete
            Thread.sleep(3000)
            loadJobsToScheduler()
        } catch (e: Exception) {
            log.error("Failed to initialize scheduled jobs", e)
        }
    }
}

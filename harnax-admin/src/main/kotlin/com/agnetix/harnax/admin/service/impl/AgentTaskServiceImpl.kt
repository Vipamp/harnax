package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.AgentTaskCreateRequest
import com.agnetix.harnax.admin.dto.AgentTaskResponse
import com.agnetix.harnax.admin.dto.AgentTaskUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.job.AgentTaskJob
import com.agnetix.harnax.admin.service.AgentTaskService
import com.agnetix.harnax.admin.service.AgentService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.entity.AgentTask
import com.agnetix.harnax.mapper.AgentTaskMapper
import com.github.pagehelper.PageHelper
import jakarta.annotation.PostConstruct
import org.quartz.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.quartz.SchedulerFactoryBean
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class AgentTaskServiceImpl(
    private val agentTaskMapper: AgentTaskMapper,
    private val agentService: AgentService,
    private val jwtUtil: JwtUtil,
    @Qualifier("schedulerFactoryBean") private val schedulerFactory: SchedulerFactoryBean,
    @Value("\${agent-task.scheduler-enabled:true}") private val schedulerEnabled: Boolean,
) : AgentTaskService {

    private val log = LoggerFactory.getLogger(AgentTaskServiceImpl::class.java)

    private val scheduler: Scheduler
        get() = schedulerFactory.scheduler

    @PostConstruct
    fun init() {
        if (!schedulerEnabled) {
            log.info("Agent task scheduler is disabled on this instance")
            return
        }
        Thread {
            try {
                Thread.sleep(3000)
                loadTasksToScheduler()
            } catch (e: Exception) {
                log.error("Failed to load agent tasks to scheduler", e)
            }
        }.start()
    }

    override fun page(
        name: String?,
        agentId: Long?,
        taskStatus: Int?,
        pageNum: Int,
        pageSize: Int,
    ): Page<AgentTask> {
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        PageHelper.startPage<AgentTask>(pageNum, pageSize)
        return Page.fromPageInfo(agentTaskMapper.selectTaskList(name, agentId, taskStatus, currentUsername))
    }

    override fun getAgentTask(id: Long): AgentTask? = agentTaskMapper.selectById(id)

    @Transactional(rollbackFor = [Exception::class])
    override fun createAgentTask(request: AgentTaskCreateRequest): Boolean {
        log.info("Creating agent task, name: {}, agentId: {}", request.name, request.agentId)

        // Check name uniqueness
        val existing = agentTaskMapper.selectByName(request.name)
        if (existing != null) {
            throw BizException("Task name already exists")
        }

        // Validate cron expression
        if (!CronExpression.isValidExpression(request.cronExpression)) {
            throw BizException("Invalid cron expression")
        }

        // Get agent name
        val agent = agentService.getAgent(request.agentId!!)
            ?: throw BizException("Agent not found")

        val task = AgentTask()
        task.name = request.name
        task.agentId = request.agentId
        task.agentName = agent.name
        task.prompt = request.prompt
        task.cronExpression = request.cronExpression
        task.taskStatus = 0 // paused by default
        task.concurrent = request.concurrent
        task.timeoutSeconds = request.timeoutSeconds
        task.description = request.description
        task.isPublic = request.isPublic
        task.tenantId = TenantContext.getTenantId() ?: 1
        task.active = 1
        task.creator = UserContextUtil.getCurrentUsername(jwtUtil) ?: ""
        task.createTime = LocalDateTime.now()
        task.updateTime = LocalDateTime.now()

        val success = agentTaskMapper.insert(task) > 0
        log.info("Agent task creation {}, id: {}", if (success) "successful" else "failed", task.id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateAgentTask(id: Long, request: AgentTaskUpdateRequest): Boolean {
        log.info("Updating agent task, id: {}", id)

        val task = agentTaskMapper.selectById(id)
            ?: throw BizException("Agent task not found")

        // If task was running, stop it first
        if (task.taskStatus == 1 && schedulerEnabled) {
            unscheduleTask(task)
        }

        // Check name uniqueness if name changed
        if (request.name != null && request.name != task.name) {
            val existing = agentTaskMapper.selectByName(request.name)
            if (existing != null) {
                throw BizException("Task name already exists")
            }
            task.name = request.name
        }

        // Validate cron if changed
        if (request.cronExpression != null) {
            if (!CronExpression.isValidExpression(request.cronExpression)) {
                throw BizException("Invalid cron expression")
            }
            task.cronExpression = request.cronExpression
        }

        // Update agent if changed
        if (request.agentId != null && request.agentId != task.agentId) {
            val agent = agentService.getAgent(request.agentId)
                ?: throw BizException("Agent not found")
            task.agentId = request.agentId
            task.agentName = agent.name
        }

        // Update other fields
        request.prompt?.let { task.prompt = it }
        request.concurrent?.let { task.concurrent = it }
        request.timeoutSeconds?.let { task.timeoutSeconds = it }
        request.description?.let { task.description = it }
        request.isPublic?.let { task.isPublic = it }

        // Reset to paused after update
        task.taskStatus = 0
        task.updateTime = LocalDateTime.now()

        return agentTaskMapper.updateById(task) > 0
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun deleteAgentTask(id: Long): Boolean {
        log.info("Deleting agent task, id: {}", id)

        val task = agentTaskMapper.selectById(id)
            ?: throw BizException("Agent task not found")

        // Stop if running
        if (task.taskStatus == 1 && schedulerEnabled) {
            unscheduleTask(task)
        }

        return agentTaskMapper.deleteById(id) > 0
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun startTask(id: Long): Boolean {
        log.info("Starting agent task, id: {}", id)

        val task = agentTaskMapper.selectById(id)
            ?: throw BizException("Agent task not found")

        if (task.taskStatus == 1) {
            throw BizException("Task is already running")
        }

        if (schedulerEnabled) {
            scheduleTask(task)
        }
        return agentTaskMapper.updateStatus(id, 1) > 0
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun pauseTask(id: Long): Boolean {
        log.info("Pausing agent task, id: {}", id)

        val task = agentTaskMapper.selectById(id)
            ?: throw BizException("Agent task not found")

        if (task.taskStatus == 0) {
            return true // Already paused
        }

        if (schedulerEnabled) {
            unscheduleTask(task)
        }
        return agentTaskMapper.updateStatus(id, 0) > 0
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun runTaskOnce(id: Long): Boolean {
        log.info("Running agent task once, id: {}", id)

        val task = agentTaskMapper.selectById(id)
            ?: throw BizException("Agent task not found")

        if (!schedulerEnabled) {
            throw BizException("Scheduler is disabled on this instance")
        }

        val jobKey = JobKey("AgentTask_${task.id}_ONCE", "AgentTaskGroup_ONCE")
        val jobDetail = JobBuilder.newJob(AgentTaskJob::class.java)
            .withIdentity(jobKey)
            .usingJobData("agentTask", task)
            .build()

        val trigger = TriggerBuilder.newTrigger()
            .withIdentity(TriggerKey("AgentTask_${task.id}_ONCE_trigger", "AgentTaskGroup_ONCE"))
            .startNow()
            .build()

        scheduler.scheduleJob(jobDetail, trigger)
        return true
    }

    override fun loadTasksToScheduler() {
        log.info("Loading agent tasks to scheduler")
        val runningTasks = agentTaskMapper.selectRunningTasks()
        log.info("Found {} running agent tasks", runningTasks.size)

        for (task in runningTasks) {
            try {
                scheduleTask(task)
                log.info("Loaded agent task to scheduler: id={}, name={}", task.id, task.name)
            } catch (e: Exception) {
                log.error("Failed to load agent task: id={}, name={}, error={}", task.id, task.name, e.message, e)
            }
        }
    }

    override fun getRunningTasks(): List<AgentTask> = agentTaskMapper.selectRunningTasks()

    override fun convertToResponse(task: AgentTask): AgentTaskResponse {
        return AgentTaskResponse.fromEntity(task)
    }

    private fun scheduleTask(task: AgentTask) {
        val jobKey = JobKey("AgentTask_${task.id}", "AgentTaskGroup")
        val jobDetail = JobBuilder.newJob(AgentTaskJob::class.java)
            .withIdentity(jobKey)
            .usingJobData("agentTask", task)
            .build()

        val triggerBuilder = TriggerBuilder.newTrigger()
            .withIdentity(TriggerKey("AgentTask_${task.id}_trigger", "AgentTaskGroup"))
            .withSchedule(
                CronScheduleBuilder.cronSchedule(task.cronExpression)
                    .apply {
                        if (task.concurrent == 0) {
                            withMisfireHandlingInstructionDoNothing()
                        } else {
                            withMisfireHandlingInstructionFireAndProceed()
                        }
                    }
            )

        val trigger = triggerBuilder.build()
        scheduler.scheduleJob(jobDetail, trigger)
        log.info("Scheduled agent task: id={}, name={}, cron={}", task.id, task.name, task.cronExpression)
    }

    private fun unscheduleTask(task: AgentTask) {
        val jobKey = JobKey("AgentTask_${task.id}", "AgentTaskGroup")
        scheduler.deleteJob(jobKey)
        log.info("Unscheduled agent task: id={}, name={}", task.id, task.name)
    }
}

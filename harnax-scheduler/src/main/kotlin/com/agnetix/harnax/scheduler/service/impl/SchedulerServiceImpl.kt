package com.agnetix.harnax.scheduler.service.impl

import com.agnetix.harnax.entity.AgentTask
import com.agnetix.harnax.entity.AgentTaskLog
import com.agnetix.harnax.mapper.AgentTaskLogMapper
import com.agnetix.harnax.mapper.AgentTaskMapper
import com.agnetix.harnax.scheduler.client.RouterClient
import com.agnetix.harnax.scheduler.job.AgentTaskJob
import com.agnetix.harnax.scheduler.service.AgentTaskExecutionGuard
import com.agnetix.harnax.scheduler.service.SchedulerService
import jakarta.annotation.PostConstruct
import org.quartz.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.quartz.SchedulerFactoryBean
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

@Service
class SchedulerServiceImpl(
    private val schedulerFactory: SchedulerFactoryBean,
    private val agentTaskMapper: AgentTaskMapper,
    private val agentTaskLogMapper: AgentTaskLogMapper,
    private val routerClient: RouterClient,
    private val executionGuard: AgentTaskExecutionGuard,
    @Value("\${scheduler.enabled:true}") private val schedulerEnabled: Boolean,
) : SchedulerService {

    private val log = LoggerFactory.getLogger(SchedulerServiceImpl::class.java)

    private val scheduler: Scheduler
        get() = schedulerFactory.scheduler

    @PostConstruct
    fun init() {
        if (!schedulerEnabled) {
            log.info("Scheduler is disabled on this instance")
            return
        }

        // Register beans in scheduler context so Quartz jobs can access them
        val schedulerContext = scheduler.context
        schedulerContext["routerClient"] = routerClient
        schedulerContext["executionGuard"] = executionGuard
        schedulerContext["taskLogMapper"] = agentTaskLogMapper

        Thread {
            try {
                Thread.sleep(3000)
                loadTasksToScheduler()
            } catch (e: Exception) {
                log.error("Failed to load agent tasks to scheduler", e)
            }
        }.start()
    }

    override fun scheduleTask(task: AgentTask) {
        val jobKey = JobKey("AgentTask_${task.id}", "AgentTaskGroup")
        val jobDataMap = JobDataMap()
        jobDataMap.put("agentTask", task)
        val jobDetail = JobBuilder.newJob(AgentTaskJob::class.java)
            .withIdentity(jobKey)
            .usingJobData(jobDataMap)
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
                    },
            )

        val trigger = triggerBuilder.build()

        if (scheduler.checkExists(jobKey)) {
            scheduler.rescheduleJob(TriggerKey("AgentTask_${task.id}_trigger", "AgentTaskGroup"), trigger)
            scheduler.addJob(jobDetail, true)
        } else {
            scheduler.scheduleJob(jobDetail, trigger)
        }
        log.info("Scheduled agent task: id={}, name={}, cron={}", task.id, task.name, task.cronExpression)
    }

    override fun unscheduleTask(task: AgentTask) {
        val jobKey = JobKey("AgentTask_${task.id}", "AgentTaskGroup")
        scheduler.deleteJob(jobKey)
        log.info("Unscheduled agent task: id={}, name={}", task.id, task.name)
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

    override fun startTask(id: Long): Boolean {
        val task = agentTaskMapper.selectById(id)
            ?: throw RuntimeException("Agent task not found: $id")

        if (task.taskStatus == 1) {
            throw RuntimeException("Task is already running")
        }

        scheduleTask(task)
        return agentTaskMapper.updateStatus(id, 1) > 0
    }

    override fun pauseTask(id: Long): Boolean {
        val task = agentTaskMapper.selectById(id)
            ?: throw RuntimeException("Agent task not found: $id")

        if (task.taskStatus == 0) {
            return true // Already paused
        }

        unscheduleTask(task)
        return agentTaskMapper.updateStatus(id, 0) > 0
    }

    override fun runTaskOnce(id: Long): Boolean {
        val task = agentTaskMapper.selectById(id)
            ?: throw RuntimeException("Agent task not found: $id")

        val uniqueId = java.util.UUID.randomUUID().toString().substring(0, 8)
        val jobKey = JobKey("AgentTask_${task.id}_ONCE_$uniqueId", "AgentTaskGroup_ONCE")
        val jobDataMap = JobDataMap()
        jobDataMap.put("agentTask", task)
        val jobDetail = JobBuilder.newJob(AgentTaskJob::class.java)
            .withIdentity(jobKey)
            .usingJobData(jobDataMap)
            .build()

        val trigger = TriggerBuilder.newTrigger()
            .withIdentity(TriggerKey("AgentTask_${task.id}_ONCE_${uniqueId}_trigger", "AgentTaskGroup_ONCE"))
            .startNow()
            .build()

        scheduler.scheduleJob(jobDetail, trigger)
        return true
    }

    @Async
    override fun triggerManually(id: Long): Boolean {
        val task = agentTaskMapper.selectById(id)
            ?: throw RuntimeException("Agent task not found: $id")

        val triggerTime = LocalDateTime.now()

        // Multi-instance guard
        if (!executionGuard.tryAcquireLock(task.id, triggerTime)) {
            log.info("Task {} already being executed by another instance, skipping", task.id)
            return false
        }

        log.info("Manually triggering agent task: id={}, name={}", task.id, task.name)

        val taskLog = AgentTaskLog().apply {
            taskId = task.id
            taskName = task.name
            prompt = task.prompt
            startTime = LocalDateTime.now()
            creator = task.creator
            createTime = LocalDateTime.now()
            status = 3 // running
        }

        // Generate task sessionId: task-{taskId}-{uuid}
        val sessionId = "task-${task.id}-${UUID.randomUUID()}"
        taskLog.sessionId = sessionId

        // Insert running log immediately so it's visible on the page
        agentTaskLogMapper.insert(taskLog)
        log.info("Inserted running task log: id={}, taskId={}", taskLog.id, task.id)

        try {
            // 1. Call router to execute
            val response = routerClient.chat(sessionId, task.prompt)

            // 2. Record success
            taskLog.response = response.content
            taskLog.tokenUsage = response.tokenUsage?.toString() ?: ""
            taskLog.status = 1

            log.info("Manual task execution succeeded: id={}, name={}", task.id, task.name)
        } catch (e: Exception) {
            log.error("Manual task execution failed: id={}, name={}, error={}", task.id, task.name, e.message, e)
            taskLog.status = 0
            taskLog.errorInfo = e.message?.take(4000) ?: "Unknown error"
        } finally {
            // 3. Clear agent cache on agent-service
            routerClient.clearSession(sessionId)

            val endTime = LocalDateTime.now()
            taskLog.endTime = endTime
            taskLog.durationMs = if (taskLog.startTime != null) {
                Duration.between(taskLog.startTime, endTime).toMillis()
            } else {
                0
            }

            // 4. Update task log with final result
            try {
                agentTaskLogMapper.updateById(taskLog)
            } catch (e: Exception) {
                log.error("Failed to update task log: id={}, error={}", taskLog.id, e.message, e)
            }

            executionGuard.updateExecutionStatus(
                task.id,
                triggerTime,
                taskLog.status == 1,
                taskLog.startTime ?: endTime,
                endTime,
            )
        }

        return true
    }

    override fun getScheduledTaskIds(): Set<Long> {
        val jobKeys = scheduler.getJobKeys(org.quartz.impl.matchers.GroupMatcher.jobGroupEquals("AgentTaskGroup"))
        return jobKeys.mapNotNull { key ->
            key.name.removePrefix("AgentTask_").toLongOrNull()
        }.toSet()
    }
}

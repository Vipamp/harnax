package com.agnetix.harnax.scheduler.controller

import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.scheduler.health.SchedulerStatus
import com.agnetix.harnax.scheduler.service.SchedulerService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

@Tag(name = "Scheduler Management", description = "Agent task scheduling and execution APIs")
@RestController
@RequestMapping("/api/scheduler")
class SchedulerController(
    private val schedulerService: SchedulerService,
    private val status: SchedulerStatus,
) {

    private val log = LoggerFactory.getLogger(SchedulerController::class.java)

    @Operation(summary = "Manually trigger a one-time task execution")
    @PostMapping("/tasks/{id}/trigger")
    fun trigger(@PathVariable id: Long): ResultVo<String> {
        requireEnabled("trigger task $id")?.let { return it }
        return try {
            if (schedulerService.triggerManually(id)) {
                ResultVo.success("Task triggered")
            } else {
                // 40901, not a message: both "already running" and "another instance won the lock" mean
                // the same thing to the caller — try again later.
                ResultVo.error(CODE_EXECUTION_IN_PROGRESS, "Task execution is already in progress")
            }
        } catch (e: Exception) {
            log.error("Failed to trigger task: id={}", id, e)
            ResultVo.error("Failed to trigger task: ${e.message}")
        }
    }

    @Operation(summary = "Start a scheduled task")
    @PostMapping("/tasks/{id}/start")
    fun start(@PathVariable id: Long): ResultVo<String> {
        requireEnabled("start task $id")?.let { return it }
        return try {
            val success = schedulerService.startTask(id)
            if (success) {
                ResultVo.success("Task started")
            } else {
                ResultVo.error("Start failed")
            }
        } catch (e: Exception) {
            log.error("Failed to start task: id={}", id, e)
            ResultVo.error("Failed to start task: ${e.message}")
        }
    }

    @Operation(summary = "Pause a scheduled task")
    @PostMapping("/tasks/{id}/pause")
    fun pause(@PathVariable id: Long): ResultVo<String> {
        requireEnabled("pause task $id")?.let { return it }
        return try {
            val success = schedulerService.pauseTask(id)
            if (success) {
                ResultVo.success("Task paused")
            } else {
                ResultVo.error("Pause failed")
            }
        } catch (e: Exception) {
            log.error("Failed to pause task: id={}", id, e)
            ResultVo.error("Failed to pause task: ${e.message}")
        }
    }

    @Operation(summary = "Trigger a one-time execution via Quartz")
    @PostMapping("/tasks/{id}/run-once")
    fun runOnce(@PathVariable id: Long): ResultVo<String> {
        requireEnabled("run task $id once")?.let { return it }
        return try {
            val success = schedulerService.runTaskOnce(id)
            if (success) {
                ResultVo.success("Task run once scheduled")
            } else {
                // Same code as trigger: runTaskOnce returns false only for a conflict, so a plain 500
                // would tell the caller nothing about whether to retry.
                ResultVo.error(CODE_EXECUTION_IN_PROGRESS, "Task execution is already in progress")
            }
        } catch (e: Exception) {
            log.error("Failed to run task once: id={}", id, e)
            ResultVo.error("Failed to run task once: ${e.message}")
        }
    }

    @Operation(summary = "Get scheduled task status")
    @GetMapping("/tasks/status")
    fun status(): ResultVo<Map<String, Any>> = try {
        val scheduledIds = schedulerService.getScheduledTaskIds()
        ResultVo.success(
            mapOf(
                "scheduledTaskCount" to scheduledIds.size,
                "scheduledTaskIds" to scheduledIds,
            ),
        )
    } catch (e: Exception) {
        log.error("Failed to get scheduler status", e)
        ResultVo.error("Failed to get scheduler status: ${e.message}")
    }

    @Operation(summary = "Reload all tasks from database")
    @PostMapping("/reload")
    fun reload(): ResultVo<String> {
        requireEnabled("reload tasks")?.let { return it }
        return try {
            if (schedulerService.loadTasksToScheduler()) {
                ResultVo.success("Tasks reloaded")
            } else {
                ResultVo.error("Some active tasks could not be scheduled, see /actuator/health for details")
            }
        } catch (e: Exception) {
            log.error("Failed to reload tasks", e)
            ResultVo.error("Failed to reload tasks: ${e.message}")
        }
    }

    /**
     * Not gated by [requireEnabled]: stopping an execution writes no Quartz object. It flips the log row
     * to 4 and asks the router to interrupt the live session, both of which are exactly as correct on a
     * node that refuses to *schedule* new work. Refusing it here would strand executions that are
     * already running.
     */
    @Operation(summary = "Stop a running task execution")
    @PostMapping("/tasks/logs/{logId}/stop")
    fun stopTask(@PathVariable logId: Long): ResultVo<String> = try {
        val success = schedulerService.stopTask(logId)
        if (success) {
            ResultVo.success("Task stopped")
        } else {
            ResultVo.error("Task is not running or already completed")
        }
    } catch (e: Exception) {
        log.error("Failed to stop task: logId={}", logId, e)
        ResultVo.error("Failed to stop task: ${e.message}")
    }

    /**
     * `scheduler.enabled=false` is meant to make this node inert, but it only ever skipped the startup
     * load and the scheduler-context registration: [SchedulerFactoryBean] starts regardless, so a write
     * here still registers a job that fires and then dies inside `AgentTaskJob` — `null as SchedulerService`
     * on a context nobody populated — while the caller has already been answered 200. Answering on the
     * write surface is the only place that can tell the difference, and it is where the gate belongs.
     *
     * @return null when scheduling is enabled here, otherwise the response the caller gets.
     */
    private fun requireEnabled(action: String): ResultVo<String>? {
        if (status.schedulerEnabled) {
            return null
        }
        log.warn("Refusing to {} on this instance: scheduler.enabled=false", action)
        return ResultVo.error(CODE_SCHEDULER_DISABLED, "Scheduling is disabled on this instance")
    }

    companion object {
        /** The task already has a live execution; the caller should poll instead of retrying. */
        const val CODE_EXECUTION_IN_PROGRESS = 40901

        /**
         * This instance will not take scheduling work at all (`scheduler.enabled=false`). Kept out of the
         * 40901/40902 pair on purpose: retrying or rolling anything back is not the caller's problem, the
         * request simply does not belong on this node.
         */
        const val CODE_SCHEDULER_DISABLED = 40903
    }
}

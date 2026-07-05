package com.agnetix.harnax.scheduler.controller

import com.agnetix.harnax.common.dto.ResultVo
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
) {

    private val log = LoggerFactory.getLogger(SchedulerController::class.java)

    @Operation(summary = "Manually trigger a one-time task execution")
    @PostMapping("/tasks/{id}/trigger")
    fun trigger(@PathVariable id: Long): ResultVo<String> = try {
        val success = schedulerService.triggerManually(id)
        if (success) {
            ResultVo.success("Task triggered")
        } else {
            ResultVo.error("Task is already being executed by another instance")
        }
    } catch (e: Exception) {
        log.error("Failed to trigger task: id={}", id, e)
        ResultVo.error("Failed to trigger task: ${e.message}")
    }

    @Operation(summary = "Start a scheduled task")
    @PostMapping("/tasks/{id}/start")
    fun start(@PathVariable id: Long): ResultVo<String> = try {
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

    @Operation(summary = "Pause a scheduled task")
    @PostMapping("/tasks/{id}/pause")
    fun pause(@PathVariable id: Long): ResultVo<String> = try {
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

    @Operation(summary = "Trigger a one-time execution via Quartz")
    @PostMapping("/tasks/{id}/run-once")
    fun runOnce(@PathVariable id: Long): ResultVo<String> = try {
        val success = schedulerService.runTaskOnce(id)
        if (success) {
            ResultVo.success("Task run once scheduled")
        } else {
            ResultVo.error("Run once failed")
        }
    } catch (e: Exception) {
        log.error("Failed to run task once: id={}", id, e)
        ResultVo.error("Failed to run task once: ${e.message}")
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
    fun reload(): ResultVo<String> = try {
        schedulerService.loadTasksToScheduler()
        ResultVo.success("Tasks reloaded")
    } catch (e: Exception) {
        log.error("Failed to reload tasks", e)
        ResultVo.error("Failed to reload tasks: ${e.message}")
    }

    @Operation(summary = "Health check")
    @GetMapping("/health")
    fun health(): ResultVo<String> = ResultVo.success("OK")
}

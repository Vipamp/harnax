package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.*
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.mapRecords
import com.agnetix.harnax.admin.service.SysJobLogService
import com.agnetix.harnax.admin.service.SysJobService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime

/**
 * Scheduled job management controller
 */
@RestController
@RequestMapping("/api/jobs")
@Tag(name = "Scheduled Job Management", description = "Scheduled job related APIs")
class SysJobController(
    private val sysJobService: SysJobService,
    private val sysJobLogService: SysJobLogService,
) {

    private val log = LoggerFactory.getLogger(SysJobController::class.java)

    @GetMapping("/page")
    @Operation(summary = "Get scheduled job list with pagination", description = "Paginated query for scheduled job information")
    fun pageSysJob(
        @Parameter(description = "Page number", example = "1") @RequestParam(
            name = "pageNum",
            defaultValue = "1",
        ) pageNum: Int?,
        @Parameter(description = "Page size", example = "10") @RequestParam(
            name = "pageSize",
            defaultValue = "10",
        ) pageSize: Int?,
        @Parameter(description = "Fuzzy search field (job name)") @RequestParam(
            name = "keyword",
            required = false,
        ) keyword: String?,
        @Parameter(description = "Status filter (0-paused, 1-running)") @RequestParam(
            name = "jobStatus",
            required = false,
        ) jobStatus: Int?,
    ): ResultVo<Page<SysJobResponse>> = try {
        val page = sysJobService.page(keyword, jobStatus, pageNum ?: 1, pageSize ?: 10)
        ResultVo.success(page.mapRecords { SysJobResponse.fromEntity(it) })
    } catch (e: Exception) {
        log.error("Failed to get scheduled job list", e)
        ResultVo.error(e.message ?: "Failed to get scheduled job list")
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get scheduled job details", description = "Get job information by job ID")
    fun getSysJob(
        @Parameter(description = "Job ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<SysJobResponse?> = try {
        val job = sysJobService.getSysJob(id)
        ResultVo.success(SysJobResponse.fromEntity(job))
    } catch (e: Exception) {
        log.error("Failed to get scheduled job details", e)
        ResultVo.error(e.message ?: "Failed to get scheduled job details")
    }

    @PostMapping
    @Operation(summary = "Create scheduled job", description = "Add new scheduled job information")
    fun createJob(
        @Valid @RequestBody request: SysJobCreateRequest,
    ): ResultVo<Void> = try {
        if (sysJobService.createJob(request)) ResultVo.success() else ResultVo.error("Failed to create scheduled job")
    } catch (e: Exception) {
        log.error("Failed to create scheduled job", e)
        ResultVo.error(e.message ?: "Failed to create scheduled job")
    }

    @PutMapping("/update/{id}")
    @Operation(summary = "Update scheduled job", description = "Update job information by job ID")
    fun updateSysJob(
        @Parameter(description = "Job ID") @PathVariable(name = "id") id: Long,
        @Valid @RequestBody request: SysJobUpdateRequest,
    ): ResultVo<Void> = try {
        if (sysJobService.updateJob(id, request)) ResultVo.success() else ResultVo.error("Failed to update scheduled job")
    } catch (e: Exception) {
        log.error("Failed to update scheduled job", e)
        ResultVo.error(e.message ?: "Failed to update scheduled job")
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete scheduled job", description = "Delete job by job ID")
    fun deleteSysJob(
        @Parameter(description = "Job ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<Void> = try {
        if (sysJobService.deleteJob(id)) ResultVo.success() else ResultVo.error("Failed to delete scheduled job")
    } catch (e: Exception) {
        log.error("Failed to delete scheduled job", e)
        ResultVo.error(e.message ?: "Failed to delete scheduled job")
    }

    @PostMapping("/start/{id}")
    @Operation(summary = "Start scheduled job", description = "Start specified scheduled job")
    fun startJob(
        @Parameter(description = "Job ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<Void> = try {
        if (sysJobService.startJob(id)) ResultVo.success() else ResultVo.error("Failed to start scheduled job")
    } catch (e: Exception) {
        log.error("Failed to start scheduled job", e)
        ResultVo.error(e.message ?: "Failed to start scheduled job")
    }

    @PostMapping("/pause/{id}")
    @Operation(summary = "Pause scheduled job", description = "Pause specified scheduled job")
    fun pauseJob(
        @Parameter(description = "Job ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<Void> = try {
        if (sysJobService.pauseJob(id)) ResultVo.success() else ResultVo.error("Failed to pause scheduled job")
    } catch (e: Exception) {
        log.error("Failed to pause scheduled job", e)
        ResultVo.error(e.message ?: "Failed to pause scheduled job")
    }

    @PostMapping("/run/{id}")
    @Operation(summary = "Run scheduled job immediately", description = "Execute specified scheduled job immediately once")
    fun runJobOnce(
        @Parameter(description = "Job ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<Void> = try {
        if (sysJobService.runJobOnce(id)) ResultVo.success() else ResultVo.error("Failed to execute scheduled job")
    } catch (e: Exception) {
        log.error("Failed to run scheduled job immediately", e)
        ResultVo.error(e.message ?: "Failed to execute scheduled job")
    }

    @GetMapping("/logs")
    @Operation(summary = "Get scheduled job log list with pagination", description = "Paginated query for scheduled job execution logs")
    fun pageSysJobLog(
        @Parameter(description = "Page number", example = "1") @RequestParam(
            name = "pageNum",
            defaultValue = "1",
        ) pageNum: Int?,
        @Parameter(description = "Page size", example = "10") @RequestParam(
            name = "pageSize",
            defaultValue = "10",
        ) pageSize: Int?,
        @Parameter(description = "Job ID") @RequestParam(name = "jobId", required = false) jobId: Long?,
        @Parameter(description = "Job name") @RequestParam(name = "jobName", required = false) jobName: String?,
        @Parameter(description = "Execution status (0-failed, 1-success)") @RequestParam(
            name = "status",
            required = false,
        ) status: Int?,
        @Parameter(description = "Start time") @RequestParam(
            name = "startTime",
            required = false,
        ) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") startTime: LocalDateTime?,
        @Parameter(description = "End time") @RequestParam(
            name = "endTime",
            required = false,
        ) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") endTime: LocalDateTime?,
    ): ResultVo<Page<SysJobLogResponse>> = try {
        val page = sysJobLogService.getJobLogPage(
            jobId,
            jobName,
            status,
            startTime,
            endTime,
            pageNum ?: 1,
            pageSize ?: 10,
        )
        ResultVo.success(page.mapRecords { SysJobLogResponse.fromEntity(it) })
    } catch (e: Exception) {
        log.error("Failed to get scheduled job log list", e)
        ResultVo.error(e.message ?: "Failed to get scheduled job log list")
    }
}

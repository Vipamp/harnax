package com.vipamp.vipclaw.admin.controller

import com.vipamp.vipclaw.common.page.Page
import com.vipamp.vipclaw.admin.dto.ResultVo
import com.vipamp.vipclaw.admin.dto.SysJobCreateRequest
import com.vipamp.vipclaw.admin.dto.SysJobLogResponse
import com.vipamp.vipclaw.admin.dto.SysJobResponse
import com.vipamp.vipclaw.admin.dto.SysJobUpdateRequest
import com.vipamp.vipclaw.admin.service.SysJobLogService
import com.vipamp.vipclaw.admin.service.SysJobService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime

/**
 * 定时任务管理控制器
 *
 * @author vipamp
 * @since 2026-03-16
 */
@RestController
@RequestMapping("/admin/jobs")
@Tag(name = "定时任务管理", description = "定时任务相关接口")
class SysJobController(
    private val sysJobService: SysJobService,
    private val sysJobLogService: SysJobLogService
) {

    private val log = LoggerFactory.getLogger(SysJobController::class.java)

    @GetMapping("/page")
    @Operation(summary = "分页获取定时任务列表", description = "分页查询定时任务信息")
    fun getJobPage(
        @Parameter(description = "页码", example = "1") @RequestParam(name = "pageNum", defaultValue = "1") pageNum: Int?,
        @Parameter(description = "每页大小", example = "10") @RequestParam(name = "pageSize", defaultValue = "10") pageSize: Int?,
        @Parameter(description = "模糊查询字段（任务名称）") @RequestParam(name = "keyword", required = false) keyword: String?,
        @Parameter(description = "状态筛选字段（0-暂停，1-运行）") @RequestParam(name = "jobStatus", required = false) jobStatus: Int?
    ): ResultVo<Page<SysJobResponse>> {
        return try {
            val page = sysJobService.getJobPage(keyword, jobStatus, pageNum ?: 1, pageSize ?: 10)
            val responsePage = convertToResponsePage(page)
            ResultVo.success(responsePage)
        } catch (e: Exception) {
            log.error("获取定时任务列表失败", e)
            ResultVo.error(e.message ?: "获取定时任务列表失败")
        }
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取定时任务详情", description = "根据任务 ID 获取任务信息")
    fun getJobById(
        @Parameter(description = "任务 ID") @PathVariable(name = "id") id: Long
    ): ResultVo<SysJobResponse> {
        return try {
            val job = sysJobService.getJobById(id)
            ResultVo.success(SysJobResponse.fromEntity(job))
        } catch (e: Exception) {
            log.error("获取定时任务详情失败", e)
            ResultVo.error(e.message ?: "获取定时任务详情失败")
        }
    }

    @PostMapping
    @Operation(summary = "创建定时任务", description = "新增定时任务信息")
    fun createJob(
        @Valid @RequestBody request: SysJobCreateRequest
    ): ResultVo<Void> {
        return try {
            if (sysJobService.createJob(request)) ResultVo.success() else ResultVo.error("创建定时任务失败")
        } catch (e: Exception) {
            log.error("创建定时任务失败", e)
            ResultVo.error(e.message ?: "创建定时任务失败")
        }
    }

    @PutMapping("/update/{jobId}")
    @Operation(summary = "更新定时任务", description = "根据任务 ID 更新任务信息")
    fun updateJob(
        @Parameter(description = "任务 ID") @PathVariable(name = "jobId") jobId: Long,
        @Valid @RequestBody request: SysJobUpdateRequest
    ): ResultVo<Void> {
        return try {
            if (sysJobService.updateJob(jobId, request)) ResultVo.success() else ResultVo.error("更新定时任务失败")
        } catch (e: Exception) {
            log.error("更新定时任务失败", e)
            ResultVo.error(e.message ?: "更新定时任务失败")
        }
    }

    @DeleteMapping("/{jobId}")
    @Operation(summary = "删除定时任务", description = "根据任务 ID 删除任务")
    fun deleteJob(
        @Parameter(description = "任务 ID") @PathVariable(name = "jobId") jobId: Long
    ): ResultVo<Void> {
        return try {
            if (sysJobService.deleteJob(jobId)) ResultVo.success() else ResultVo.error("删除定时任务失败")
        } catch (e: Exception) {
            log.error("删除定时任务失败", e)
            ResultVo.error(e.message ?: "删除定时任务失败")
        }
    }

    @PostMapping("/start/{jobId}")
    @Operation(summary = "启动定时任务", description = "启动指定的定时任务")
    fun startJob(
        @Parameter(description = "任务 ID") @PathVariable(name = "jobId") jobId: Long
    ): ResultVo<Void> {
        return try {
            if (sysJobService.startJob(jobId)) ResultVo.success() else ResultVo.error("启动定时任务失败")
        } catch (e: Exception) {
            log.error("启动定时任务失败", e)
            ResultVo.error(e.message ?: "启动定时任务失败")
        }
    }

    @PostMapping("/pause/{jobId}")
    @Operation(summary = "暂停定时任务", description = "暂停指定的定时任务")
    fun pauseJob(
        @Parameter(description = "任务 ID") @PathVariable(name = "jobId") jobId: Long
    ): ResultVo<Void> {
        return try {
            if (sysJobService.pauseJob(jobId)) ResultVo.success() else ResultVo.error("暂停定时任务失败")
        } catch (e: Exception) {
            log.error("暂停定时任务失败", e)
            ResultVo.error(e.message ?: "暂停定时任务失败")
        }
    }

    @PostMapping("/run/{jobId}")
    @Operation(summary = "立即执行定时任务", description = "立即执行一次指定的定时任务")
    fun runJobOnce(
        @Parameter(description = "任务 ID") @PathVariable(name = "jobId") jobId: Long
    ): ResultVo<Void> {
        return try {
            if (sysJobService.runJobOnce(jobId)) ResultVo.success() else ResultVo.error("执行定时任务失败")
        } catch (e: Exception) {
            log.error("立即执行定时任务失败", e)
            ResultVo.error(e.message ?: "执行定时任务失败")
        }
    }

    @GetMapping("/logs")
    @Operation(summary = "分页获取定时任务日志列表", description = "分页查询定时任务执行日志")
    fun getJobLogPage(
        @Parameter(description = "页码", example = "1") @RequestParam(name = "pageNum", defaultValue = "1") pageNum: Int?,
        @Parameter(description = "每页大小", example = "10") @RequestParam(name = "pageSize", defaultValue = "10") pageSize: Int?,
        @Parameter(description = "任务 ID") @RequestParam(name = "jobId", required = false) jobId: Long?,
        @Parameter(description = "任务名称") @RequestParam(name = "jobName", required = false) jobName: String?,
        @Parameter(description = "执行状态（0-失败，1-成功）") @RequestParam(name = "status", required = false) status: Int?,
        @Parameter(description = "开始时间") @RequestParam(name = "startTime", required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") startTime: LocalDateTime?,
        @Parameter(description = "结束时间") @RequestParam(name = "endTime", required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") endTime: LocalDateTime?
    ): ResultVo<Page<SysJobLogResponse>> {
        return try {
            val page = sysJobLogService.getJobLogPage(jobId, jobName, status, startTime, endTime, pageNum ?: 1, pageSize ?: 10)
            val responsePage = convertToLogResponsePage(page)
            ResultVo.success(responsePage)
        } catch (e: Exception) {
            log.error("获取定时任务日志列表失败", e)
            ResultVo.error(e.message ?: "获取定时任务日志列表失败")
        }
    }

    /**
     * 分页结果转换
     */
    private fun convertToResponsePage(page: Page<com.vipamp.vipclaw.admin.entity.SysJob>): Page<SysJobResponse> {
        val responsePage = Page<SysJobResponse>(page.current, page.size)
        responsePage.total = page.total
        responsePage.size = page.size
        responsePage.current = page.current
        responsePage.pages = page.pages
        responsePage.records = page.records.map { SysJobResponse.fromEntity(it) }
        return responsePage
    }

    /**
     * 日志分页结果转换
     */
    private fun convertToLogResponsePage(page: Page<com.vipamp.vipclaw.admin.entity.SysJobLog>): Page<SysJobLogResponse> {
        val responsePage = Page<SysJobLogResponse>(page.current, page.size)
        responsePage.total = page.total
        responsePage.size = page.size
        responsePage.current = page.current
        responsePage.pages = page.pages
        responsePage.records = page.records.map { SysJobLogResponse.fromEntity(it) }
        return responsePage
    }
}

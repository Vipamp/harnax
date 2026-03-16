package com.vipamp.vipclaw.admin.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vipamp.vipclaw.admin.dto.SysJobCreateRequest;
import com.vipamp.vipclaw.admin.dto.SysJobLogResponse;
import com.vipamp.vipclaw.admin.dto.SysJobResponse;
import com.vipamp.vipclaw.admin.dto.SysJobUpdateRequest;
import com.vipamp.vipclaw.admin.entity.SysJob;
import com.vipamp.vipclaw.admin.entity.SysJobLog;
import com.vipamp.vipclaw.admin.service.SysJobLogService;
import com.vipamp.vipclaw.admin.service.SysJobService;
import com.vipamp.vipclaw.admin.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * 定时任务管理控制器
 *
 * @author vipamp
 * @since 2026-03-16
 */
@Slf4j
@RestController
@RequestMapping("/jobs")
@RequiredArgsConstructor
@Tag(name = "定时任务管理", description = "定时任务相关接口")
public class SysJobController {

    private final SysJobService sysJobService;
    private final SysJobLogService sysJobLogService;

    @GetMapping("/list")
    @Operation(summary = "分页获取定时任务列表", description = "分页查询定时任务信息")
    public Result<Page<SysJobResponse>> getJobPage(
            @Parameter(description = "页码", example = "1") @RequestParam(name = "pageNum", defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页大小", example = "10") @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
            @Parameter(description = "模糊查询字段（任务名称）") @RequestParam(name = "keyword", required = false) String keyword,
            @Parameter(description = "状态筛选字段（0-暂停，1-运行）") @RequestParam(name = "jobStatus", required = false) Integer jobStatus) {
        try {
            Page<SysJob> page = sysJobService.getJobPage(keyword, jobStatus, pageNum, pageSize);
            Page<SysJobResponse> responsePage = convertToResponsePage(page);
            return Result.success(responsePage);
        } catch (Exception e) {
            log.error("获取定时任务列表失败", e);
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取定时任务详情", description = "根据任务 ID 获取任务信息")
    public Result<SysJobResponse> getJobById(
            @Parameter(description = "任务 ID") @PathVariable Long id) {
        try {
            SysJob job = sysJobService.getJobById(id);
            return Result.success(SysJobResponse.fromEntity(job));
        } catch (Exception e) {
            log.error("获取定时任务详情失败", e);
            return Result.error(e.getMessage());
        }
    }

    @PostMapping
    @Operation(summary = "创建定时任务", description = "新增定时任务信息")
    public Result<Void> createJob(
            @Valid @RequestBody SysJobCreateRequest request) {
        try {
            return sysJobService.createJob(request) ? Result.success() : Result.error("创建定时任务失败");
        } catch (Exception e) {
            log.error("创建定时任务失败", e);
            return Result.error(e.getMessage());
        }
    }

    @PutMapping("/update/{jobId}")
    @Operation(summary = "更新定时任务", description = "根据任务 ID 更新任务信息")
    public Result<Void> updateJob(
            @Parameter(description = "任务 ID") @PathVariable Long jobId,
            @Valid @RequestBody SysJobUpdateRequest request) {
        try {
            request.setId(jobId);
            return sysJobService.updateJob(jobId, request) ? Result.success() : Result.error("更新定时任务失败");
        } catch (Exception e) {
            log.error("更新定时任务失败", e);
            return Result.error(e.getMessage());
        }
    }

    @DeleteMapping("/{jobId}")
    @Operation(summary = "删除定时任务", description = "根据任务 ID 删除任务")
    public Result<Void> deleteJob(
            @Parameter(description = "任务 ID") @PathVariable Long jobId) {
        try {
            return sysJobService.deleteJob(jobId) ? Result.success() : Result.error("删除定时任务失败");
        } catch (Exception e) {
            log.error("删除定时任务失败", e);
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/start/{jobId}")
    @Operation(summary = "启动定时任务", description = "启动指定的定时任务")
    public Result<Void> startJob(
            @Parameter(description = "任务 ID") @PathVariable Long jobId) {
        try {
            return sysJobService.startJob(jobId) ? Result.success() : Result.error("启动定时任务失败");
        } catch (Exception e) {
            log.error("启动定时任务失败", e);
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/pause/{jobId}")
    @Operation(summary = "暂停定时任务", description = "暂停指定的定时任务")
    public Result<Void> pauseJob(
            @Parameter(description = "任务 ID") @PathVariable Long jobId) {
        try {
            return sysJobService.pauseJob(jobId) ? Result.success() : Result.error("暂停定时任务失败");
        } catch (Exception e) {
            log.error("暂停定时任务失败", e);
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/run/{jobId}")
    @Operation(summary = "立即执行定时任务", description = "立即执行一次指定的定时任务")
    public Result<Void> runJobOnce(
            @Parameter(description = "任务 ID") @PathVariable Long jobId) {
        try {
            return sysJobService.runJobOnce(jobId) ? Result.success() : Result.error("执行定时任务失败");
        } catch (Exception e) {
            log.error("立即执行定时任务失败", e);
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/logs")
    @Operation(summary = "分页获取定时任务日志列表", description = "分页查询定时任务执行日志")
    public Result<Page<SysJobLogResponse>> getJobLogPage(
            @Parameter(description = "页码", example = "1") @RequestParam(name = "pageNum", defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页大小", example = "10") @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
            @Parameter(description = "任务ID") @RequestParam(name = "jobId", required = false) Long jobId,
            @Parameter(description = "任务名称") @RequestParam(name = "jobName", required = false) String jobName,
            @Parameter(description = "执行状态（0-失败，1-成功）") @RequestParam(name = "status", required = false) Integer status,
            @Parameter(description = "开始时间") @RequestParam(name = "startTime", required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(name = "endTime", required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
        try {
            Page<SysJobLog> page = sysJobLogService.getJobLogPage(jobId, jobName, status, startTime, endTime, pageNum, pageSize);
            Page<SysJobLogResponse> responsePage = convertToLogResponsePage(page);
            return Result.success(responsePage);
        } catch (Exception e) {
            log.error("获取定时任务日志列表失败", e);
            return Result.error(e.getMessage());
        }
    }

    @DeleteMapping("/logs/clean")
    @Operation(summary = "清理定时任务日志", description = "清理指定天数前的日志")
    public Result<Void> cleanLogs(
            @Parameter(description = "天数", example = "30") @RequestParam(name = "days", defaultValue = "30") Integer days) {
        try {
            int count = sysJobLogService.cleanLogs(days);
            return Result.success();
        } catch (Exception e) {
            log.error("清理定时任务日志失败", e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 分页结果转换
     */
    private Page<SysJobResponse> convertToResponsePage(Page<SysJob> page) {
        Page<SysJobResponse> responsePage = new Page<>(page.getCurrent(), page.getSize());
        responsePage.setTotal(page.getTotal());
        responsePage.setSize(page.getSize());
        responsePage.setCurrent(page.getCurrent());
        responsePage.setPages(page.getPages());
        responsePage.setRecords(page.getRecords().stream()
                .map(SysJobResponse::fromEntity)
                .toList());
        return responsePage;
    }

    /**
     * 日志分页结果转换
     */
    private Page<SysJobLogResponse> convertToLogResponsePage(Page<SysJobLog> page) {
        Page<SysJobLogResponse> responsePage = new Page<>(page.getCurrent(), page.getSize());
        responsePage.setTotal(page.getTotal());
        responsePage.setSize(page.getSize());
        responsePage.setCurrent(page.getCurrent());
        responsePage.setPages(page.getPages());
        responsePage.setRecords(page.getRecords().stream()
                .map(SysJobLogResponse::fromEntity)
                .toList());
        return responsePage;
    }
}

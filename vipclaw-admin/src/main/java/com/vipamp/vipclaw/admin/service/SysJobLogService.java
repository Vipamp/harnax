package com.vipamp.vipclaw.admin.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.vipamp.vipclaw.admin.entity.SysJobLog;
import jakarta.annotation.Nullable;

import java.time.LocalDateTime;

/**
 * 定时任务日志服务接口
 *
 * @author vipamp
 * @since 2026-03-16
 */
public interface SysJobLogService extends IService<SysJobLog> {

    /**
     * 分页查询定时任务日志列表
     *
     * @param jobId     任务ID
     * @param jobName   任务名称
     * @param status    执行状态
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @param current   当前页码
     * @param size      每页大小
     * @return 分页结果
     */
    Page<SysJobLog> getJobLogPage(@Nullable Long jobId, @Nullable String jobName, 
                                   @Nullable Integer status, @Nullable LocalDateTime startTime,
                                   @Nullable LocalDateTime endTime, Integer current, Integer size);

    /**
     * 清理指定天数前的日志
     *
     * @param days 天数
     * @return 清理的记录数
     */
    int cleanLogs(int days);
}

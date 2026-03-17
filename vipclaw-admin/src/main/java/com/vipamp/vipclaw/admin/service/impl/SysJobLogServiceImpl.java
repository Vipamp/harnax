package com.vipamp.vipclaw.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.vipamp.vipclaw.admin.entity.SysJobLog;
import com.vipamp.vipclaw.admin.mapper.SysJobLogMapper;
import com.vipamp.vipclaw.admin.service.SysJobLogService;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 定时任务日志服务实现类
 *
 * @author vipamp
 * @since 2026-03-16
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysJobLogServiceImpl extends ServiceImpl<SysJobLogMapper, SysJobLog> implements SysJobLogService {

    @Override
    public Page<SysJobLog> getJobLogPage(@Nullable Long jobId,
                                         @Nullable String jobName,
                                         @Nullable Integer status,
                                         @Nullable LocalDateTime startTime,
                                         @Nullable LocalDateTime endTime,
                                         Integer current,
                                         Integer size) {
        log.info("分页查询定时任务日志列表，current: {}, size: {}, jobId: {}, jobName: {}, status: {}", 
                current, size, jobId, jobName, status);

        Page<SysJobLog> page = new Page<>(current, size);
        LambdaQueryWrapper<SysJobLog> wrapper = new LambdaQueryWrapper<>();

        // 任务ID筛选
        if (jobId != null) {
            wrapper.eq(SysJobLog::getJobId, jobId);
        }

        // 任务名称模糊查询
        if (StringUtils.hasText(jobName)) {
            wrapper.like(SysJobLog::getJobName, jobName);
        }

        // 状态筛选
        if (status != null) {
            wrapper.eq(SysJobLog::getStatus, status);
        }

        // 时间范围筛选
        if (startTime != null) {
            wrapper.ge(SysJobLog::getCreateTime, startTime);
        }
        if (endTime != null) {
            wrapper.le(SysJobLog::getCreateTime, endTime);
        }

        wrapper.orderByDesc(SysJobLog::getCreateTime);
        return this.page(page, wrapper);
    }
}

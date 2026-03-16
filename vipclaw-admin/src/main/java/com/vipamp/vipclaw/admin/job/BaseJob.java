package com.vipamp.vipclaw.admin.job;

import com.vipamp.vipclaw.admin.entity.SysJob;
import com.vipamp.vipclaw.admin.entity.SysJobLog;
import com.vipamp.vipclaw.admin.service.SysJobLogService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

/**
 * 定时任务基类
 * 所有定时任务都需要继承此类
 *
 * @author vipamp
 * @since 2026-03-16
 */
@Slf4j
public abstract class BaseJob implements Job {

    @Autowired
    private SysJobLogService jobLogService;

    /**
     * 任务执行入口
     */
    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        JobDataMap dataMap = context.getJobDetail().getJobDataMap();
        SysJob sysJob = (SysJob) dataMap.get("sysJob");
        
        if (sysJob == null) {
            log.error("任务执行失败：未获取到任务信息");
            return;
        }

        // 创建日志记录
        SysJobLog jobLog = new SysJobLog();
        jobLog.setJobId(sysJob.getId());
        jobLog.setJobName(sysJob.getJobName());
        jobLog.setJobGroup(sysJob.getJobGroup());
        jobLog.setInvokeTarget(sysJob.getJobClass());
        jobLog.setStartTime(LocalDateTime.now());
        
        log.info("定时任务开始执行 - 任务名称: {}, 任务组: {}", sysJob.getJobName(), sysJob.getJobGroup());
        
        try {
            // 执行具体任务逻辑
            doExecute(context);
            
            // 记录成功日志
            jobLog.setStatus(1);
            jobLog.setJobMessage("任务执行成功");
            log.info("定时任务执行成功 - 任务名称: {}", sysJob.getJobName());
        } catch (Exception e) {
            // 记录失败日志
            jobLog.setStatus(0);
            jobLog.setJobMessage("任务执行失败: " + e.getMessage());
            jobLog.setExceptionInfo(getExceptionInfo(e));
            log.error("定时任务执行失败 - 任务名称: {}, 错误: {}", sysJob.getJobName(), e.getMessage(), e);
            throw new JobExecutionException(e);
        } finally {
            jobLog.setEndTime(LocalDateTime.now());
            jobLogService.save(jobLog);
        }
    }

    /**
     * 具体任务执行逻辑，由子类实现
     *
     * @param context 任务执行上下文
     * @throws Exception 执行异常
     */
    protected abstract void doExecute(JobExecutionContext context) throws Exception;

    /**
     * 获取异常信息
     *
     * @param e 异常对象
     * @return 异常信息字符串
     */
    private String getExceptionInfo(Exception e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.toString()).append("\n");
        for (StackTraceElement element : e.getStackTrace()) {
            sb.append("\tat ").append(element.toString()).append("\n");
        }
        // 限制长度，防止存储溢出
        String result = sb.toString();
        return result.length() > 4000 ? result.substring(0, 4000) + "..." : result;
    }
}

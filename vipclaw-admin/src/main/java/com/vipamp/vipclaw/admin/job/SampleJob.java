package com.vipamp.vipclaw.admin.job;

import lombok.extern.slf4j.Slf4j;
import org.quartz.JobExecutionContext;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 示例定时任务
 * 用于演示定时任务的基本用法
 *
 * @author vipamp
 * @since 2026-03-16
 */
@Slf4j
@Component
public class SampleJob extends BaseJob {

    @Override
    protected void doExecute(JobExecutionContext context) throws Exception {
        String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        log.info("【示例任务】正在执行，当前时间: {}", currentTime);
        
        // 这里可以编写具体的业务逻辑
        // 例如：数据同步、报表生成、定时清理等
        
        // 模拟任务执行时间
        Thread.sleep(1000);
        
        log.info("【示例任务】执行完成");
    }
}

package com.vipamp.vipclaw.agent.service.adaptor;

import com.vipamp.vipclaw.agent.adaptor.ProcessLog;
import com.vipamp.vipclaw.agent.adaptor.ProcessLogAdaptor;
import com.vipamp.vipclaw.common.entity.ProcessLogEntity;
import com.vipamp.vipclaw.common.mapper.ProcessLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * ProcessLogAdaptor 实现类
 * 将处理日志信息保存到数据库
 *
 * @Author: heqingsong
 * @Date: 2026/4/12
 * @Project: vipclaw
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessLogAdaptorImpl implements ProcessLogAdaptor {

    private final ProcessLogMapper processLogMapper;

    @Override
    public void emitLog(@NotNull ProcessLog processLog) {
        try {
            // 将 ProcessLog 转换为 ProcessLog 实体
            ProcessLogEntity entity = convertToEntity(processLog);
            
            // 保存到数据库
            int result = processLogMapper.insert(entity);
            
            if (result > 0) {
                log.debug("Process log saved successfully: agentId={}, agentName={}, sessionId={}, type={}",
                        processLog.getAgentId(), processLog.getAgentName(), 
                        processLog.getSessionId(), processLog.getType());
            } else {
                log.warn("Failed to save process log: agentId={}, agentName={}", 
                        processLog.getAgentId(), processLog.getAgentName());
            }
        } catch (Exception e) {
            log.error("Error saving process log: agentId={}, agentName={}, sessionId={}", 
                    processLog.getAgentId(), processLog.getAgentName(), processLog.getSessionId(), e);
            // 不抛出异常，避免影响主流程
        }
    }

    /**
     * 将 ProcessLog 转换为 ProcessLog 实体
     */
    private ProcessLogEntity convertToEntity(ProcessLog processLog) {
        ProcessLogEntity entity = new ProcessLogEntity();
        entity.setAgentId(processLog.getAgentId());
        entity.setAgentName(processLog.getAgentName());
        entity.setSessionId(processLog.getSessionId());
        entity.setMessage(processLog.getMessage());
        entity.setLogType(processLog.getType().name());
        
        // 如果有异常，记录堆栈信息
        if (processLog.getThrowable() != null) {
            entity.setStackTrace(getStackTrace(processLog.getThrowable()));
        }
        
        // 将时间戳转换为 LocalDateTime
        LocalDateTime dateTime = Instant.ofEpochMilli(processLog.getTimestamp())
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
        entity.setTs(dateTime);
        
        return entity;
    }

    /**
     * 获取异常堆栈信息
     */
    private String getStackTrace(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        try {
            throwable.printStackTrace(pw);
            return sw.toString();
        } finally {
            pw.close();
        }
    }
}

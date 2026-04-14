package com.vipamp.vipclaw.agent.service.adaptor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vipamp.vipclaw.agent.adaptor.ToolCallInfo;
import com.vipamp.vipclaw.agent.adaptor.ToolCallLogAdaptor;
import com.vipamp.vipclaw.common.entity.ToolCallLogEntity;
import com.vipamp.vipclaw.common.mapper.ToolCallLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * ToolCallLogAdaptor 实现类
 * 将工具调用日志信息保存到数据库
 *
 * @Author: heqingsong
 * @Date: 2026/4/14
 * @Project: vipclaw
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolCallLogAdaptorImpl implements ToolCallLogAdaptor {

    private final ToolCallLogMapper toolCallLogMapper;
    private final ObjectMapper objectMapper;

    @Override
    public void emit(@NotNull ToolCallInfo toolCallInfo) {
        try {
            // 将 ToolCallInfo 转换为 ToolCallLogEntity 实体
            ToolCallLogEntity entity = convertToEntity(toolCallInfo);
            
            // 保存到数据库
            int result = toolCallLogMapper.insert(entity);
            
            if (result > 0) {
                log.debug("Tool call log saved successfully: agentId={}, sessionId={}, toolName={}, duration={}ms",
                        toolCallInfo.getAgentId(), toolCallInfo.getSessionId(), 
                        toolCallInfo.getToolName(), toolCallInfo.getDuration());
            } else {
                log.warn("Failed to save tool call log: agentId={}, toolName={}", 
                        toolCallInfo.getAgentId(), toolCallInfo.getToolName());
            }
        } catch (Exception e) {
            log.error("Error saving tool call log: agentId={}, sessionId={}, toolName={}", 
                    toolCallInfo.getAgentId(), toolCallInfo.getSessionId(), toolCallInfo.getToolName(), e);
            // 不抛出异常，避免影响主流程
        }
    }

    /**
     * 将 ToolCallInfo 转换为 ToolCallLogEntity 实体
     */
    private ToolCallLogEntity convertToEntity(ToolCallInfo toolCallInfo) {
        ToolCallLogEntity entity = new ToolCallLogEntity();
        entity.setAgentId(toolCallInfo.getAgentId());
        entity.setSessionId(toolCallInfo.getSessionId());
        entity.setToolName(toolCallInfo.getToolName());
        
        // 将 args Map 转换为 JSON 字符串
        try {
            entity.setArgs(objectMapper.writeValueAsString(toolCallInfo.getArgs()));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize tool call args to JSON: toolName={}", 
                    toolCallInfo.getToolName(), e);
            entity.setArgs("{}");
        }
        
        entity.setResult(toolCallInfo.getResult());
        
        // 设置是否成功标志
        entity.setSuccess(toolCallInfo.getSuccess() ? 1 : 0);
        
        // 将时间戳转换为 LocalDateTime
        LocalDateTime startDateTime = Instant.ofEpochMilli(toolCallInfo.getStartTime())
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
        entity.setStartTime(startDateTime);
        
        LocalDateTime endDateTime = Instant.ofEpochMilli(toolCallInfo.getEndTime())
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
        entity.setEndTime(endDateTime);
        
        entity.setDuration(toolCallInfo.getDuration());
        
        // 设置记录时间戳（使用结束时间）
        entity.setTs(endDateTime);
        
        return entity;
    }
}

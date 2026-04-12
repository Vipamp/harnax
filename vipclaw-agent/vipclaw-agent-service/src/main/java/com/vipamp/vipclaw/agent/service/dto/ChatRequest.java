package com.vipamp.vipclaw.agent.service.dto;

/**
 * @Author: heqingsong
 * @Date: 2026/4/12
 * @Description: ChatRequest
 * @Project: vipclaw
 */
public record ChatRequest(String sessionId,
                          String message,
                          Boolean enableThink,
                          Boolean enableSearch) {
}

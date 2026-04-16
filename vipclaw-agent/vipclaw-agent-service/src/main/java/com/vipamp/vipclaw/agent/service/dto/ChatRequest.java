package com.vipamp.vipclaw.agent.service.dto;

import java.util.List;

/**
 * @Author: heqingsong
 * @Date: 2026/4/12
 * @Description: ChatRequest
 * @Project: vipclaw
 */
public record ChatRequest(String sessionId,
                          String message,
                          List<String> imageUrl,
                          Boolean enableThink,
                          Boolean enableSearch,
                          Boolean enablePlan) {
}

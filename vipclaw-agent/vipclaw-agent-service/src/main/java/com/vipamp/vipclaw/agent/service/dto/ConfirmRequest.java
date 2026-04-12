package com.vipamp.vipclaw.agent.service.dto;

import java.util.List;

/**
 * @Author: heqingsong
 * @Date: 2026/4/12
 * @Description: ChatRequest
 * @Project: vipclaw
 */
public record ConfirmRequest(String sessionId,
                             boolean isConfirmed,
                             List<ToolInfo> toolInfoList,
                             Boolean enableThink,
                             Boolean enableSearch) {

    public record ToolInfo(String toolId, String toolName) {

    }
}

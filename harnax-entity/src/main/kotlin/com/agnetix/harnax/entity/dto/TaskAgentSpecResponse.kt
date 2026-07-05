package com.agnetix.harnax.entity.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Lightweight AgentSpec DTO returned by admin internal API for task sessions.
 *
 * When agent-service encounters a `task-{taskId}-{uuid}` sessionId,
 * it calls admin's internal endpoint to fetch this DTO, which contains
 * all the information needed to build an AgentSpec without a DB session record.
 */
@Schema(description = "Task Agent Spec response for agent-service")
data class TaskAgentSpecResponse(
    @Schema(description = "Agent ID")
    val agentId: Long,

    @Schema(description = "Agent name")
    val agentName: String,

    @Schema(description = "Agent description")
    val description: String,

    @Schema(description = "System prompt (Markdown supported)")
    val systemPrompt: String,

    @Schema(description = "Chat model ID")
    val modelId: Long,

    @Schema(description = "MCP service list (JSON format)")
    val mcpList: String,

    @Schema(description = "Skill list (JSON format or comma-separated IDs)")
    val skillList: String,

    @Schema(description = "Deep thinking enabled (0:no, 1:yes)")
    val enableThink: Int = 0,

    @Schema(description = "Internet search enabled (0:no, 1:yes)")
    val enableSearch: Int = 0,

    @Schema(description = "Planning enabled (0:no, 1:yes)")
    val enablePlan: Int = 0,
)

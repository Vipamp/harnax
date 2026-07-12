package com.agnetix.harnax.entity.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Unified AgentSpec DTO returned by admin internal API for all session types.
 *
 * When agent-service needs to build an AgentSpec, it calls admin's internal endpoint
 * with the sessionId. Admin resolves the agent configuration based on sessionId prefix:
 * - `web-*` / `mp-*`: look up session table → agent
 * - `chn-*`: look up channel table → agent
 * - `task-{taskId}-*`: look up agent_task table → agent
 *
 * This centralizes all agent spec resolution in admin, keeping agent-service
 * free from direct DB queries for session/channel/task configuration.
 *
 * Tool/MCP/Skill bindings are read from normalized binding tables
 * (agent_tool_binding, agent_mcp_binding, agent_skill_binding).
 * Env bindings are resolved at admin side: envVarId → latest value, fallback to snapshot.
 */
@Schema(description = "Unified Agent Spec response for agent-service")
data class AgentSpecInfoResponse(
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

    @Schema(description = "Tool bindings (JSON: [{id, enableSkip, needConfirm, envBindings}])")
    val toolList: String = "[]",

    @Schema(description = "MCP bindings (JSON: [{id, enableSkip, envBindings}])")
    val mcpList: String = "[]",

    @Schema(description = "Skill IDs (comma-separated)")
    val skillList: String = "",

    @Schema(description = "Deep thinking enabled (0:no, 1:yes)")
    val enableThink: Int = 0,

    @Schema(description = "Internet search enabled (0:no, 1:yes)")
    val enableSearch: Int = 0,

    @Schema(description = "Planning enabled (0:no, 1:yes)")
    val enablePlan: Int = 0,

    @Schema(description = "Permission mode (DEFAULT/ACCEPT_EDITS/EXPLORE/BYPASS/DONT_ASK)")
    val permissionMode: String = "DEFAULT",

    @Schema(description = "Model supports internet search (0:no, 1:yes)")
    val modelSupportInternet: Int = 0,

    @Schema(description = "Model supports reasoning/thinking (0:no, 1:yes)")
    val modelSupportReasoning: Int = 0,
)

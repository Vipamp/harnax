package com.agnetix.harnax.client.dto

/**
 * Confirm request - carries tool confirmation decision from the client.
 *
 * Supports two modes:
 * 1. Bulk: use [isConfirmed] to approve/reject all tools at once
 * 2. Per-tool: use [toolResults] for individual decisions per tool
 *
 * @property sessionId   Session identifier
 * @property isConfirmed Whether the user confirmed all tool executions (bulk mode)
 * @property toolInfoList List of tools pending confirmation (legacy)
 * @property toolResults  Per-tool confirmation decisions (preferred over bulk mode)
 */
data class ConfirmRequest(
    val sessionId: String,
    val isConfirmed: Boolean,
    val toolInfoList: List<ToolInfo> = emptyList(),
    val toolResults: List<ToolConfirmResult> = emptyList(),
    /**
     * Discriminator field matching the Router's polymorphic type (Jackson).
     * Must be "CONFIRM" for confirm requests.
     */
    val type: String = "CONFIRM",
)

/**
 * Per-tool confirmation decision.
 */
data class ToolConfirmResult(
    val toolId: String,
    val toolName: String,
    val confirmed: Boolean,
    val alwaysAllow: Boolean = false,
)

/**
 * Tool information for confirmation requests.
 */
data class ToolInfo(
    val toolId: String? = null,
    val toolName: String? = null,
)

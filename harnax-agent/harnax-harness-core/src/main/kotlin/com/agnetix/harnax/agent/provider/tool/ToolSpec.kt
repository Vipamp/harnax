package com.agnetix.harnax.agent.provider.tool

data class ToolSpec(
    val toolId: Long,
    val toolName: String = "",
    val skipIfMissing: Boolean = true,
    val needConfirm: Boolean = false,
)

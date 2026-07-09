package com.agnetix.harnax.tools.sdk

data class ToolSpec(
    val toolId: Long,
    val toolName: String = "",
    val skipIfMissing: Boolean = true,
    val needConfirm: Boolean = false,
)

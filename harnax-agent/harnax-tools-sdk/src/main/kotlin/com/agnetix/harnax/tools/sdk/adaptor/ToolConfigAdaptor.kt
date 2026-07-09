package com.agnetix.harnax.tools.sdk.adaptor

import com.agnetix.harnax.entity.AgentTool

interface ToolConfigAdaptor {
    fun getToolConfig(toolId: Long): AgentTool?
}

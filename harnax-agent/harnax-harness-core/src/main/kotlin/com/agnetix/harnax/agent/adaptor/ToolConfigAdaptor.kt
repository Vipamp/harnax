package com.agnetix.harnax.agent.adaptor

import com.agnetix.harnax.entity.AgentTool

interface ToolConfigAdaptor {
    fun getToolConfig(toolId: Long): AgentTool?
}

package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.entity.AgentTool
import com.agnetix.harnax.mapper.AgentToolMapper
import com.agnetix.harnax.tools.sdk.adaptor.ToolConfigAdaptor
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class ToolConfigAdaptorImpl(
    private val agentToolMapper: AgentToolMapper,
) : ToolConfigAdaptor {

    private val log = LoggerFactory.getLogger(ToolConfigAdaptorImpl::class.java)

    override fun getToolConfig(toolId: Long): AgentTool? {
        if (toolId <= 0) {
            log.warn("Invalid toolId: $toolId")
            return null
        }
        val agentTool = agentToolMapper.selectById(toolId)
        if (agentTool == null) {
            log.warn("AgentTool not found: $toolId")
            return null
        }
        return agentTool
    }
}

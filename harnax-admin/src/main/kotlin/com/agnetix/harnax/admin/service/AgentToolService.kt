package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.AgentToolResponse
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.entity.AgentTool

/**
 * Read-only: every agent_tool row is written by [com.agnetix.harnax.admin.registrar.BuiltinToolAutoRegistrar],
 * so this service exposes no create, update, enable or delete path.
 */
interface AgentToolService {

    fun page(keyword: String?, status: Int?, pageNum: Int, pageSize: Int): Page<AgentTool>

    fun getAgentTool(id: Long): AgentTool?

    fun convertToResponse(agentTool: AgentTool): AgentToolResponse

    /** Tools an agent may bind: enabled and non-mandatory */
    fun getAvailableTools(): List<AgentTool>

    fun getBuiltinTools(): List<AgentTool>

    fun getRequiredEnvParamKeys(id: Long): List<String>
}

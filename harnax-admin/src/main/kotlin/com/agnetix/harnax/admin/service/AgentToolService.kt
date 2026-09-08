package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.AgentToolCreateRequest
import com.agnetix.harnax.admin.dto.AgentToolResponse
import com.agnetix.harnax.admin.dto.AgentToolUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.entity.AgentTool

interface AgentToolService {

    fun page(keyword: String?, status: Int?, type: String?, pageNum: Int, pageSize: Int): Page<AgentTool>

    fun getAgentTool(id: Long): AgentTool?

    /**
     * The four write methods below serve CUSTOM/HTTP tools only; each rejects `type = 'BUILTIN'`
     * because [com.agnetix.harnax.admin.registrar.BuiltinToolAutoRegistrar] is the sole owner of
     * builtin rows (insert, update and hard delete all happen at startup).
     */
    fun createAgentTool(request: AgentToolCreateRequest): Boolean

    fun updateAgentTool(id: Long, request: AgentToolUpdateRequest): Boolean

    fun toggleAgentToolStatus(id: Long, status: Int): Boolean

    fun deleteAgentTool(id: Long): Boolean

    fun convertToResponse(agentTool: AgentTool): AgentToolResponse

    fun getAvailableTools(): List<AgentTool>

    fun getBuiltinTools(): List<AgentTool>

    fun getAvailableToolsByType(type: String?): List<AgentTool>

    fun getRequiredEnvParamKeys(id: Long): List<String>
}

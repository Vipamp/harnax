package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.AgentCreateRequest
import com.agnetix.harnax.admin.dto.AgentResponse
import com.agnetix.harnax.admin.dto.AgentUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.entity.Agent

/**
 * Agent service interface
 */
interface AgentService {

    /**
     * Query agent list with pagination
     *
     * @param name     Agent name
     * @param status   Status filter field
     * @param pageNum  Current page number
     * @param pageSize Page size
     * @return Paginated result
     */
    fun page(name: String?, status: Int?, pageNum: Int, pageSize: Int): Page<Agent>

    /**
     * Get single agent details
     *
     * @param id Agent ID
     * @return Agent entity
     */
    fun getAgent(id: Long): Agent?

    /**
     * Create agent
     *
     * @param request Agent create request object
     * @return Create result
     */
    fun createAgent(request: AgentCreateRequest): Boolean

    /**
     * Update agent
     *
     * @param id      Agent ID
     * @param request Agent update request object
     * @return Update result
     */
    fun updateAgent(id: Long, request: AgentUpdateRequest): Boolean

    /**
     * Toggle agent enable status
     *
     * @param id     Agent ID
     * @param status Enable status (0:disabled, 1:enabled)
     * @return Update result
     */
    fun toggleAgentStatus(id: Long, status: Int): Boolean

    /**
     * Delete agent
     *
     * @param id Agent ID
     * @return Delete result
     */
    fun deleteAgent(id: Long): Boolean

    /**
     * Get all active agents (enabled and not deleted)
     *
     * @return List of active agents
     */
    fun getActiveAgents(): List<Agent>

    /**
     * Convert Agent entity to response DTO (including complete skill and MCP information)
     *
     * @param agent Agent entity
     * @return Response DTO
     */
    fun convertToResponse(agent: Agent): AgentResponse
}

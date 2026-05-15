package com.vipamp.vipclaw.admin.service

import com.vipamp.vipclaw.admin.dto.McpServerCreateRequest
import com.vipamp.vipclaw.admin.dto.McpServerResponse
import com.vipamp.vipclaw.admin.dto.McpServerUpdateRequest
import com.vipamp.vipclaw.admin.dto.Page
import com.vipamp.vipclaw.admin.entity.McpServer
import io.modelcontextprotocol.spec.McpSchema

/**
 * MCP server service interface
 */
interface McpServerService {

    /**
     * Query MCP server list with pagination
     *
     * @param keyword  Fuzzy search field (name/description)
     * @param status   Status filter field
     * @param type     Type filter field (e.g.: stdio/sse/streamablehttp)
     * @param pageNum  Current page number
     * @param pageSize Page size
     * @return Paginated result
     */
    fun page(keyword: String?, status: Int?, type: String?, pageNum: Int, pageSize: Int): Page<McpServer>

    /**
     * Get single MCP server details
     *
     * @param id MCP ID
     * @return MCP server entity
     */
    fun getMcpServer(id: Long): McpServer?

    /**
     * Create MCP server
     *
     * @param request MCP server create request object
     * @return Create result
     */
    fun createMcpServer(request: McpServerCreateRequest): Boolean

    /**
     * Update MCP server
     *
     * @param id      MCP ID
     * @param request MCP server update request object
     * @return Update result
     */
    fun updateMcpServer(id: Long, request: McpServerUpdateRequest): Boolean

    /**
     * Toggle MCP server enable status
     *
     * @param id     MCP ID
     * @param status Enable status (0:disabled, 1:enabled)
     * @return Update result
     */
    fun toggleMcpServerStatus(id: Long, status: Int): Boolean

    /**
     * Delete MCP server (logical delete)
     *
     * @param id MCP ID
     * @return Delete result
     */
    fun deleteMcpServer(id: Long): Boolean

    /**
     * MCP server connectivity test
     *
     * @param id MCP ID
     * @return Connection test result (true: success, false: failed)
     */
    fun connectivityTest(id: Long): Boolean

    /**
     * Convert MCP server entity to response object
     *
     * @param mcpServer MCP server entity
     * @return MCP server response object
     */
    fun convertToResponse(mcpServer: McpServer): McpServerResponse

    /**
     * List tools from MCP configuration
     *
     * @param mcpConfig MCP configuration object
     * @return Tool list
     */
    fun listTools(mcpId: Long): List<McpSchema.Tool>
}

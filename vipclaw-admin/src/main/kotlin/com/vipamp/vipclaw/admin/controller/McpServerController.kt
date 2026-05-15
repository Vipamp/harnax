package com.vipamp.vipclaw.admin.controller

import com.vipamp.vipclaw.admin.config.EditionUtil
import com.vipamp.vipclaw.admin.dto.*
import com.vipamp.vipclaw.admin.dto.Page
import com.vipamp.vipclaw.admin.dto.mapRecords
import com.vipamp.vipclaw.admin.service.McpServerService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

/**
 * MCP server management controller
 */
@RestController
@RequestMapping("/api/mcp")
@Tag(name = "MCP Server Management", description = "MCP server related APIs")
class McpServerController(
    private val mcpServerService: McpServerService,
    private val editionUtil: EditionUtil,
) {

    private val log = LoggerFactory.getLogger(McpServerController::class.java)

    @GetMapping("/page")
    @Operation(summary = "Get MCP server list with pagination", description = "Paginated query for MCP server information")
    fun pageMcpServer(
        @Parameter(description = "Page number", example = "1") @RequestParam(
            name = "pageNum",
            defaultValue = "1",
        ) pageNum: Int?,
        @Parameter(description = "Page size", example = "10") @RequestParam(
            name = "pageSize",
            defaultValue = "10",
        ) pageSize: Int?,
        @Parameter(description = "Keyword (name/description)") @RequestParam(
            name = "keyword",
            required = false,
        ) keyword: String?,
        @Parameter(description = "Status filter (0: disabled 1: enabled)") @RequestParam(
            name = "status",
            required = false,
        ) status: Int?,
        @Parameter(description = "Type filter (stdio/sse/streamablehttp)") @RequestParam(
            name = "type",
            required = false,
        ) type: String?,
    ): ResultVo<Page<McpServerResponse>> = try {
        val page = mcpServerService.page(keyword, status, type, pageNum ?: 1, pageSize ?: 10)
        ResultVo.success(page.mapRecords { mcpServerService.convertToResponse(it) })
    } catch (e: Exception) {
        log.error("Failed to get MCP server list", e)
        ResultVo.error(e.message ?: "Failed to get MCP server list")
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get MCP server details", description = "Get MCP server information by ID")
    fun getMcpServer(
        @Parameter(description = "MCP ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<McpServerResponse?> = try {
        val mcpServer = mcpServerService.getMcpServer(id)
        ResultVo.success(mcpServer?.let { mcpServerService.convertToResponse(it) })
    } catch (e: Exception) {
        log.error("Failed to get MCP server details", e)
        ResultVo.error(e.message ?: "Failed to get MCP server details")
    }

    @PostMapping
    @Operation(summary = "Create MCP server", description = "Add new MCP server")
    fun createMcpServer(
        @Valid @RequestBody request: McpServerCreateRequest,
    ): ResultVo<Void> {
        return try {
            // Enterprise and public editions do not support stdio mode
            if ((editionUtil.isEnterprise() || editionUtil.isPublic()) &&
                request.type == "stdio"
            ) {
                return ResultVo.error("stdio mode is not supported in current edition")
            }

            if (mcpServerService.createMcpServer(request)) ResultVo.success() else ResultVo.error("Failed to create MCP server")
        } catch (e: Exception) {
            log.error("Failed to create MCP server", e)
            ResultVo.error(e.message ?: "Failed to create MCP server")
        }
    }

    @PutMapping("/update/{id}")
    @Operation(summary = "Update MCP server", description = "Update MCP server information by ID")
    fun updateMcpServer(
        @Parameter(description = "MCP ID") @PathVariable(name = "id") id: Long,
        @Valid @RequestBody request: McpServerUpdateRequest,
    ): ResultVo<Void> = try {
        if (mcpServerService.updateMcpServer(id, request)) ResultVo.success() else ResultVo.error("Failed to update MCP server")
    } catch (e: Exception) {
        log.error("Failed to update MCP server", e)
        ResultVo.error(e.message ?: "Failed to update MCP server")
    }

    @PutMapping("/toggle/{id}")
    @Operation(summary = "Toggle MCP server status", description = "Enable or disable MCP server")
    fun toggleMcpServer(
        @Parameter(description = "MCP ID") @PathVariable(name = "id") id: Long,
        @Parameter(description = "Enable status (0: disabled 1: enabled)") @RequestParam(name = "status") status: Int,
    ): ResultVo<Void> = try {
        if (mcpServerService.toggleMcpServerStatus(id, status)) ResultVo.success() else ResultVo.error("Failed to toggle status")
    } catch (e: Exception) {
        log.error("Failed to toggle MCP server status", e)
        ResultVo.error(e.message ?: "Failed to toggle status")
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete MCP server", description = "Logical delete MCP server by ID")
    fun deleteMcpServer(
        @Parameter(description = "MCP ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<Void> = try {
        if (mcpServerService.deleteMcpServer(id)) ResultVo.success() else ResultVo.error("Failed to delete MCP server")
    } catch (e: Exception) {
        log.error("Failed to delete MCP server", e)
        ResultVo.error(e.message ?: "Failed to delete MCP server")
    }

    @PostMapping("/{id}/connectivity-test")
    @Operation(summary = "MCP server connectivity test", description = "Test if MCP server connection is normal")
    fun connectivityTest(
        @Parameter(description = "MCP ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<Boolean> = try {
        val result = mcpServerService.connectivityTest(id)
        ResultVo.success(result)
    } catch (e: Exception) {
        log.error("Failed to test MCP server connectivity", e)
        ResultVo.error(e.message ?: "Failed to test MCP server connectivity")
    }

    @GetMapping("/{id}/list_tools")
    @Operation(summary = "Get MCP tool list", description = "Get tool list provided by MCP server")
    fun listTools(
        @Parameter(description = "MCP ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<List<McpToolResponse>> = try {
        val tools = mcpServerService.listTools(id)
        val toolResponses = tools.map { tool ->
            McpToolResponse(
                name = tool.name,
                parameters = tool.inputSchema.properties?.map { (key, value) ->
                    McpToolResponse.McpToolParameter(
                        name = key,
                        type = when (value) {
                            is Map<*, *> -> (value["type"] as? String) ?: "string"
                            else -> "string"
                        },
                        description = when (value) {
                            is Map<*, *> -> (value["description"] as? String) ?: ""
                            else -> ""
                        },
                    )
                } ?: emptyList(),
            )
        }
        ResultVo.success(toolResponses)
    } catch (e: Exception) {
        log.error("Failed to get MCP tool list, mcpId: {}", id, e)
        val errorMessage = e.message ?: "Failed to get MCP tool list"
        ResultVo.error(errorMessage)
    }

    /**
     * Get mock tool list
     */
    private fun getMockTools(): List<McpToolResponse> {
        val tools = mutableListOf<McpToolResponse>()

        // Tool 1: Read file
        val readFile = McpToolResponse().apply {
            name = "read_file"
            parameters = listOf(
                createParameter("file_path", "string", "File path, e.g.: /path/to/file.txt"),
                createParameter("encoding", "string", "File encoding, default is utf-8"),
            )
        }
        tools.add(readFile)

        // Tool 2: Write file
        val writeFile = McpToolResponse().apply {
            name = "write_file"
            parameters = listOf(
                createParameter("file_path", "string", "File path, e.g.: /path/to/file.txt"),
                createParameter("content", "string", "File content to write"),
                createParameter("encoding", "string", "File encoding, default is utf-8"),
            )
        }
        tools.add(writeFile)

        // Tool 3: List directory
        val listDirectory = McpToolResponse().apply {
            name = "list_directory"
            parameters = listOf(
                createParameter("directory_path", "string", "Directory path, e.g.: /path/to/directory"),
            )
        }
        tools.add(listDirectory)

        // Tool 4: Search files
        val searchFiles = McpToolResponse().apply {
            name = "search_files"
            parameters = listOf(
                createParameter("directory_path", "string", "Directory path to search"),
                createParameter("pattern", "string", "Search pattern, supports wildcards, e.g.: *.txt"),
                createParameter("recursive", "boolean", "Whether to recursively search subdirectories, default is false"),
            )
        }
        tools.add(searchFiles)

        // Tool 5: Execute command
        val executeCommand = McpToolResponse().apply {
            name = "execute_command"
            parameters = listOf(
                createParameter("command", "string", "Command to execute, e.g.: ls -la"),
                createParameter("working_directory", "string", "Working directory, default is current directory"),
                createParameter("timeout", "integer", "Command execution timeout in seconds, default is 30"),
            )
        }
        tools.add(executeCommand)

        return tools
    }

    /**
     * Create parameter object
     */
    private fun createParameter(name: String, type: String, description: String): McpToolResponse.McpToolParameter = McpToolResponse.McpToolParameter().apply {
        this.name = name
        this.type = type
        this.description = description
    }
}

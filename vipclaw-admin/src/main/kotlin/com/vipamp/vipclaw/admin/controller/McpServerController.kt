package com.vipamp.vipclaw.admin.controller

import com.vipamp.vipclaw.admin.dto.*
import com.vipamp.vipclaw.admin.service.McpServerService
import com.vipamp.vipclaw.common.page.Page
import com.vipamp.vipclaw.common.page.mapRecords
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

/**
 * MCP 服务管理控制器
 *
 * @author vipamp
 * @since 2026-03-12
 */
@RestController
@RequestMapping("/admin/mcp")
@Tag(name = "MCP 服务管理", description = "MCP 服务相关接口")
class McpServerController(
    private val mcpServerService: McpServerService
) {

    private val log = LoggerFactory.getLogger(McpServerController::class.java)

    @GetMapping("/page")
    @Operation(summary = "分页获取 MCP 服务列表", description = "分页查询 MCP 服务信息")
    fun pageMcpServer(
        @Parameter(description = "页码", example = "1") @RequestParam(
            name = "pageNum", defaultValue = "1"
        ) pageNum: Int?, @Parameter(description = "每页大小", example = "10") @RequestParam(
            name = "pageSize", defaultValue = "10"
        ) pageSize: Int?, @Parameter(description = "关键词（名称/描述）") @RequestParam(
            name = "keyword", required = false
        ) keyword: String?, @Parameter(description = "状态筛选（0:禁用 1:启用）") @RequestParam(
            name = "status", required = false
        ) status: Int?, @Parameter(description = "类型筛选（可多选，逗号分隔）") @RequestParam(
            name = "types", required = false
        ) types: String?
    ): ResultVo<Page<McpServerResponse>> = try {
        val page = mcpServerService.page(keyword, status, types, pageNum ?: 1, pageSize ?: 10)
        ResultVo.success(page.mapRecords { mcpServerService.convertToResponse(it) })
    } catch (e: Exception) {
        log.error("获取 MCP 服务列表失败", e)
        ResultVo.error(e.message ?: "获取 MCP 服务列表失败")
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取 MCP 服务详情", description = "根据 ID 获取 MCP 服务信息")
    fun getMcpServer(
        @Parameter(description = "MCP ID") @PathVariable(name = "id") id: Long
    ): ResultVo<McpServerResponse?> = try {
        val mcpServer = mcpServerService.getMcpServer(id)
        ResultVo.success(mcpServer?.let { mcpServerService.convertToResponse(it) })
    } catch (e: Exception) {
        log.error("获取 MCP 服务详情失败", e)
        ResultVo.error(e.message ?: "获取 MCP 服务详情失败")
    }

    @PostMapping
    @Operation(summary = "创建 MCP 服务", description = "新增 MCP 服务")
    fun createMcpServer(
        @Valid @RequestBody request: McpServerCreateRequest
    ): ResultVo<Void> = try {
        if (mcpServerService.createMcpServer(request)) ResultVo.success() else ResultVo.error("创建 MCP 服务失败")
    } catch (e: Exception) {
        log.error("创建 MCP 服务失败", e)
        ResultVo.error(e.message ?: "创建 MCP 服务失败")
    }

    @PutMapping("/update/{id}")
    @Operation(summary = "更新 MCP 服务", description = "根据 ID 更新 MCP 服务信息")
    fun updateMcpServer(
        @Parameter(description = "MCP ID") @PathVariable(name = "id") id: Long,
        @Valid @RequestBody request: McpServerUpdateRequest
    ): ResultVo<Void> = try {
        if (mcpServerService.updateMcpServer(id, request)) ResultVo.success() else ResultVo.error("更新 MCP 服务失败")
    } catch (e: Exception) {
        log.error("更新 MCP 服务失败", e)
        ResultVo.error(e.message ?: "更新 MCP 服务失败")
    }

    @PutMapping("/toggle/{id}")
    @Operation(summary = "切换 MCP 服务启用状态", description = "启用或禁用 MCP 服务")
    fun toggleMcpServer(
        @Parameter(description = "MCP ID") @PathVariable(name = "id") id: Long,
        @Parameter(description = "启用状态（0:禁用 1:启用）") @RequestParam(name = "status") status: Int
    ): ResultVo<Void> = try {
        if (mcpServerService.toggleMcpServerStatus(id, status)) ResultVo.success() else ResultVo.error("切换状态失败")
    } catch (e: Exception) {
        log.error("切换 MCP 服务状态失败", e)
        ResultVo.error(e.message ?: "切换状态失败")
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除 MCP 服务", description = "根据 ID 逻辑删除 MCP 服务")
    fun deleteMcpServer(
        @Parameter(description = "MCP ID") @PathVariable(name = "id") id: Long
    ): ResultVo<Void> = try {
        if (mcpServerService.deleteMcpServer(id)) ResultVo.success() else ResultVo.error("删除 MCP 服务失败")
    } catch (e: Exception) {
        log.error("删除 MCP 服务失败", e)
        ResultVo.error(e.message ?: "删除 MCP 服务失败")
    }

    @PostMapping("/{id}/connectivity-test")
    @Operation(summary = "MCP 服务连通性测试", description = "测试 MCP 服务是否可正常连接")
    fun connectivityTest(
        @Parameter(description = "MCP ID") @PathVariable(name = "id") id: Long
    ): ResultVo<Boolean> = try {
        val result = mcpServerService.connectivityTest(id)
        ResultVo.success(result)
    } catch (e: Exception) {
        log.error("MCP 服务连通性测试失败", e)
        ResultVo.error(e.message ?: "MCP 服务连通性测试失败")
    }

    @GetMapping("/{id}/list_tools")
    @Operation(summary = "获取 MCP 工具列表", description = "获取 MCP 服务提供的工具列表（Mock 数据）")
    fun listTools(
        @Parameter(description = "MCP ID") @PathVariable(name = "id") id: Long
    ): ResultVo<List<McpToolResponse>> = try {
        // TODO: 后续替换为真实的工具列表获取逻辑
        val mockTools = getMockTools()
        ResultVo.success(mockTools)
    } catch (e: Exception) {
        log.error("获取 MCP 工具列表失败", e)
        ResultVo.error(e.message ?: "获取 MCP 工具列表失败")
    }

    /**
     * 获取 Mock 工具列表
     */
    private fun getMockTools(): List<McpToolResponse> {
        val tools = mutableListOf<McpToolResponse>()

        // 工具 1: 读取文件
        val readFile = McpToolResponse().apply {
            name = "read_file"
            parameters = listOf(
                createParameter("file_path", "string", "文件路径，例如: /path/to/file.txt"),
                createParameter("encoding", "string", "文件编码，默认为 utf-8")
            )
        }
        tools.add(readFile)

        // 工具 2: 写入文件
        val writeFile = McpToolResponse().apply {
            name = "write_file"
            parameters = listOf(
                createParameter("file_path", "string", "文件路径，例如: /path/to/file.txt"),
                createParameter("content", "string", "要写入的文件内容"),
                createParameter("encoding", "string", "文件编码，默认为 utf-8")
            )
        }
        tools.add(writeFile)

        // 工具 3: 列出目录
        val listDirectory = McpToolResponse().apply {
            name = "list_directory"
            parameters = listOf(
                createParameter("directory_path", "string", "目录路径，例如: /path/to/directory")
            )
        }
        tools.add(listDirectory)

        // 工具 4: 搜索文件
        val searchFiles = McpToolResponse().apply {
            name = "search_files"
            parameters = listOf(
                createParameter("directory_path", "string", "搜索的目录路径"),
                createParameter("pattern", "string", "搜索模式，支持通配符，例如: *.txt"),
                createParameter("recursive", "boolean", "是否递归搜索子目录，默认为 false")
            )
        }
        tools.add(searchFiles)

        // 工具 5: 执行命令
        val executeCommand = McpToolResponse().apply {
            name = "execute_command"
            parameters = listOf(
                createParameter("command", "string", "要执行的命令，例如: ls -la"),
                createParameter("working_directory", "string", "工作目录，默认为当前目录"),
                createParameter("timeout", "integer", "命令执行超时时间（秒），默认为 30")
            )
        }
        tools.add(executeCommand)

        return tools
    }

    /**
     * 创建参数对象
     */
    private fun createParameter(name: String, type: String, description: String): McpToolResponse.McpToolParameter {
        return McpToolResponse.McpToolParameter().apply {
            this.name = name
            this.type = type
            this.description = description
        }
    }
}

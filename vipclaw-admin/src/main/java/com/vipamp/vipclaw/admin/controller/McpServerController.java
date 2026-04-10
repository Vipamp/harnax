package com.vipamp.vipclaw.admin.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vipamp.vipclaw.admin.dto.McpServerCreateRequest;
import com.vipamp.vipclaw.admin.dto.McpServerResponse;
import com.vipamp.vipclaw.admin.dto.McpServerUpdateRequest;
import com.vipamp.vipclaw.admin.dto.McpToolResponse;
import com.vipamp.vipclaw.admin.entity.McpServer;
import com.vipamp.vipclaw.admin.service.McpServerService;
import com.vipamp.vipclaw.admin.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * MCP 服务管理控制器
 *
 * @author vipamp
 * @since 2026-03-12
 */
@Slf4j
@RestController
@RequestMapping("/mcp")
@RequiredArgsConstructor
@Tag(name = "MCP 服务管理", description = "MCP 服务相关接口")
public class McpServerController {

    private final McpServerService mcpServerService;

    @GetMapping("/page")
    @Operation(summary = "分页获取 MCP 服务列表", description = "分页查询 MCP 服务信息")
    public Result<Page<McpServerResponse>> getMcpServerPage(
            @Parameter(description = "页码", example = "1") @RequestParam(name = "current", defaultValue = "1") Integer current,
            @Parameter(description = "每页大小", example = "10") @RequestParam(name = "size", defaultValue = "10") Integer size,
            @Parameter(description = "关键词（名称/描述）") @RequestParam(name = "keyword", required = false) String keyword,
            @Parameter(description = "状态筛选（0:禁用 1:启用）") @RequestParam(name = "status", required = false) Integer status,
            @Parameter(description = "类型筛选（可多选，逗号分隔）") @RequestParam(name = "types", required = false) String types) {
        try {
            Page<McpServer> page = mcpServerService.getMcpServerPage(keyword, status, types, current, size);
            Page<McpServerResponse> responsePage = convertToResponsePage(page);
            return Result.success(responsePage);
        } catch (Exception e) {
            log.error("获取 MCP 服务列表失败", e);
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取 MCP 服务详情", description = "根据 ID 获取 MCP 服务信息")
    public Result<McpServerResponse> getMcpServerById(
            @Parameter(description = "MCP ID") @PathVariable(name = "id") Long id) {
        try {
            McpServer mcpServer = mcpServerService.getMcpServerById(id);
            return Result.success(McpServerResponse.fromEntity(mcpServer));
        } catch (Exception e) {
            log.error("获取 MCP 服务详情失败", e);
            return Result.error(e.getMessage());
        }
    }

    @PostMapping
    @Operation(summary = "创建 MCP 服务", description = "新增 MCP 服务")
    public Result<Void> createMcpServer(
            @Valid @RequestBody McpServerCreateRequest request) {
        try {
            return mcpServerService.createMcpServer(request) ? Result.success() : Result.error("创建 MCP 服务失败");
        } catch (Exception e) {
            log.error("创建 MCP 服务失败", e);
            return Result.error(e.getMessage());
        }
    }

    @PutMapping("/update/{id}")
    @Operation(summary = "更新 MCP 服务", description = "根据 ID 更新 MCP 服务信息")
    public Result<Void> updateMcpServer(
            @Parameter(description = "MCP ID") @PathVariable(name = "id") Long id,
            @Valid @RequestBody McpServerUpdateRequest request) {
        try {
            request.setId(id);
            return mcpServerService.updateMcpServer(id, request) ? Result.success() : Result.error("更新 MCP 服务失败");
        } catch (Exception e) {
            log.error("更新 MCP 服务失败", e);
            return Result.error(e.getMessage());
        }
    }

    @PutMapping("/toggle/{id}")
    @Operation(summary = "切换 MCP 服务启用状态", description = "启用或禁用 MCP 服务")
    public Result<Void> toggleMcpServer(
            @Parameter(description = "MCP ID") @PathVariable(name = "id") Long id,
            @Parameter(description = "启用状态（0:禁用 1:启用）") @RequestParam(name = "status") Integer status) {
        try {
            return mcpServerService.toggleMcpServerStatus(id, status) ? Result.success() : Result.error("切换状态失败");
        } catch (Exception e) {
            log.error("切换 MCP 服务状态失败", e);
            return Result.error(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除 MCP 服务", description = "根据 ID 逻辑删除 MCP 服务")
    public Result<Void> deleteMcpServer(
            @Parameter(description = "MCP ID") @PathVariable(name = "id") Long id) {
        try {
            return mcpServerService.deleteMcpServer(id) ? Result.success() : Result.error("删除 MCP 服务失败");
        } catch (Exception e) {
            log.error("删除 MCP 服务失败", e);
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/{id}/connectivity-test")
    @Operation(summary = "MCP 服务连通性测试", description = "测试 MCP 服务是否可正常连接")
    public Result<Boolean> connectivityTest(
            @Parameter(description = "MCP ID") @PathVariable(name = "id") Long id) {
        try {
            boolean result = mcpServerService.connectivityTest(id);
            return Result.success(result);
        } catch (Exception e) {
            log.error("MCP 服务连通性测试失败", e);
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/{id}/list_tools")
    @Operation(summary = "获取 MCP 工具列表", description = "获取 MCP 服务提供的工具列表（Mock 数据）")
    public Result<List<McpToolResponse>> listTools(
            @Parameter(description = "MCP ID") @PathVariable(name = "id") Long id) {
        try {
            // TODO: 后续替换为真实的工具列表获取逻辑
            List<McpToolResponse> mockTools = getMockTools();
            return Result.success(mockTools);
        } catch (Exception e) {
            log.error("获取 MCP 工具列表失败", e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 获取 Mock 工具列表
     */
    private List<McpToolResponse> getMockTools() {
        List<McpToolResponse> tools = new ArrayList<>();

        // 工具 1: 读取文件
        McpToolResponse readFile = new McpToolResponse();
        readFile.setName("read_file");
        readFile.setParameters(Arrays.asList(
                createParameter("file_path", "string", "文件路径，例如: /path/to/file.txt"),
                createParameter("encoding", "string", "文件编码，默认为 utf-8")
        ));
        tools.add(readFile);

        // 工具 2: 写入文件
        McpToolResponse writeFile = new McpToolResponse();
        writeFile.setName("write_file");
        writeFile.setParameters(Arrays.asList(
                createParameter("file_path", "string", "文件路径，例如: /path/to/file.txt"),
                createParameter("content", "string", "要写入的文件内容"),
                createParameter("encoding", "string", "文件编码，默认为 utf-8")
        ));
        tools.add(writeFile);

        // 工具 3: 列出目录
        McpToolResponse listDirectory = new McpToolResponse();
        listDirectory.setName("list_directory");
        listDirectory.setParameters(Arrays.asList(
                createParameter("directory_path", "string", "目录路径，例如: /path/to/directory")
        ));
        tools.add(listDirectory);

        // 工具 4: 搜索文件
        McpToolResponse searchFiles = new McpToolResponse();
        searchFiles.setName("search_files");
        searchFiles.setParameters(Arrays.asList(
                createParameter("directory_path", "string", "搜索的目录路径"),
                createParameter("pattern", "string", "搜索模式，支持通配符，例如: *.txt"),
                createParameter("recursive", "boolean", "是否递归搜索子目录，默认为 false")
        ));
        tools.add(searchFiles);

        // 工具 5: 执行命令
        McpToolResponse executeCommand = new McpToolResponse();
        executeCommand.setName("execute_command");
        executeCommand.setParameters(Arrays.asList(
                createParameter("command", "string", "要执行的命令，例如: ls -la"),
                createParameter("working_directory", "string", "工作目录，默认为当前目录"),
                createParameter("timeout", "integer", "命令执行超时时间（秒），默认为 30")
        ));
        tools.add(executeCommand);

        return tools;
    }

    /**
     * 创建参数对象
     */
    private McpToolResponse.McpToolParameter createParameter(String name, String type, String description) {
        McpToolResponse.McpToolParameter parameter = new McpToolResponse.McpToolParameter();
        parameter.setName(name);
        parameter.setType(type);
        parameter.setDescription(description);
        return parameter;
    }

    /**
     * 分页结果转换
     */
    private Page<McpServerResponse> convertToResponsePage(Page<McpServer> page) {
        Page<McpServerResponse> responsePage = new Page<>(page.getCurrent(), page.getSize());
        responsePage.setTotal(page.getTotal());
        responsePage.setSize(page.getSize());
        responsePage.setCurrent(page.getCurrent());
        responsePage.setPages(page.getPages());
        responsePage.setRecords(page.getRecords().stream()
                .map(McpServerResponse::fromEntity)
                .toList());
        return responsePage;
    }
}

package com.vipclaw.admin.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.vipclaw.admin.dto.McpServerCreateRequest;
import com.vipclaw.admin.dto.McpServerUpdateRequest;
import com.vipclaw.admin.entity.McpServer;
import org.springframework.lang.Nullable;

/**
 * MCP 服务接口
 *
 * @author vipamp
 * @since 2026-03-12
 */
public interface McpServerService extends IService<McpServer> {

    /**
     * 分页查询 MCP 服务列表
     *
     * @param keyword 模糊查询字段（名称/描述）
     * @param status  状态筛选字段
     * @param types   类型筛选字段（逗号分隔，如：stdio,sse）
     * @param current 当前页码
     * @param size    每页大小
     * @return 分页结果
     */
    Page<McpServer> getMcpServerPage(@Nullable String keyword, @Nullable Integer status, @Nullable String types, Integer current, Integer size);

    /**
     * 获取单个 MCP 服务详情
     *
     * @param id MCP ID
     * @return MCP 服务实体
     */
    McpServer getMcpServerById(Long id);

    /**
     * 创建 MCP 服务
     *
     * @param request MCP 服务创建请求对象
     * @return 创建结果
     */
    boolean createMcpServer(McpServerCreateRequest request);

    /**
     * 更新 MCP 服务
     *
     * @param id      MCP ID
     * @param request MCP 服务更新请求对象
     * @return 更新结果
     */
    boolean updateMcpServer(Long id, McpServerUpdateRequest request);

    /**
     * 切换 MCP 服务启用状态
     *
     * @param id     MCP ID
     * @param status 启用状态（0:禁用，1:启用）
     * @return 更新结果
     */
    boolean toggleMcpServerStatus(Long id, Integer status);

    /**
     * 删除 MCP 服务（逻辑删除）
     *
     * @param id MCP ID
     * @return 删除结果
     */
    boolean deleteMcpServer(Long id);

    /**
     * MCP 服务连通性测试
     *
     * @param id MCP ID
     * @return 连通测试结果（true: 成功，false: 失败）
     */
    boolean connectivityTest(Long id);
}

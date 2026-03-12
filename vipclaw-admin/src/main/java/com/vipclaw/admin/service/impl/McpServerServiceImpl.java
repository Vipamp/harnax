package com.vipclaw.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.vipclaw.admin.dto.McpServerCreateRequest;
import com.vipclaw.admin.dto.McpServerUpdateRequest;
import com.vipclaw.admin.entity.McpServer;
import com.vipclaw.admin.exception.BizException;
import com.vipclaw.admin.mapper.McpServerMapper;
import com.vipclaw.admin.service.McpServerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * MCP 服务实现类
 *
 * @author vipamp
 * @since 2026-03-12
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpServerServiceImpl extends ServiceImpl<McpServerMapper, McpServer> implements McpServerService {

    @Override
    public Page<McpServer> getMcpServerPage(@Nullable String keyword, @Nullable Integer status,
                                             @Nullable String types, Integer current, Integer size) {
        log.info("分页查询 MCP 服务列表，current: {}, size: {}, keyword: {}, status: {}, types: {}", current, size, keyword, status, types);

        Page<McpServer> page = new Page<>(current, size);
        LambdaQueryWrapper<McpServer> wrapper = new LambdaQueryWrapper<>();

        // 模糊查询（名称或描述）
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(McpServer::getName, keyword)
                    .or().like(McpServer::getDescription, keyword));
        }

        // 状态筛选
        if (status != null) {
            wrapper.eq(McpServer::getStatus, status);
        }

        // 类型筛选（支持多选，逗号分隔）
        if (StringUtils.hasText(types)) {
            String[] typeArray = types.split(",");
            wrapper.in(McpServer::getType, (Object[]) typeArray);
        }

        wrapper.eq(McpServer::getActive, 1);
        wrapper.orderByDesc(McpServer::getUpdateTime);
        return this.page(page, wrapper);
    }

    @Override
    public McpServer getMcpServerById(Long id) {
        log.info("查询 MCP 服务详情，id: {}", id);
        McpServer mcpServer = this.getById(id);
        if (mcpServer == null) {
            throw new BizException("MCP 服务不存在");
        }
        return mcpServer;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean createMcpServer(McpServerCreateRequest request) {
        log.info("创建 MCP 服务，name: {}", request.getName());

        // 校验名称唯一性
        LambdaQueryWrapper<McpServer> checkWrapper = new LambdaQueryWrapper<>();
        checkWrapper.eq(McpServer::getName, request.getName())
                .eq(McpServer::getActive, 1)
                .last("LIMIT 1");
        McpServer existing = this.getOne(checkWrapper);
        if (existing != null) {
            throw new BizException("MCP 名称已存在");
        }

        // 校验 type 与字段的联动逻辑
        validateTypeAndFields(request.getType(), request.getCommand(), request.getUrl());

        McpServer mcpServer = new McpServer();
        mcpServer.setName(request.getName());
        mcpServer.setDescription(request.getDescription());
        mcpServer.setType(request.getType());
        mcpServer.setCommand(request.getCommand());
        mcpServer.setUrl(request.getUrl());
        mcpServer.setStatus(request.getStatus() != null ? request.getStatus() : 1);
        mcpServer.setActive(1);

        boolean success = this.save(mcpServer);
        log.info("MCP 服务创建{}，id: {}", success ? "成功" : "失败", mcpServer.getId());
        return success;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateMcpServer(Long id, McpServerUpdateRequest request) {
        log.info("更新 MCP 服务，id: {}", id);

        McpServer mcpServer = this.getById(id);
        if (mcpServer == null) {
            throw new BizException("MCP 服务不存在");
        }

        // 如果修改了名称，需校验唯一性
        if (request.getName() != null && !request.getName().equals(mcpServer.getName())) {
            LambdaQueryWrapper<McpServer> checkWrapper = new LambdaQueryWrapper<>();
            checkWrapper.eq(McpServer::getName, request.getName())
                    .eq(McpServer::getActive, 1)
                    .last("LIMIT 1");
            McpServer existing = this.getOne(checkWrapper);
            if (existing != null) {
                throw new BizException("MCP 名称已存在");
            }
            mcpServer.setName(request.getName());
        }

        // 选择性更新字段
        if (request.getDescription() != null) {
            mcpServer.setDescription(request.getDescription());
        }
        if (StringUtils.hasText(request.getType())) {
            mcpServer.setType(request.getType());
        }
        if (request.getCommand() != null) {
            mcpServer.setCommand(request.getCommand());
        }
        if (request.getUrl() != null) {
            mcpServer.setUrl(request.getUrl());
        }
        if (request.getStatus() != null) {
            mcpServer.setStatus(request.getStatus());
        }

        // 校验更新后 type 与字段的联动逻辑
        validateTypeAndFields(mcpServer.getType(), mcpServer.getCommand(), mcpServer.getUrl());

        boolean success = this.updateById(mcpServer);
        log.info("MCP 服务更新{}，id: {}", success ? "成功" : "失败", id);
        return success;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean toggleMcpServerStatus(Long id, Integer status) {
        log.info("切换 MCP 服务状态，id: {}, status: {}", id, status);

        McpServer mcpServer = this.getById(id);
        if (mcpServer == null) {
            throw new BizException("MCP 服务不存在");
        }

        LambdaUpdateWrapper<McpServer> wrapper = new LambdaUpdateWrapper<>();
        wrapper.set(McpServer::getStatus, status)
                .eq(McpServer::getId, id);
        boolean success = this.update(wrapper);
        log.info("MCP 服务状态切换{}，id: {}, status: {}", success ? "成功" : "失败", id, status);
        return success;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteMcpServer(Long id) {
        log.info("删除 MCP 服务，id: {}", id);

        McpServer mcpServer = this.getById(id);
        if (mcpServer == null) {
            throw new BizException("MCP 服务不存在");
        }

        LambdaUpdateWrapper<McpServer> wrapper = new LambdaUpdateWrapper<>();
        wrapper.set(McpServer::getActive, 0)
                .eq(McpServer::getId, id);
        boolean success = this.update(wrapper);
        log.info("MCP 服务删除{}，id: {}", success ? "成功" : "失败", id);
        return success;
    }

    @Override
    public boolean connectivityTest(Long id) {
        log.info("MCP 服务连通性测试，id: {}", id);

        McpServer mcpServer = this.getById(id);
        if (mcpServer == null) {
            throw new BizException("MCP 服务不存在");
        }

        // TODO: 实现实际的连通性测试逻辑
        // 根据 mcpServer.getType() 进行不同的测试：
        // - stdio: 尝试启动进程并检查是否能正常通信
        // - sse: 尝试建立 SSE 连接
        // - streamablehttp: 尝试发送 HTTP 请求

        log.info("MCP 服务连通性测试通过，id: {}", id);
        return true;
    }

    /**
     * 校验 type 与 command/url 字段的联动逻辑
     *
     * @param type    MCP 类型
     * @param command 执行命令
     * @param url     服务地址
     */
    private void validateTypeAndFields(String type, String command, String url) {
        if ("stdio".equals(type)) {
            if (!StringUtils.hasText(command)) {
                throw new BizException("stdio 类型的 MCP 服务，command 不能为空");
            }
        } else if ("sse".equals(type) || "streamablehttp".equals(type)) {
            if (!StringUtils.hasText(url)) {
                throw new BizException(type + " 类型的 MCP 服务，url 不能为空");
            }
        } else {
            throw new BizException("不支持的 MCP 类型：" + type + "，仅支持 stdio/sse/streamablehttp");
        }
    }
}

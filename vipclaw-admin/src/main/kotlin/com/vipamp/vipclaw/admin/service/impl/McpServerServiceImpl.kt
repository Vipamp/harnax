package com.vipamp.vipclaw.admin.service.impl

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper
import com.baomidou.mybatisplus.extension.plugins.pagination.Page
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import com.vipamp.vipclaw.admin.dto.McpServerCreateRequest
import com.vipamp.vipclaw.admin.dto.McpServerUpdateRequest
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.service.McpServerService
import com.vipamp.vipclaw.admin.util.JwtUtil
import com.vipamp.vipclaw.admin.util.UserContextUtil
import com.vipamp.vipclaw.common.entity.McpServer
import com.vipamp.vipclaw.common.mapper.McpServerMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.StringUtils.hasText

/**
 * MCP 服务实现类
 *
 * @author vipamp
 * @since 2026-03-12
 */
@Service
class McpServerServiceImpl(
    private val jwtUtil: JwtUtil
) : ServiceImpl<McpServerMapper, McpServer>(), McpServerService {

    private val log = LoggerFactory.getLogger(McpServerServiceImpl::class.java)

    override fun getMcpServerPage(
        keyword: String?,
        status: Int?,
        types: String?,
        current: Int,
        size: Int
    ): Page<McpServer> {
        log.info("分页查询 MCP 服务列表，current: {}, size: {}, keyword: {}, status: {}, types: {}", current, size, keyword, status, types)

        val page = Page<McpServer>(current.toLong(), size.toLong())
        val wrapper = LambdaQueryWrapper<McpServer>()

        // 获取当前用户
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)

        // 权限过滤：只查询公开的或自己创建的
        wrapper.and { w ->
            w.eq(McpServer::getIsPublic, 1)
                .or()
                .eq(McpServer::creator, currentUsername)
        }

        // 模糊查询（名称或描述）
        if (hasText(keyword)) {
            wrapper.and { w ->
                w.like(McpServer::getName, keyword)
                    .or().like(McpServer::getDescription, keyword)
            }
        }

        // 状态筛选
        status?.let { wrapper.eq(McpServer::getStatus, it) }

        // 类型筛选（支持多选，逗号分隔）
        if (hasText(types)) {
            val typeArray = types!!.split(",")
            wrapper.in(McpServer::getType, *typeArray.toTypedArray())
        }

        wrapper.eq(McpServer::getActive, 1)
        wrapper.orderByDesc(McpServer::getStatus)
            .orderByDesc(McpServer::getUpdateTime)
        return this.page(page, wrapper)
    }

    override fun getMcpServerById(id: Long): McpServer {
        log.info("查询 MCP 服务详情，id: {}", id)
        val mcpServer = this.getById(id)
            ?: throw BizException("MCP 服务不存在")
        return mcpServer
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun createMcpServer(request: McpServerCreateRequest): Boolean {
        log.info("创建 MCP 服务，name: {}", request.name)

        // 校验名称唯一性
        val checkWrapper = LambdaQueryWrapper<McpServer>()
        checkWrapper.eq(McpServer::getName, request.name)
            .eq(McpServer::getActive, 1)
            .last("LIMIT 1")
        val existing = this.getOne(checkWrapper)
        if (existing != null) {
            throw BizException("MCP 名称已存在")
        }

        // 校验 type 与字段的联动逻辑
        validateTypeAndFields(request.type, request.command, request.url)

        val mcpServer = McpServer()
        mcpServer.name = request.name
        mcpServer.description = request.description
        mcpServer.type = request.type
        mcpServer.command = request.command
        mcpServer.url = request.url
        mcpServer.status = request.status ?: 1
        mcpServer.active = 1

        // 设置创建人
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        mcpServer.creator = currentUsername

        // 默认不公开
        if (mcpServer.isPublic == null) {
            mcpServer.isPublic = 0
        }

        val success = this.save(mcpServer)
        log.info("MCP 服务创建{}，id: {}", if (success) "成功" else "失败", mcpServer.id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateMcpServer(id: Long, request: McpServerUpdateRequest): Boolean {
        log.info("更新 MCP 服务，id: {}", id)

        val mcpServer = this.getById(id)
            ?: throw BizException("MCP 服务不存在")

        // 如果修改了名称，需校验唯一性
        if (request.name != null && request.name != mcpServer.name) {
            val checkWrapper = LambdaQueryWrapper<McpServer>()
            checkWrapper.eq(McpServer::getName, request.name)
                .eq(McpServer::getActive, 1)
                .last("LIMIT 1")
            val existing = this.getOne(checkWrapper)
            if (existing != null) {
                throw BizException("MCP 名称已存在")
            }
            mcpServer.name = request.name
        }

        // 选择性更新字段
        request.description?.let { mcpServer.description = it }
        if (hasText(request.type)) {
            mcpServer.type = request.type
        }
        request.command?.let { mcpServer.command = it }
        request.url?.let { mcpServer.url = it }
        request.status?.let { mcpServer.status = it }
        request.isPublic?.let { mcpServer.isPublic = it }

        // 校验更新后 type 与字段的联动逻辑
        validateTypeAndFields(mcpServer.type, mcpServer.command, mcpServer.url)

        val success = this.updateById(mcpServer)
        log.info("MCP 服务更新{}，id: {}", if (success) "成功" else "失败", id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun toggleMcpServerStatus(id: Long, status: Int): Boolean {
        log.info("切换 MCP 服务状态，id: {}, status: {}", id, status)

        val mcpServer = this.getById(id)
            ?: throw BizException("MCP 服务不存在")

        val wrapper = LambdaUpdateWrapper<McpServer>()
        wrapper.set(McpServer::getStatus, status)
            .eq(McpServer::getId, id)
        val success = this.update(wrapper)
        log.info("MCP 服务状态切换{}，id: {}, status: {}", if (success) "成功" else "失败", id, status)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun deleteMcpServer(id: Long): Boolean {
        log.info("删除 MCP 服务，id: {}", id)

        val mcpServer = this.getById(id)
            ?: throw BizException("MCP 服务不存在")

        val wrapper = LambdaUpdateWrapper<McpServer>()
        wrapper.set(McpServer::getActive, 0)
            .eq(McpServer::getId, id)
        val success = this.update(wrapper)
        log.info("MCP 服务删除{}，id: {}", if (success) "成功" else "失败", id)
        return success
    }

    override fun connectivityTest(id: Long): Boolean {
        log.info("MCP 服务连通性测试，id: {}", id)

        val mcpServer = this.getById(id)
            ?: throw BizException("MCP 服务不存在")

        // TODO: 实现实际的连通性测试逻辑
        return true
    }

    /**
     * 校验 type 与 command/url 字段的联动逻辑
     *
     * @param type MCP 类型
     * @param command 执行命令
     * @param url 服务地址
     */
    private fun validateTypeAndFields(type: String?, command: String?, url: String?) {
        when (type) {
            "stdio" -> {
                if (!hasText(command)) {
                    throw BizException("stdio 类型的 MCP 服务，command 不能为空")
                }
            }
            "sse", "streamablehttp" -> {
                if (!hasText(url)) {
                    throw BizException("$type 类型的 MCP 服务，url 不能为空")
                }
            }
            else -> {
                throw BizException("不支持的 MCP 类型：$type，仅支持 stdio/sse/streamablehttp")
            }
        }
    }
}

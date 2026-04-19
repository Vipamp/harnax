package com.vipamp.vipclaw.admin.service.impl

import com.github.pagehelper.PageHelper
import com.vipamp.vipclaw.common.page.Page
import java.time.LocalDateTime
import com.vipamp.vipclaw.admin.dto.McpServerCreateRequest
import com.vipamp.vipclaw.admin.dto.McpServerUpdateRequest
import com.vipamp.vipclaw.admin.entity.McpServer
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.mapper.McpServerMapper
import com.vipamp.vipclaw.admin.service.McpServerService
import com.vipamp.vipclaw.admin.util.JwtUtil
import com.vipamp.vipclaw.admin.util.UserContextUtil
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.StringUtils.hasText
import kotlin.math.min

/**
 * MCP 服务实现类
 *
 * @author vipamp
 * @since 2026-03-12
 */
@Service
class McpServerServiceImpl(
    private val jwtUtil: JwtUtil,
    private val mcpServerMapper: McpServerMapper
) : McpServerService {

    private val log = LoggerFactory.getLogger(McpServerServiceImpl::class.java)

    override fun getMcpServerPage(
        keyword: String?,
        status: Int?,
        types: String?,
        current: Int,
        size: Int
    ): Page<McpServer> {
        log.info(
            "分页查询 MCP 服务列表，current: {}, size: {}, keyword: {}, status: {}, types: {}",
            current,
            size,
            keyword,
            status,
            types
        )

        // 获取当前用户
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)

        // 处理 types 参数，将逗号分隔转为 SQL IN 格式
        val typesSql = if (hasText(types)) {
            types!!.split(",").joinToString(",") { "'$it'" }
        } else {
            null
        }

        // 使用 MyBatis 原生查询
        val allMcpServers = mcpServerMapper.selectMcpServerList(keyword, status, typesSql, currentUsername)

        // 手动分页
        val page = Page<McpServer>(current.toLong(), size.toLong())
        val fromIndex = (current - 1) * size
        val toIndex = min(fromIndex + size, allMcpServers.size)
        
        page.records = if (fromIndex < allMcpServers.size) {
            allMcpServers.subList(fromIndex, toIndex)
        } else {
            emptyList()
        }
        page.total = allMcpServers.size.toLong()

        return page
    }

    override fun getMcpServerById(id: Long): McpServer {
        log.info("查询 MCP 服务详情，id: {}", id)
        val mcpServer = this.mcpServerMapper.selectById(id)
            ?: throw BizException("MCP 服务不存在")
        return mcpServer
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun createMcpServer(request: McpServerCreateRequest): Boolean {
        log.info("创建 MCP 服务，name: {}", request.name)

        // 校验名称唯一性
        val existing = mcpServerMapper.selectByName(request.name!!)
        if (existing != null) {
            throw BizException("MCP 名称已存在")
        }

        // 校验 type 与字段的联动逻辑
        validateTypeAndFields(request.type, request.command, request.url)

        val mcpServer = McpServer()
        mcpServer.name = request.name!!
        mcpServer.description = request.description!!
        mcpServer.type = request.type!!
        mcpServer.command = request.command!!
        mcpServer.url = request.url!!
        mcpServer.status = request.status ?: 1
        mcpServer.active = 1

        // 设置创建人
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        mcpServer.creator = currentUsername

        val success = this.mcpServerMapper.insert(mcpServer) > 0
        log.info("MCP 服务创建{}，id: {}", if (success) "成功" else "失败", mcpServer.id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateMcpServer(id: Long, request: McpServerUpdateRequest): Boolean {
        log.info("更新 MCP 服务，id: {}", id)

        val mcpServer = mcpServerMapper.selectActiveById(id)
            ?: throw BizException("MCP 服务不存在")

        // 如果修改了名称，需校验唯一性
        if (request.name != mcpServer.name) {
            val existing = mcpServerMapper.selectByName(request.name!!)
            if (existing != null) {
                throw BizException("MCP 名称已存在")
            }
            mcpServer.name = request.name
        }

        // 选择性更新字段
        request.description.let { mcpServer.description = it }
        if (hasText(request.type)) {
            mcpServer.type = request.type
        }
        request.command.let { mcpServer.command = it }
        request.url.let { mcpServer.url = it }
        request.status.let { mcpServer.status = it }
        request.isPublic.let { mcpServer.isPublic = it }

        // 校验更新后 type 与字段的联动逻辑
        validateTypeAndFields(mcpServer.type, mcpServer.command, mcpServer.url)

        val success = this.mcpServerMapper.updateById(mcpServer) > 0
        log.info("MCP 服务更新{}，id: {}", if (success) "成功" else "失败", id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun toggleMcpServerStatus(id: Long, status: Int): Boolean {
        log.info("切换 MCP 服务状态，id: {}, status: {}", id, status)

        val mcpServer = mcpServerMapper.selectActiveById(id)
            ?: throw BizException("MCP 服务不存在")

        val success = mcpServerMapper.updateStatus(id, status) > 0
        log.info("MCP 服务状态切换{}，id: {}, status: {}", if (success) "成功" else "失败", id, status)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun deleteMcpServer(id: Long): Boolean {
        log.info("删除 MCP 服务，id: {}", id)

        val mcpServer = mcpServerMapper.selectActiveById(id)
            ?: throw BizException("MCP 服务不存在")

        val success = mcpServerMapper.logicalDelete(id) > 0
        log.info("MCP 服务删除{}，id: {}", if (success) "成功" else "失败", id)
        return success
    }

    override fun connectivityTest(id: Long): Boolean {
        log.info("MCP 服务连通性测试，id: {}", id)

        val mcpServer = this.mcpServerMapper.selectById(id)
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

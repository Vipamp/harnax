package com.vipamp.vipclaw.admin.service.impl

import com.github.pagehelper.PageHelper
import com.vipamp.vipclaw.admin.dto.McpServerCreateRequest
import com.vipamp.vipclaw.admin.dto.McpServerResponse
import com.vipamp.vipclaw.admin.dto.McpServerUpdateRequest
import com.vipamp.vipclaw.admin.dto.Page
import com.vipamp.vipclaw.admin.entity.Agent
import com.vipamp.vipclaw.admin.entity.McpServer
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.mapper.McpServerMapper
import com.vipamp.vipclaw.admin.service.McpServerService
import com.vipamp.vipclaw.admin.util.JwtUtil
import com.vipamp.vipclaw.admin.util.UserContextUtil
import com.vipamp.vipclaw.agent.adaptor.McpConfigAdaptor
import com.vipamp.vipclaw.agent.adaptor.mcp.McpHelper
import io.modelcontextprotocol.spec.McpSchema
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
    private val jwtUtil: JwtUtil,
    private val mcpServerMapper: McpServerMapper,
    private val mcpAdaptor: McpConfigAdaptor,
) : McpServerService {

    private val log = LoggerFactory.getLogger(McpServerServiceImpl::class.java)

    override fun page(
        keyword: String?,
        status: Int?,
        type: String?,
        pageNum: Int,
        pageSize: Int,
    ): Page<McpServer> {
        log.info(
            "分页查询 MCP 服务列表，pageNum: {}, pageSize: {}, keyword: {}, status: {}, type: {}",
            pageNum,
            pageSize,
            keyword,
            status,
            type,
        )

        // 获取当前用户
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)

        PageHelper.startPage<Agent>(pageNum, pageSize)
        return Page.fromPageInfo(mcpServerMapper.selectMcpServerList(keyword, status, type, currentUsername))
    }

    override fun getMcpServer(id: Long): McpServer? = this.mcpServerMapper.selectById(id)

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
        mcpServer.name = request.name
        mcpServer.description = request.description ?: ""
        mcpServer.type = request.type
        mcpServer.command = request.command ?: ""
        mcpServer.url = request.url ?: ""
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

        val mcpServer = mcpServerMapper.selectById(id)
            ?: throw BizException("MCP 服务不存在")

        // 如果修改了名称，需校验唯一性
        if (request.name != mcpServer.name) {
            val existing = mcpServerMapper.selectByName(request.name)
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

        val mcpServer = mcpServerMapper.selectById(id)
            ?: throw BizException("MCP 服务不存在")

        val success = mcpServerMapper.updateStatus(id, status) > 0
        log.info("MCP 服务状态切换{}，id: {}, status: {}", if (success) "成功" else "失败", id, status)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun deleteMcpServer(id: Long): Boolean {
        log.info("删除 MCP 服务，id: {}", id)

        val mcpServer = mcpServerMapper.selectById(id)
            ?: throw BizException("MCP 服务不存在")

        val success = mcpServerMapper.deleteById(id) > 0
        log.info("MCP 服务删除{}，id: {}", if (success) "成功" else "失败", id)
        return success
    }

    override fun connectivityTest(id: Long): Boolean {
        log.info("MCP 服务连通性测试，id: {}", id)
        listTools(id)
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

    override fun convertToResponse(mcpServer: McpServer): McpServerResponse = McpServerResponse.fromEntity(mcpServer)

    override fun listTools(mcpId: Long): List<McpSchema.Tool> {
        val mcpServer = mcpAdaptor.getConfig(mcpId) ?: throw BizException("MCP 服务不存在")
        return McpHelper.listTools(mcpServer)
    }
}

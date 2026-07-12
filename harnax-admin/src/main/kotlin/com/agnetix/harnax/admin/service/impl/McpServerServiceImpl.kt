package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.McpServerCreateRequest
import com.agnetix.harnax.admin.dto.McpServerResponse
import com.agnetix.harnax.admin.dto.McpServerUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.McpServerService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.SecretFieldEncryptor
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.agent.adaptor.mcp.McpHelper
import com.agnetix.harnax.entity.Agent
import com.agnetix.harnax.entity.McpServer
import com.agnetix.harnax.mapper.McpServerMapper
import com.github.pagehelper.PageHelper
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.StringUtils.hasText
import tools.jackson.databind.ObjectMapper

/**
 * MCP server service implementation
 */
@Service
class McpServerServiceImpl(
    private val jwtUtil: JwtUtil,
    private val mcpServerMapper: McpServerMapper,
    private val secretFieldEncryptor: SecretFieldEncryptor,
    private val objectMapper: ObjectMapper,
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
            "Paginated query for MCP server list, pageNum: {}, pageSize: {}, keyword: {}, status: {}, type: {}",
            pageNum,
            pageSize,
            keyword,
            status,
            type,
        )

        // Get current user
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)

        PageHelper.startPage<Agent>(pageNum, pageSize)
        return Page.fromPageInfo(mcpServerMapper.selectMcpServerList(keyword, status, type, currentUsername))
    }

    override fun getMcpServer(id: Long): McpServer? = this.mcpServerMapper.selectById(id)

    @Transactional(rollbackFor = [Exception::class])
    override fun createMcpServer(request: McpServerCreateRequest): Boolean {
        log.info("Creating MCP server, name: {}", request.name)

        // Validate name uniqueness
        val existing = mcpServerMapper.selectByName(request.name!!)
        if (existing != null) {
            throw BizException("MCP name already exists")
        }

        // Validate type and field linkage logic
        validateTypeAndFields(request.type, request.command, request.url)

        val mcpServer = McpServer()
        mcpServer.name = request.name
        mcpServer.description = request.description ?: ""
        mcpServer.type = request.type
        mcpServer.command = request.command ?: ""
        mcpServer.url = request.url ?: ""
        mcpServer.status = request.status ?: 1
        mcpServer.active = 1

        // Serialize headers with encryption (McpConfigEntry) and envParams with ToolEnvParamEntry format
        mcpServer.headers = secretFieldEncryptor.serializeWithEncryption(request.headers)
        mcpServer.envParams = secretFieldEncryptor.serializeToolEnvParams(request.envParams)

        // Set tenant ID
        mcpServer.tenantId = TenantContext.getTenantId() ?: 1

        // Set creator
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        mcpServer.creator = currentUsername

        val success = this.mcpServerMapper.insert(mcpServer) > 0
        log.info("MCP server creation {}, id: {}", if (success) "successful" else "failed", mcpServer.id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateMcpServer(id: Long, request: McpServerUpdateRequest): Boolean {
        log.info("Updating MCP server, id: {}", id)

        val mcpServer = mcpServerMapper.selectById(id)
            ?: throw BizException("MCP server not found")

        // If name is modified, validate uniqueness
        if (request.name != mcpServer.name) {
            val existing = mcpServerMapper.selectByName(request.name)
            if (existing != null) {
                throw BizException("MCP name already exists")
            }
            mcpServer.name = request.name
        }

        // Selectively update fields
        request.description.let { mcpServer.description = it }
        if (hasText(request.type)) {
            mcpServer.type = request.type
        }
        request.command.let { mcpServer.command = it }
        request.url.let { mcpServer.url = it }
        request.isPublic.let { mcpServer.isPublic = it }

        // Update headers (McpConfigEntry) and envParams (ToolEnvParamEntry) with encryption
        if (request.headers != null) {
            mcpServer.headers = secretFieldEncryptor.serializeWithEncryption(request.headers)
        }
        if (request.envParams != null) {
            mcpServer.envParams = secretFieldEncryptor.serializeToolEnvParams(request.envParams)
        }

        // Validate type and field linkage logic after update
        validateTypeAndFields(mcpServer.type, mcpServer.command, mcpServer.url)

        val success = this.mcpServerMapper.updateById(mcpServer) > 0
        log.info("MCP server update {}, id: {}", if (success) "successful" else "failed", id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun toggleMcpServerStatus(id: Long, status: Int): Boolean {
        log.info("Toggling MCP server status, id: {}, status: {}", id, status)

        val mcpServer = mcpServerMapper.selectById(id)
            ?: throw BizException("MCP server not found")

        val success = mcpServerMapper.updateStatus(id, status) > 0
        log.info("MCP server status toggle {}, id: {}, status: {}", if (success) "successful" else "failed", id, status)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun deleteMcpServer(id: Long): Boolean {
        log.info("Deleting MCP server, id: {}", id)

        val mcpServer = mcpServerMapper.selectById(id)
            ?: throw BizException("MCP server not found")

        val success = mcpServerMapper.deleteById(id) > 0
        log.info("MCP server deletion {}, id: {}", if (success) "successful" else "failed", id)
        return success
    }

    override fun connectivityTest(id: Long): Boolean {
        log.info("MCP server connectivity test, id: {}", id)
        listTools(id)
        return true
    }

    /**
     * Validate type and command/url field linkage logic
     *
     * @param type MCP type
     * @param command Execution command
     * @param url Server URL
     */
    private fun validateTypeAndFields(type: String?, command: String?, url: String?) {
        when (type) {
            "stdio" -> {
                if (!hasText(command)) {
                    throw BizException("MCP server of stdio type, command cannot be empty")
                }
            }

            "sse", "streamablehttp" -> {
                if (!hasText(url)) {
                    throw BizException("MCP server of $type type, url cannot be empty")
                }
            }

            else -> {
                throw BizException("Unsupported MCP type: $type, only supports stdio/sse/streamablehttp")
            }
        }
    }

    override fun convertToResponse(mcpServer: McpServer): McpServerResponse = McpServerResponse.fromEntity(mcpServer, objectMapper, secretFieldEncryptor)

    override fun listTools(mcpId: Long): List<McpSchema.Tool> {
        val mcpServer = getMcpServer(mcpId) ?: throw BizException("MCP server not found")
        return McpHelper.listTools(mcpServer, secretFieldEncryptor::decryptToMap, secretFieldEncryptor::decryptToolEnvParamsToMap)
    }
}

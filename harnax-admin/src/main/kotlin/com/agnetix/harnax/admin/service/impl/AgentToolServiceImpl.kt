package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.AgentToolCreateRequest
import com.agnetix.harnax.admin.dto.AgentToolResponse
import com.agnetix.harnax.admin.dto.AgentToolUpdateRequest
import com.agnetix.harnax.admin.dto.McpConfigEntry
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.service.AgentToolService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.SecretFieldEncryptor
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.entity.AgentTool
import com.agnetix.harnax.mapper.AgentToolMapper
import com.github.pagehelper.PageHelper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime

@Service
class AgentToolServiceImpl(
    private val agentToolMapper: AgentToolMapper,
    private val jwtUtil: JwtUtil,
    private val objectMapper: ObjectMapper,
    private val secretFieldEncryptor: SecretFieldEncryptor,
) : AgentToolService {

    private val log = LoggerFactory.getLogger(AgentToolServiceImpl::class.java)

    override fun page(keyword: String?, status: Int?, type: String?, pageNum: Int, pageSize: Int): Page<AgentTool> {
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        PageHelper.startPage<AgentTool>(pageNum, pageSize)
        return Page.fromPageInfo(agentToolMapper.selectAgentToolList(keyword, status, type, currentUsername))
    }

    override fun getAgentTool(id: Long): AgentTool? = agentToolMapper.selectById(id)

    @Transactional(rollbackFor = [Exception::class])
    override fun createAgentTool(request: AgentToolCreateRequest): Boolean = try {
        val agentTool = AgentTool()
        agentTool.name = request.name!!
        agentTool.displayName = request.displayName
        agentTool.description = request.description ?: ""
        agentTool.type = request.type
        agentTool.beanName = request.beanName
        agentTool.httpUrl = request.httpUrl
        agentTool.httpMethod = request.httpMethod ?: "POST"
        agentTool.inputSchema = request.inputSchema
        agentTool.outputSchema = request.outputSchema
        agentTool.readOnly = request.readOnly ?: 0
        agentTool.needConfirm = request.needConfirm ?: 0
        agentTool.timeoutSeconds = request.timeoutSeconds ?: 30
        agentTool.status = 1

        agentTool.tenantId = TenantContext.getTenantId() ?: 1
        agentTool.creator = UserContextUtil.getCurrentUsername(jwtUtil) ?: ""

        if (request.httpHeaders != null) {
            agentTool.httpHeaders = serializeHeaders(request.httpHeaders)
        }

        agentTool.createTime = LocalDateTime.now()
        agentTool.updateTime = LocalDateTime.now()
        agentToolMapper.insert(agentTool)
        true
    } catch (e: Exception) {
        log.error("Failed to create agent tool", e)
        throw RuntimeException("Failed to create agent tool: ${e.message}")
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateAgentTool(id: Long, request: AgentToolUpdateRequest): Boolean = try {
        val agentTool = getAgentTool(id)
            ?: throw RuntimeException("Agent tool not found")

        request.name?.let { agentTool.name = it }
        request.displayName?.let { agentTool.displayName = it }
        request.description?.let { agentTool.description = it }
        request.type?.let { agentTool.type = it }
        request.beanName?.let { agentTool.beanName = it }
        request.httpUrl?.let { agentTool.httpUrl = it }
        request.httpMethod?.let { agentTool.httpMethod = it }
        request.inputSchema?.let { agentTool.inputSchema = it }
        request.outputSchema?.let { agentTool.outputSchema = it }
        request.readOnly?.let { agentTool.readOnly = it }
        request.needConfirm?.let { agentTool.needConfirm = it }
        request.timeoutSeconds?.let { agentTool.timeoutSeconds = it }

        if (request.httpHeaders != null) {
            agentTool.httpHeaders = serializeHeaders(request.httpHeaders)
        }

        agentTool.updateTime = LocalDateTime.now()
        agentToolMapper.updateById(agentTool)
        true
    } catch (e: Exception) {
        log.error("Failed to update agent tool", e)
        throw RuntimeException("Failed to update agent tool: ${e.message}")
    }

    override fun toggleAgentToolStatus(id: Long, status: Int): Boolean {
        agentToolMapper.selectById(id) ?: throw RuntimeException("Agent tool not found")
        return agentToolMapper.updateStatus(id, status) > 0
    }

    override fun deleteAgentTool(id: Long): Boolean = agentToolMapper.deleteById(id) > 0

    override fun convertToResponse(agentTool: AgentTool): AgentToolResponse {
        val headers = deserializeHeaders(agentTool.httpHeaders)
        return AgentToolResponse(
            id = agentTool.id,
            name = agentTool.name,
            displayName = agentTool.displayName,
            description = agentTool.description,
            type = agentTool.type,
            beanName = agentTool.beanName,
            httpUrl = agentTool.httpUrl,
            httpMethod = agentTool.httpMethod,
            httpHeaders = headers,
            inputSchema = agentTool.inputSchema,
            outputSchema = agentTool.outputSchema,
            readOnly = agentTool.readOnly,
            needConfirm = agentTool.needConfirm,
            timeoutSeconds = agentTool.timeoutSeconds,
            status = agentTool.status,
            creator = agentTool.creator,
            createTime = agentTool.createTime,
            updateTime = agentTool.updateTime,
        )
    }

    override fun getAvailableTools(): List<AgentTool> = agentToolMapper.selectAllEnabled()

    private fun serializeHeaders(headers: List<McpConfigEntry>): String = secretFieldEncryptor.serializeWithEncryption(headers) ?: "[]"

    private fun deserializeHeaders(json: String?): List<McpConfigEntry>? {
        if (json.isNullOrBlank()) return null
        return try {
            val entries = objectMapper.readValue(
                json,
                objectMapper.typeFactory.constructCollectionType(List::class.java, McpConfigEntry::class.java),
            ) as List<McpConfigEntry>
            entries.map { entry ->
                if (entry.secret) {
                    entry.copy(value = maskValue(entry.value))
                } else {
                    entry
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to deserialize http headers", e)
            null
        }
    }

    private fun maskValue(encryptedValue: String): String = try {
        val plain = secretFieldEncryptor.decrypt(encryptedValue)
        if (plain.length <= 7) "******" else "${plain.take(3)}****${plain.takeLast(4)}"
    } catch (e: Exception) {
        "******"
    }
}

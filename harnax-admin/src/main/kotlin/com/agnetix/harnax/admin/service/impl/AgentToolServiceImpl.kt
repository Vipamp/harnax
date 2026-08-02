package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.AgentToolCreateRequest
import com.agnetix.harnax.admin.dto.AgentToolResponse
import com.agnetix.harnax.admin.dto.AgentToolUpdateRequest
import com.agnetix.harnax.admin.dto.McpConfigEntry
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.ToolEnvParamEntry
import com.agnetix.harnax.admin.service.AgentToolService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.SecretFieldEncryptor
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.entity.AgentTool
import com.agnetix.harnax.entity.AgentToolEnvParam
import com.agnetix.harnax.mapper.AgentToolEnvParamMapper
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
    private val agentToolEnvParamMapper: AgentToolEnvParamMapper,
    private val jwtUtil: JwtUtil,
    private val objectMapper: ObjectMapper,
    private val secretFieldEncryptor: SecretFieldEncryptor,
) : AgentToolService {

    private val log = LoggerFactory.getLogger(AgentToolServiceImpl::class.java)

    override fun page(keyword: String?, status: Int?, type: String?, pageNum: Int, pageSize: Int): Page<AgentTool> {
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        val safePageNum = pageNum.coerceAtLeast(1)
        val safePageSize = pageSize.coerceIn(1, 1000)
        PageHelper.startPage<AgentTool>(safePageNum, safePageSize)
        return Page.fromPageInfo(agentToolMapper.selectAgentToolList(keyword, status, type, currentUsername))
    }

    override fun getAgentTool(id: Long): AgentTool? = agentToolMapper.selectById(id)

    @Transactional(rollbackFor = [Exception::class])
    override fun createAgentTool(request: AgentToolCreateRequest): Boolean = try {
        val agentTool = AgentTool()
        agentTool.name = request.name!!
        agentTool.displayName = request.displayName
        agentTool.displayNameZh = request.displayNameZh
        agentTool.description = request.description ?: ""
        agentTool.type = request.type
        agentTool.beanName = request.beanName
        agentTool.methodName = request.methodName
        agentTool.httpUrl = request.httpUrl
        agentTool.httpMethod = request.httpMethod ?: "POST"
        agentTool.inputSchema = request.inputSchema
        agentTool.outputSchema = request.outputSchema
        agentTool.readOnly = if (request.readOnly == true) 1 else 0
        agentTool.needConfirm = if (request.needConfirm == true) 1 else 0
        agentTool.timeoutSeconds = request.timeoutSeconds ?: 30
        agentTool.status = request.status ?: 1

        // Handle requiredEnvParamKeys: List -> JSON string
        if (!request.requiredEnvParamKeys.isNullOrEmpty()) {
            agentTool.requiredEnvParamKeys = objectMapper.writeValueAsString(request.requiredEnvParamKeys)
        }

        agentTool.tenantId = TenantContext.getTenantId() ?: 1
        agentTool.creator = UserContextUtil.getCurrentUsername(jwtUtil) ?: ""

        if (request.httpHeaders != null) {
            agentTool.httpHeaders = serializeHeaders(request.httpHeaders)
        }

        agentTool.createTime = LocalDateTime.now()
        agentTool.updateTime = LocalDateTime.now()
        agentToolMapper.insert(agentTool)

        // Insert env param entries into agent_tool_env_param table
        if (!request.envParams.isNullOrEmpty()) {
            saveToolEnvParams(agentTool.id, request.envParams)
        }
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
        request.displayNameZh?.let { agentTool.displayNameZh = it }
        request.description?.let { agentTool.description = it }
        request.type?.let { agentTool.type = it }
        request.beanName?.let { agentTool.beanName = it }
        request.methodName?.let { agentTool.methodName = it }
        request.httpUrl?.let { agentTool.httpUrl = it }
        request.httpMethod?.let { agentTool.httpMethod = it }
        request.inputSchema?.let { agentTool.inputSchema = it }
        request.outputSchema?.let { agentTool.outputSchema = it }
        request.readOnly?.let { agentTool.readOnly = if (it) 1 else 0 }
        request.needConfirm?.let { agentTool.needConfirm = if (it) 1 else 0 }
        request.timeoutSeconds?.let { agentTool.timeoutSeconds = it }

        // Handle requiredEnvParamKeys: List -> JSON string
        if (request.requiredEnvParamKeys != null) {
            agentTool.requiredEnvParamKeys = objectMapper.writeValueAsString(request.requiredEnvParamKeys)
        }

        if (request.httpHeaders != null) {
            agentTool.httpHeaders = serializeHeaders(request.httpHeaders)
        }

        agentTool.updateTime = LocalDateTime.now()
        agentToolMapper.updateById(agentTool)

        // Replace env param entries: delete old, insert new
        if (request.envParams != null) {
            agentToolEnvParamMapper.deleteByToolId(id)
            if (request.envParams.isNotEmpty()) {
                saveToolEnvParams(id, request.envParams)
            }
        }
        true
    } catch (e: Exception) {
        log.error("Failed to update agent tool", e)
        throw RuntimeException("Failed to update agent tool: ${e.message}")
    }

    override fun toggleAgentToolStatus(id: Long, status: Int): Boolean {
        agentToolMapper.selectById(id) ?: throw RuntimeException("Agent tool not found")
        return agentToolMapper.updateStatus(id, status) > 0
    }

    override fun deleteAgentTool(id: Long): Boolean {
        agentToolEnvParamMapper.deleteByToolId(id)
        return agentToolMapper.deleteById(id) > 0
    }

    override fun convertToResponse(agentTool: AgentTool): AgentToolResponse {
        val headers = deserializeHeaders(agentTool.httpHeaders)
        val envParams = loadToolEnvParams(agentTool.id)
        val requiredEnvParamKeys = parseRequiredEnvParamKeys(agentTool.requiredEnvParamKeys)
        return AgentToolResponse(
            id = agentTool.id,
            name = agentTool.name,
            displayName = agentTool.displayName,
            displayNameZh = agentTool.displayNameZh,
            description = agentTool.description,
            type = agentTool.type,
            beanName = agentTool.beanName,
            methodName = agentTool.methodName,
            httpUrl = agentTool.httpUrl,
            httpMethod = agentTool.httpMethod,
            httpHeaders = headers,
            envParams = envParams,
            inputSchema = agentTool.inputSchema,
            outputSchema = agentTool.outputSchema,
            readOnly = agentTool.readOnly,
            needConfirm = agentTool.needConfirm,
            isRequired = agentTool.isRequired,
            requiredEnvParamKeys = requiredEnvParamKeys,
            timeoutSeconds = agentTool.timeoutSeconds,
            status = agentTool.status,
            creator = agentTool.creator,
            createTime = agentTool.createTime,
            updateTime = agentTool.updateTime,
        )
    }

    override fun getAvailableTools(): List<AgentTool> = agentToolMapper.selectAllEnabled()

    override fun getBuiltinTools(): List<AgentTool> = agentToolMapper.selectBuiltinToolList()

    override fun getAvailableToolsByType(type: String?): List<AgentTool> = agentToolMapper.selectAvailableToolsByType(type)

    override fun getRequiredEnvParamKeys(id: Long): List<String> {
        val tool = agentToolMapper.selectById(id) ?: throw RuntimeException("Agent tool not found")
        return parseRequiredEnvParamKeys(tool.requiredEnvParamKeys) ?: emptyList()
    }

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

    private fun parseRequiredEnvParamKeys(json: String?): List<String>? {
        if (json.isNullOrBlank()) return null
        return try {
            objectMapper.readValue(
                json,
                objectMapper.typeFactory.constructCollectionType(List::class.java, String::class.java),
            )
        } catch (e: Exception) {
            log.warn("Failed to parse requiredEnvParamKeys", e)
            null
        }
    }

    private fun maskValue(encryptedValue: String): String = try {
        val plain = secretFieldEncryptor.decrypt(encryptedValue)
        if (plain.length <= 7) "******" else "${plain.take(3)}****${plain.takeLast(4)}"
    } catch (e: Exception) {
        "******"
    }

    /**
     * Save tool env param entries to agent_tool_env_param table.
     * Encrypts defaultValue when secret=true.
     */
    private fun saveToolEnvParams(toolId: Long, envParamEntries: List<ToolEnvParamEntry>) {
        val now = LocalDateTime.now()
        val entities = envParamEntries.map { entry ->
            AgentToolEnvParam().apply {
                this.toolId = toolId
                this.envParamName = entry.envParamName
                this.description = entry.description
                this.required = if (entry.required) 1 else 0
                this.secret = if (entry.secret) 1 else 0
                this.defaultValue = if (entry.secret && !entry.defaultValue.isNullOrBlank()) {
                    secretFieldEncryptor.encrypt(entry.defaultValue)
                } else {
                    entry.defaultValue
                }
                this.createTime = now
                this.updateTime = now
            }
        }
        if (entities.isNotEmpty()) {
            agentToolEnvParamMapper.batchInsert(entities)
        }
    }

    /**
     * Load tool env param entries from agent_tool_env_param table.
     * Masks defaultValue for secret entries.
     */
    private fun loadToolEnvParams(toolId: Long): List<ToolEnvParamEntry> {
        val envEntities = agentToolEnvParamMapper.selectByToolId(toolId)
        return envEntities.map { entity ->
            val maskedDefault = if (entity.secret == 1 && !entity.defaultValue.isNullOrBlank()) {
                try {
                    maskValue(entity.defaultValue!!)
                } catch (e: Exception) {
                    "******"
                }
            } else {
                entity.defaultValue
            }
            ToolEnvParamEntry(
                id = entity.id,
                envParamName = entity.envParamName,
                description = entity.description,
                required = entity.required == 1,
                secret = entity.secret == 1,
                defaultValue = maskedDefault,
            )
        }
    }
}

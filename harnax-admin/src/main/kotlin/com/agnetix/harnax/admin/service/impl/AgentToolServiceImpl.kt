package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.AgentToolCreateRequest
import com.agnetix.harnax.admin.dto.AgentToolResponse
import com.agnetix.harnax.admin.dto.AgentToolUpdateRequest
import com.agnetix.harnax.admin.dto.McpConfigEntry
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.ToolEnvParamEntry
import com.agnetix.harnax.admin.exception.BizException
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
    override fun createAgentTool(request: AgentToolCreateRequest): Boolean {
        // Outside the try block: a rejected request must surface its own message, not
        // "Failed to create agent tool: ..."
        requireManageableTool(request.type, "Creating a builtin tool through this API")
        return try {
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
        } catch (e: BizException) {
            // "this masked secret cannot be kept, re-enter it" has to reach the page as itself
            throw e
        } catch (e: Exception) {
            log.error("Failed to create agent tool", e)
            throw RuntimeException("Failed to create agent tool: ${e.message}")
        }
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateAgentTool(id: Long, request: AgentToolUpdateRequest): Boolean {
        val existing = getAgentTool(id) ?: throw RuntimeException("Agent tool not found")
        // The row's own type and the requested type are both checked: re-typing a custom tool
        // to BUILTIN would hand a user-owned row to the code sync, and editing a builtin row
        // would be overwritten (or pruned) at the next startup.
        requireManageableTool(existing.type, "Updating builtin tool ${existing.name}")
        requireManageableTool(request.type, "Updating tool ${existing.name} to builtin type")
        return try {
            request.name?.let { existing.name = it }
            request.displayName?.let { existing.displayName = it }
            request.displayNameZh?.let { existing.displayNameZh = it }
            request.description?.let { existing.description = it }
            request.type?.let { existing.type = it }
            request.beanName?.let { existing.beanName = it }
            request.methodName?.let { existing.methodName = it }
            request.httpUrl?.let { existing.httpUrl = it }
            request.httpMethod?.let { existing.httpMethod = it }
            request.inputSchema?.let { existing.inputSchema = it }
            request.outputSchema?.let { existing.outputSchema = it }
            request.readOnly?.let { existing.readOnly = if (it) 1 else 0 }
            request.needConfirm?.let { existing.needConfirm = if (it) 1 else 0 }
            request.timeoutSeconds?.let { existing.timeoutSeconds = it }

            // Handle requiredEnvParamKeys: List -> JSON string
            if (request.requiredEnvParamKeys != null) {
                existing.requiredEnvParamKeys = objectMapper.writeValueAsString(request.requiredEnvParamKeys)
            }

            if (request.httpHeaders != null) {
                existing.httpHeaders = serializeHeaders(request.httpHeaders, existing.httpHeaders)
            }

            existing.updateTime = LocalDateTime.now()
            agentToolMapper.updateById(existing)

            // Replace env param entries: delete old, insert new
            if (request.envParams != null) {
                // The rows are about to go away, and with them the only copy of what a masked
                // defaultValue stands for.
                val storedSecrets = storedEnvSecrets(id)
                agentToolEnvParamMapper.deleteByToolId(id)
                if (request.envParams.isNotEmpty()) {
                    saveToolEnvParams(id, request.envParams, storedSecrets)
                }
            }
            true
        } catch (e: BizException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to update agent tool", e)
            throw RuntimeException("Failed to update agent tool: ${e.message}")
        }
    }

    override fun toggleAgentToolStatus(id: Long, status: Int): Boolean {
        val agentTool = agentToolMapper.selectById(id) ?: throw RuntimeException("Agent tool not found")
        requireManageableTool(agentTool.type, "Changing status of builtin tool ${agentTool.name}")
        return agentToolMapper.updateStatus(id, status) > 0
    }

    override fun deleteAgentTool(id: Long): Boolean {
        val agentTool = agentToolMapper.selectById(id) ?: throw RuntimeException("Agent tool not found")
        requireManageableTool(agentTool.type, "Deleting builtin tool ${agentTool.name}")
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

    private fun requireManageableTool(type: String?, action: String) {
        if (type.equals(TOOL_TYPE_BUILTIN, ignoreCase = true)) {
            throw BizException("Builtin tools are owned by the code sync (BuiltinToolAutoRegistrar): $action is not allowed")
        }
    }

    private fun serializeHeaders(headers: List<McpConfigEntry>, storedJson: String? = null): String = secretFieldEncryptor.serializeWithEncryption(headers, storedJson) ?: "[]"

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
     *
     * [storedSecrets] is the ciphertext per parameter name that this tool had before the rows were
     * replaced; a masked incoming value means "unchanged" and carries that ciphertext over.
     */
    private fun saveToolEnvParams(
        toolId: Long,
        envParamEntries: List<ToolEnvParamEntry>,
        storedSecrets: Map<String, String> = emptyMap(),
    ) {
        val now = LocalDateTime.now()
        val entities = envParamEntries.map { entry ->
            AgentToolEnvParam().apply {
                this.toolId = toolId
                this.envParamName = entry.envParamName
                this.description = entry.description
                this.required = if (entry.required) 1 else 0
                this.secret = if (entry.secret) 1 else 0
                this.defaultValue = secretFieldEncryptor.resolveEnvParamValue(entry, storedSecrets)
                this.createTime = now
                this.updateTime = now
            }
        }
        if (entities.isNotEmpty()) {
            agentToolEnvParamMapper.batchInsert(entities)
        }
    }

    /**
     * Raw stored ciphertexts by parameter name. This reads the table itself rather than
     * [loadToolEnvParams], which hands out the masked view the write-back has to be defended against.
     */
    private fun storedEnvSecrets(toolId: Long): Map<String, String> = agentToolEnvParamMapper.selectByToolId(toolId)
        .filter { it.secret == 1 && !it.defaultValue.isNullOrBlank() }
        .associate { it.envParamName to it.defaultValue!! }

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

    private companion object {
        const val TOOL_TYPE_BUILTIN = "BUILTIN"
    }
}

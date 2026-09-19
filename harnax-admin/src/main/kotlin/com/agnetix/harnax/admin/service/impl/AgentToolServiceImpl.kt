package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.AgentToolResponse
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.ToolEnvParamEntry
import com.agnetix.harnax.admin.service.AgentToolService
import com.agnetix.harnax.admin.util.SecretFieldEncryptor
import com.agnetix.harnax.entity.AgentTool
import com.agnetix.harnax.mapper.AgentToolEnvParamMapper
import com.agnetix.harnax.mapper.AgentToolMapper
import com.github.pagehelper.PageHelper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
class AgentToolServiceImpl(
    private val agentToolMapper: AgentToolMapper,
    private val agentToolEnvParamMapper: AgentToolEnvParamMapper,
    private val objectMapper: ObjectMapper,
    private val secretFieldEncryptor: SecretFieldEncryptor,
) : AgentToolService {

    private val log = LoggerFactory.getLogger(AgentToolServiceImpl::class.java)

    override fun page(keyword: String?, status: Int?, pageNum: Int, pageSize: Int): Page<AgentTool> {
        val safePageNum = pageNum.coerceAtLeast(1)
        val safePageSize = pageSize.coerceIn(1, 1000)
        PageHelper.startPage<AgentTool>(safePageNum, safePageSize)
        return Page.fromPageInfo(agentToolMapper.selectAgentToolList(keyword, status))
    }

    override fun getAgentTool(id: Long): AgentTool? = agentToolMapper.selectById(id)

    override fun convertToResponse(agentTool: AgentTool): AgentToolResponse {
        val envParams = loadToolEnvParams(agentTool.id)
        val requiredEnvParamKeys = parseRequiredEnvParamKeys(agentTool.requiredEnvParamKeys)
        return AgentToolResponse(
            id = agentTool.id,
            name = agentTool.name,
            displayName = agentTool.displayName,
            displayNameZh = agentTool.displayNameZh,
            description = agentTool.description,
            beanName = agentTool.beanName,
            methodName = agentTool.methodName,
            envParams = envParams,
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

    override fun getAvailableTools(): List<AgentTool> = agentToolMapper.selectAvailableTools()

    override fun getBuiltinTools(): List<AgentTool> = agentToolMapper.selectBuiltinToolList()

    override fun getRequiredEnvParamKeys(id: Long): List<String> {
        val tool = agentToolMapper.selectById(id) ?: throw RuntimeException("Agent tool not found")
        return parseRequiredEnvParamKeys(tool.requiredEnvParamKeys) ?: emptyList()
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

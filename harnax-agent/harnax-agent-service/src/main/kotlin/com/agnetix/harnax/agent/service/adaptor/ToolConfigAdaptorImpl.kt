package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.agent.service.client.AgentSpecContextHolder
import com.agnetix.harnax.entity.AgentTool
import com.agnetix.harnax.entity.dto.ToolDetailDto
import com.agnetix.harnax.mapper.AgentToolMapper
import com.agnetix.harnax.tools.sdk.adaptor.ToolConfigAdaptor
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * ToolConfigAdaptor Implementation.
 *
 * **Primary path**: reads tool config from [AgentSpecContextHolder] (populated by admin API).
 * **Fallback**: queries DB directly via [AgentToolMapper].
 */
@Component
class ToolConfigAdaptorImpl(
    private val specContextHolder: AgentSpecContextHolder,
    private val agentToolMapper: AgentToolMapper,
) : ToolConfigAdaptor {

    private val log = LoggerFactory.getLogger(ToolConfigAdaptorImpl::class.java)

    override fun getToolConfig(toolId: Long): AgentTool? {
        if (toolId <= 0) {
            log.warn("Invalid toolId: $toolId")
            return null
        }

        // Primary: read from context (admin pre-resolved)
        val toolDetails = specContextHolder.get()?.toolDetails
        if (!toolDetails.isNullOrEmpty()) {
            val dto = toolDetails.find { it.id == toolId }
            if (dto != null) {
                log.debug("Tool config loaded from context: toolId={}, name={}", toolId, dto.name)
                return dtoToEntity(dto)
            }
        }

        // Fallback: direct DB query
        log.debug("Tool config fallback to DB: toolId={}", toolId)
        val agentTool = agentToolMapper.selectById(toolId)
        if (agentTool == null) {
            log.warn("AgentTool not found: $toolId")
            return null
        }
        return agentTool
    }

    /**
     * Convert ToolDetailDto to AgentTool entity for backward compatibility.
     */
    private fun dtoToEntity(dto: ToolDetailDto): AgentTool {
        val entity = AgentTool()
        entity.id = dto.id
        entity.name = dto.name
        entity.displayName = dto.displayName
        entity.displayNameZh = dto.displayNameZh
        entity.description = dto.description
        entity.type = dto.type
        entity.beanName = dto.beanName
        entity.methodName = dto.methodName
        entity.httpUrl = dto.httpUrl
        entity.httpMethod = dto.httpMethod
        entity.httpHeaders = dto.httpHeaders
        entity.envParams = dto.envParams
        entity.inputSchema = dto.inputSchema
        entity.outputSchema = dto.outputSchema
        entity.readOnly = dto.readOnly
        entity.needConfirm = dto.needConfirm
        entity.requiredEnvParamKeys = dto.requiredEnvParamKeys
        entity.timeoutSeconds = dto.timeoutSeconds
        return entity
    }
}

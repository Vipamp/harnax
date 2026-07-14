package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.agent.adaptor.McpConfigAdaptor
import com.agnetix.harnax.agent.adaptor.mcp.*
import com.agnetix.harnax.agent.service.client.AgentSpecContextHolder
import com.agnetix.harnax.entity.McpServer
import com.agnetix.harnax.entity.dto.McpDetailDto
import com.agnetix.harnax.mapper.McpServerMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * McpConfigAdaptor Implementation.
 *
 * **Primary path**: reads MCP config from [AgentSpecContextHolder] (populated by admin API).
 * **Fallback**: queries DB directly via [McpServerMapper].
 */
@Component
class McpConfigAdaptorImpl(
    private val specContextHolder: AgentSpecContextHolder,
    private val mcpServerMapper: McpServerMapper,
) : McpConfigAdaptor {

    private val log = LoggerFactory.getLogger(McpConfigAdaptorImpl::class.java)

    override fun getConfig(mcpId: Long): McpServer? {
        if (mcpId <= 0) {
            log.warn("Invalid mcpId: $mcpId")
            return null
        }

        // Primary: read from context (admin pre-resolved)
        val mcpDetails = specContextHolder.get()?.mcpDetails
        if (!mcpDetails.isNullOrEmpty()) {
            val dto = mcpDetails.find { it.id == mcpId }
            if (dto != null) {
                log.debug("MCP config loaded from context: mcpId={}, name={}", mcpId, dto.name)
                return dtoToEntity(dto)
            }
        }

        // Fallback: direct DB query
        log.debug("MCP config fallback to DB: mcpId={}", mcpId)
        val mcpServer = mcpServerMapper.selectById(mcpId)
        if (mcpServer == null) {
            log.warn("McpServer not found: $mcpId")
            return null
        }
        return mcpServer
    }

    /**
     * Convert McpDetailDto to McpServer entity for backward compatibility.
     */
    private fun dtoToEntity(dto: McpDetailDto): McpServer {
        val entity = McpServer()
        entity.id = dto.id
        entity.name = dto.name
        entity.description = dto.description
        entity.type = dto.type
        entity.command = dto.command
        entity.url = dto.url
        entity.headers = dto.headers
        entity.envParams = dto.envParams
        return entity
    }
}

package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.agent.adaptor.McpConfigAdaptor
import com.agnetix.harnax.agent.adaptor.mcp.*
import com.agnetix.harnax.agent.service.client.AgentSpecContextHolder
import com.agnetix.harnax.entity.McpServer
import com.agnetix.harnax.entity.dto.McpDetailDto
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * McpConfigAdaptor Implementation.
 *
 * Reads MCP config from [AgentSpecContextHolder], the one place it can be read from: Admin delivers
 * `headers` / `envParams` decrypted on its way out because this service holds no AES key, so a row read
 * straight from the database would be fed to the client as ciphertext that no one here can open.
 */
@Component
class McpConfigAdaptorImpl(
    private val specContextHolder: AgentSpecContextHolder,
) : McpConfigAdaptor {

    private val log = LoggerFactory.getLogger(McpConfigAdaptorImpl::class.java)

    override fun getConfig(mcpId: Long): McpServer? {
        if (mcpId <= 0) {
            log.warn("Invalid mcpId: $mcpId")
            return null
        }

        val mcpDetails = specContextHolder.get()?.mcpDetails
        val dto = mcpDetails?.find { it.id == mcpId }
        if (dto == null) {
            // Warn, not debug: an agent bound to a server this spec does not carry means the caller
            // stopped filling `mcpDetails`, and the difference is otherwise invisible in the logs.
            log.warn("MCP {} is not in the delivered spec, so its config cannot be resolved here", mcpId)
            return null
        }
        return dtoToEntity(dto)
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
        entity.status = dto.status
        return entity
    }
}

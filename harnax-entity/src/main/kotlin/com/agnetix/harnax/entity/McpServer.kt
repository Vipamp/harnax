package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * MCP server entity
 */
@Schema(description = "MCP server entity")
class McpServer : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    /**
     * MCP ID
     */
    @Schema(description = "MCP ID")
    var id: Long = 0

    /**
     * Tenant ID
     */
    @Schema(description = "Tenant ID")
    var tenantId: Long = 1

    /**
     * MCP name
     */
    @Schema(description = "MCP name")
    var name: String = ""

    /**
     * MCP description
     */
    @Schema(description = "MCP description")
    var description: String = ""

    /**
     * MCP type (stdio/sse/streamablehttp)
     */
    @Schema(description = "MCP type (stdio/sse/streamablehttp)")
    var type: String = "streamablehttp"

    /**
     * Execution command (only for stdio type)
     */
    @Schema(description = "Execution command (only for stdio type)")
    var command: String = ""

    /**
     * Service URL (for sse/streamablehttp type)
     */
    @Schema(description = "Service URL (for sse/streamablehttp type)")
    var url: String = ""

    /**
     * Status (0:disabled, 1:enabled)
     */
    @Schema(description = "Status (0:disabled, 1:enabled)")
    var status: Int = 1

    /**
     * Public status (0:no, 1:yes)
     */
    @Schema(description = "Public status (0:no, 1:yes)")
    var isPublic: Int = 1

    /**
     * Creator
     */
    @Schema(description = "Creator")
    var creator: String = ""

    /**
     * Active status (0:deleted, 1:active)
     */
    @Schema(description = "Active status (0:deleted, 1:active)")
    var active: Int = 1

    /**
     * Creation time
     */
    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()

    /**
     * Update time
     */
    @Schema(description = "Update time")
    var updateTime: LocalDateTime = LocalDateTime.now()

    /**
     * HTTP headers JSON string (for sse/streamablehttp type)
     * Format: [{"key":"Authorization","value":"Bearer xxx","secret":true}]
     */
    @Schema(description = "HTTP headers JSON string")
    var headers: String? = null

    /**
     * Environment variables JSON string (for stdio type)
     * Format: [{"key":"API_KEY","value":"sk-xxx","secret":true}]
     */
    @Schema(description = "Environment variables JSON string")
    var envs: String? = null
}

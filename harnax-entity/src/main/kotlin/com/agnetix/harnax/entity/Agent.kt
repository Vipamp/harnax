package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * Agent entity
 */
@Schema(description = "Agent entity")
class Agent : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    /**
     * ID
     */
    @Schema(description = "ID")
    var id: Long = 0

    /**
     * Tenant ID
     */
    @Schema(description = "Tenant ID")
    var tenantId: Long = 1

    /**
     * Agent name
     */
    @Schema(description = "Agent name")
    var name: String = ""

    /**
     * Agent description
     */
    @Schema(description = "Agent description")
    var description: String = ""

    /**
     * System prompt (Markdown supported)
     */
    @Schema(description = "System prompt (Markdown supported)")
    var systemPrompt: String = ""

    /**
     * Chat model ID
     */
    @Schema(description = "Chat model ID")
    var modelId: Long = 0

    /**
     * MCP service list (JSON format)
     */
    @Schema(description = "MCP service list (JSON format)")
    var mcpList: String = ""

    /**
     * Skill list (JSON format)
     */
    @Schema(description = "Skill list (JSON format)")
    var skillList: String = ""

    /**
     * Tool list (JSON format)
     */
    @Schema(description = "Tool list (JSON format)")
    var toolList: String = ""

    /**
     * Owner
     */
    @Schema(description = "Owner")
    var owner: String = ""

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
}

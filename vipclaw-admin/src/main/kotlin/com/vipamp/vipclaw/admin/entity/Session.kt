package com.vipamp.vipclaw.admin.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

@Schema(description = "Session entity")
class Session : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "ID")
    var id: Long = 0

    @Schema(description = "Tenant ID")
    var tenantId: Long = 1

    @Schema(description = "Session title")
    var title: String = ""

    @Schema(description = "Session description")
    var sessionDescription: String = ""

    @Schema(description = "Session ID")
    var sessionId: String = ""

    @Schema(description = "Associated agent ID")
    var agentId: Long = 0

    @Schema(description = "Agent name")
    var name: String = ""

    @Schema(description = "Agent description")
    var description: String = ""

    @Schema(description = "System prompt (Markdown supported)")
    var systemPrompt: String = ""

    @Schema(description = "Chat model ID")
    var modelId: Long = 0

    @Schema(description = "Deep thinking enabled (0:no, 1:yes)")
    var enableThink: Int = 0

    @Schema(description = "Internet search enabled (0:no, 1:yes)")
    var enableSearch: Int = 0

    @Schema(description = "Planning enabled (0:no, 1:yes)")
    var enablePlan: Int = 0

    @Schema(description = "MCP service list (JSON format)")
    var mcpList: String = "[]"

    @Schema(description = "Skill list (JSON format)")
    var skillList: String = "[]"

    @Schema(description = "Owner")
    var owner: String = ""

    @Schema(description = "Status (0:disabled, 1:enabled)")
    var status: Int = 1

    @Schema(description = "Public status (0:no, 1:yes)")
    var isPublic: Int = 1

    @Schema(description = "Creator")
    var creator: String = ""

    @Schema(description = "Active status (0:deleted, 1:active)")
    var active: Int = 1

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Update time")
    var updateTime: LocalDateTime = LocalDateTime.now()
}

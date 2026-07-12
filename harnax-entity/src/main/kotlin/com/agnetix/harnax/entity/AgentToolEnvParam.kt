package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

@Schema(description = "Agent Tool Environment Parameter entity")
class AgentToolEnvParam : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "Env param entry ID")
    var id: Long = 0

    @Schema(description = "FK to agent_tool.id")
    var toolId: Long = 0

    @Schema(description = "Environment parameter name, e.g. API_KEY")
    var envParamName: String = ""

    @Schema(description = "Human-readable description shown in Admin UI")
    var description: String? = null

    @Schema(description = "Is required (0: No, 1: Yes)")
    var required: Int = 0

    @Schema(description = "Is sensitive (0: No, 1: Yes)")
    var secret: Int = 0

    @Schema(description = "Default value")
    var defaultValue: String? = null

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Update time")
    var updateTime: LocalDateTime = LocalDateTime.now()
}

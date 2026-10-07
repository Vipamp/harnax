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
     * Skill self-write status (0:no, 1:yes)
     *
     * The grant the runtime reads at assembly time to decide whether to install the skill-authoring tools
     * at all. They have to be refused there rather than filtered afterwards: the framework registers them
     * into the toolkit directly, past harnax's tool configuration filter.
     */
    @Schema(description = "Skill self-write status (0:no, 1:yes)")
    var skillSelfWrite: Int = 0

    /**
     * Whether this agent has long-term memory (0: no, 1: yes). On by default: the wizard is what answers
     * "no" for one agent, and a row written before the switch existed has to keep the memory it had.
     */
    var memoryEnabled: Int = 1

    /**
     * Whether this agent also keeps a memory layer scoped to one conversation (0: no, 1: yes). Off by
     * default: two layers is what the wizard turns on per agent, and every row written before the switch
     * existed has to keep today's single long-term layer.
     */
    var sessionMemoryEnabled: Int = 0

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

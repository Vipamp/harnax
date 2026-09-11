package com.agnetix.harnax.agent

import com.agnetix.harnax.tools.sdk.ToolSpec

/**
 * Agent specification for harness-based agents.
 *
 * Key changes in agentscope 2.0.0:
 * - `StructuredOutputReminder` removed (model layer handles natively)
 * - `AutoContextConfig` removed (workspace context is now built-in)
 */
data class AgentSpec(
    val id: Long,
    val name: String,
    val description: String,
    val maxIterNum: Int,
    val systemPrompt: String,

    val chatModelId: Long,
    val enableMetaTool: Boolean?,
    val contextForTools: List<Any>,
    val toolSpecs: List<ToolSpec>,
    val mcpServices: List<McpSpec>,

    val skills: List<SkillSpec>,
    val cliSpecs: List<CliSpec> = emptyList(),
    val planSpec: PlanSpec,
) {

    companion object {
        @JvmStatic
        fun builder() = AgentSpecBuilder()
    }
}

class AgentSpecBuilder {
    private var id: Long = -1
    private var name: String = "AscopeAgent"
    private var description: String = "I am a AI assistant, I can help you to do anything you want."
    private var maxIterNum: Int = 10
    private var systemPrompt: String = "I am a AI assistant, I can help you to do anything you want."

    private var chatModelId: Long = -1
    private var enableMetaTool: Boolean? = null
    private var toolSpecs: MutableList<ToolSpec> = mutableListOf()
    private var contextForTools: MutableList<Any> = mutableListOf()
    private var mcpServices: MutableList<McpSpec> = mutableListOf()

    private var skills: MutableList<SkillSpec> = mutableListOf()
    private var cliSpecs: MutableList<CliSpec> = mutableListOf()
    private var planSpec: PlanSpec = PlanSpec(false)

    fun id(id: Long) = apply { this.id = id }
    fun name(name: String) = apply { this.name = name }
    fun description(description: String) = apply { this.description = description }
    fun maxIterNum(maxIterNum: Int) = apply { this.maxIterNum = maxIterNum }
    fun systemPrompt(systemPrompt: String) = apply { this.systemPrompt = systemPrompt }

    fun chatModelId(chatModelId: Long) = apply { this.chatModelId = chatModelId }
    fun enableMetaTool(enableMetaTool: Boolean?) = apply { this.enableMetaTool = enableMetaTool }
    fun addContextForTool(contextForTool: Any) = apply { this.contextForTools.add(contextForTool) }
    fun addToolSpec(toolSpec: ToolSpec) = apply { this.toolSpecs.add(toolSpec) }
    fun addMcpService(mcpService: McpSpec) = apply { this.mcpServices.add(mcpService) }

    fun addSkill(skill: SkillSpec) = apply { this.skills.add(skill) }
    fun addCliSpec(cliSpec: CliSpec) = apply { this.cliSpecs.add(cliSpec) }
    fun planSpec(planSpec: PlanSpec) = apply { this.planSpec = planSpec }

    fun build(): AgentSpec {
        require(id > 0) { "Agent id must be greater than 0" }
        require(chatModelId > 0) { "Chat model id must be greater than 0" }

        return AgentSpec(
            id = id,
            name = name,
            description = description,
            maxIterNum = maxIterNum,
            systemPrompt = systemPrompt,
            chatModelId = chatModelId,
            enableMetaTool = enableMetaTool,
            contextForTools = contextForTools,
            toolSpecs = toolSpecs,
            mcpServices = mcpServices,
            skills = skills,
            cliSpecs = cliSpecs,
            planSpec = planSpec,
        )
    }
}

data class McpSpec(
    val mcpId: Long,
    val isAsync: Boolean = true,
)

data class SkillSpec(
    val skillId: Long,
    val skillName: String,
    val skipIfMissing: Boolean = true,
)

data class CliSpec(
    val cliId: Long,
    val name: String,
    val version: String = "",
    val installScript: String,
    val checkCommand: String = "",
    val envBindings: Map<String, String> = emptyMap(),
    val skillIds: List<Long> = emptyList(),
)

data class PlanSpec(
    val enablePlan: Boolean,
    val maxSubTask: Int? = null,
    val needUserConfirmed: Boolean? = null,
)

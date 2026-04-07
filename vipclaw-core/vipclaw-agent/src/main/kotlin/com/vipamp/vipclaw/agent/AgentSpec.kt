package com.vipamp.vipclaw.agent

import io.agentscope.core.memory.autocontext.AutoContextConfig
import io.agentscope.core.model.StructuredOutputReminder
import io.agentscope.core.plan.PlanNotebook

/**
 * @Author: heqingsong
 * @Date: 2026/4/1
 * @Description: AgentSpec
 * @Project: vipclaw
 */
data class AgentSpec(
    val name: String,
    val description: String,
    val maxIterNum: Int,
    val systemPrompt: String,
    val reminder: StructuredOutputReminder,
    val chatModelAliseName: String,
    val mcpServices: List<McpSpec> = emptyList(),
    val enableMetaTool: Boolean?,
    val contextForTools: List<Any> = emptyList(),
    val useAutoContextMemory: Boolean = false,
    val autoContextConfig: AutoContextConfig? = null,
    val skills: List<SkillSpec> = emptyList(),
    val enablePlan: Boolean? = false,
    val planNotebook: PlanNotebook? = null
) {
    companion object {
        @JvmStatic
        fun builder() = AgentSpecBuilder()
    }
}

class AgentSpecBuilder {
    private var name: String = "AscopeAgent"
    private var description: String = "I am a AI assistant, I can help you to do anything you want."
    private var maxIterNum: Int = 10
    private var systemPrompt: String = "I am a AI assistant, I can help you to do anything you want."
    private var reminder: StructuredOutputReminder = StructuredOutputReminder.PROMPT
    private var chatModelAliseName: String=""
    private var mcpServices: List<McpSpec> = emptyList()
    private var enableMetaTool: Boolean? = null
    private var contextForTools: List<Any> = emptyList()
    private var useAutoContextMemory: Boolean = false
    private var autoContextConfig: AutoContextConfig? = null
    private var skills: List<SkillSpec> = emptyList()
    private var enablePlan: Boolean? = false
    private var planNotebook: PlanNotebook? = null

    fun name(name: String) = apply { this.name = name }
    fun description(description: String) = apply { this.description = description }
    fun maxIterNum(maxIterNum: Int) = apply { this.maxIterNum = maxIterNum }
    fun systemPrompt(systemPrompt: String) = apply { this.systemPrompt = systemPrompt }
    fun reminder(reminder: StructuredOutputReminder) = apply { this.reminder = reminder }
    fun chatModelAliseName(chatModelAliseName: String) = apply { this.chatModelAliseName = chatModelAliseName }
    fun mcpServices(mcpServices: List<McpSpec>) = apply { this.mcpServices = mcpServices }
    fun enableMetaTool(enableMetaTool: Boolean?) = apply { this.enableMetaTool = enableMetaTool }
    fun contextForTools(contextForTools: List<Any>) = apply { this.contextForTools = contextForTools }
    fun useAutoContextMemory(useAutoContextMemory: Boolean) = apply { this.useAutoContextMemory = useAutoContextMemory }
    fun autoContextConfig(autoContextConfig: AutoContextConfig?) = apply { this.autoContextConfig = autoContextConfig }
    fun skills(skills: List<SkillSpec>) = apply { this.skills = skills }
    fun enablePlan(enablePlan: Boolean?) = apply { this.enablePlan = enablePlan }
    fun planNotebook(planNotebook: PlanNotebook?) = apply { this.planNotebook = planNotebook }

    fun build() = AgentSpec(
        name = name,
        description = description,
        maxIterNum = maxIterNum,
        systemPrompt = systemPrompt,
        reminder = reminder,
        chatModelAliseName = requireNotNull(chatModelAliseName) { "chatModelAliseName must be set" },
        mcpServices = mcpServices,
        enableMetaTool = enableMetaTool,
        contextForTools = contextForTools,
        useAutoContextMemory = useAutoContextMemory,
        autoContextConfig = autoContextConfig,
        skills = skills,
        enablePlan = enablePlan,
        planNotebook = planNotebook
    )
}

data class McpSpec(
    val mcpServiceName: String,
    val isAsync: Boolean = true,
    val skipIfMissing: Boolean = true,
)

data class SkillSpec(
    val repository: String? = null,
    val skillName: String,
    val skipIfMissing: Boolean = true
)

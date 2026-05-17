package com.agnetix.harnax.agent

import com.agnetix.harnax.agent.provider.tool.ToolBox
import io.agentscope.core.ReActAgent
import io.agentscope.core.hook.Hook
import io.agentscope.core.memory.InMemoryMemory
import io.agentscope.core.memory.Memory
import io.agentscope.core.model.ChatModelBase
import io.agentscope.core.model.StructuredOutputReminder
import io.agentscope.core.plan.PlanNotebook
import io.agentscope.core.skill.AgentSkill
import io.agentscope.core.skill.SkillBox
import io.agentscope.core.tool.ToolExecutionContext
import io.agentscope.core.tool.Toolkit
import io.agentscope.core.tool.coding.ShellCommandTool
import io.agentscope.core.tool.mcp.McpClientWrapper
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * @Author: heqingsong
 * @Date: 2026/4/11
 * @Description: AscopeAgentBuilder
 * @Project: harnax
 */
class AscopeAgentBuilder {

    private var builder: ReActAgent.Builder = ReActAgent.builder()
    private var name: String = "AscopeAgent"
    private var description: String = "I am a AI assistant, I can help you to solve so "
    private var maxIters: Int = 10
    private var systemPrompt: String = "I am aAI assistant, I can help you to solve s "
    private lateinit var model: ChatModelBase
    private var reminder: StructuredOutputReminder = StructuredOutputReminder.PROMPT

    private lateinit var agentWorkspace: Path
    private var toolkit: Toolkit = Toolkit()
    private var toolExecutionContext: ToolExecutionContext? = null
    private var enableMetaTool: Boolean = java.lang.Boolean.FALSE
    private val hooks: MutableList<Hook> = mutableListOf()
    private var skillBox: SkillBox? = null
    private var memory: Memory? = null
    private var enablePlan: Boolean? = null
    private var planNotebook: PlanNotebook? = null

    fun name(name: String): AscopeAgentBuilder {
        this.name = name
        return this
    }

    fun description(description: String): AscopeAgentBuilder {
        this.description = description
        return this
    }

    fun maxIters(maxIters: Int): AscopeAgentBuilder {
        this.maxIters = maxIters
        return this
    }

    fun systemPrompt(systemPrompt: String): AscopeAgentBuilder {
        this.systemPrompt = systemPrompt
        return this
    }

    fun reminder(reminder: StructuredOutputReminder): AscopeAgentBuilder {
        this.reminder = reminder
        return this
    }

    fun agentWorkspace(agentWorkspace: Path): AscopeAgentBuilder {
        this.agentWorkspace = agentWorkspace
        return this
    }

    fun model(model: ChatModelBase): AscopeAgentBuilder {
        this.model = model
        return this
    }

    fun addMcp(mcpClient: McpClientWrapper): AscopeAgentBuilder {
        CompletableFuture.runAsync {
            this.toolkit.registerMcpClient(mcpClient).block()
        }.get(1, TimeUnit.MINUTES)
        return this
    }

    fun enableMetaTool(enableMetaTool: Boolean): AscopeAgentBuilder {
        this.enableMetaTool = enableMetaTool
        return this
    }

    fun addTool(vararg toolBoxes: ToolBox): AscopeAgentBuilder {
        toolBoxes.iterator().forEachRemaining { this.toolkit.registerTool(it) }
        return this
    }

    fun addToolContext(toolExecutionContext: ToolExecutionContext): AscopeAgentBuilder {
        this.toolExecutionContext = toolExecutionContext
        return this
    }

    fun addSubAgentAsTool(agent: ReActAgent): AscopeAgentBuilder {
        this.toolkit.registration().subAgent { agent }.apply()
        return this
    }

    fun addSkill(agentSkill: AgentSkill): AscopeAgentBuilder {
        if (this.skillBox == null) {
            this.skillBox = SkillBox(this.toolkit)
            this.skillBox!!.codeExecution()
                .workDir(agentWorkspace.toAbsolutePath().toString())
                .withShell(ShellCommandTool(mutableSetOf<String>("python", "ls", "cat")) { _: String -> true })
                .withRead()
                .withWrite()
                .enable()
        }
        skillBox!!.registerSkill(agentSkill)
        return this
    }

    fun memory(memory: Memory): AscopeAgentBuilder {
        this.memory = memory
        return this
    }

    fun addHook(hook: Hook): AscopeAgentBuilder {
        this.hooks.add(hook)
        return this
    }

    fun enablePlan(enablePlan: Boolean): AscopeAgentBuilder {
        this.enablePlan = enablePlan
        return this
    }

    fun addPlanNotebook(planNotebook: PlanNotebook): AscopeAgentBuilder {
        this.planNotebook = planNotebook
        return this
    }

    fun build(): ReActAgent {
        builder.name(name)
            .description(description)
            .maxIters(maxIters)
            .sysPrompt(systemPrompt)
            .model(model)
            .structuredOutputReminder(reminder)
            .enableMetaTool(enableMetaTool)
            .toolkit(toolkit)
            .memory(if (memory != null) memory else InMemoryMemory())
        hooks.forEach { builder.hook(it) }
        this.toolExecutionContext?.let { builder.toolExecutionContext(it) }
        this.skillBox?.let { builder.skillBox(it) }
        if (enablePlan != null && enablePlan == true) {
            builder.enablePlan()
            planNotebook?.let { builder.planNotebook(it) }
        }
        return builder.build()
    }
}

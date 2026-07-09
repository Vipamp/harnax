package com.agnetix.harnax.harness

import com.agnetix.harnax.tools.sdk.ToolBox
import io.agentscope.core.middleware.MiddlewareBase
import io.agentscope.core.model.ChatModelBase
import io.agentscope.core.skill.AgentSkill
import io.agentscope.core.skill.repository.AgentSkillRepository
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo
import io.agentscope.core.state.AgentStateStore
import io.agentscope.core.tool.AgentTool
import io.agentscope.core.tool.ToolExecutionContext
import io.agentscope.core.tool.Toolkit
import io.agentscope.core.tool.mcp.McpClientWrapper
import io.agentscope.harness.agent.DistributedStore
import io.agentscope.harness.agent.HarnessAgent
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Kotlin-fluent wrapper around [HarnessAgent.Builder].
 *
 * Mirrors the API of [com.agnetix.harnax.agent.AscopeAgentBuilder] while exposing the full
 * harness capability set: workspace, stateStore, sandbox, distributed filesystem, etc.
 *
 * Key changes from 1.x to 2.0.0:
 * - `Session` → `AgentStateStore` (via `.stateStore()`)
 * - `Hook` → `MiddlewareBase` (via `.middleware()`)
 * - `SandboxDistributedOptions` → `DistributedStore` (via `.distributedStore()`)
 * - `structuredOutputReminder` → removed (model layer handles natively)
 * - `PlanNotebook` → `enablePlanMode()` (v2 plan mode is markdown-based)
 */
class HarnessAgentBuilder {

    private val builder: HarnessAgent.Builder = HarnessAgent.builder()
    private var toolkit: Toolkit = Toolkit()
    private val skills: MutableList<AgentSkill> = mutableListOf()

    // ===== Basic API (mirrors AscopeAgentBuilder) =====

    fun name(name: String): HarnessAgentBuilder = apply { builder.name(name) }

    fun description(description: String): HarnessAgentBuilder = apply { builder.description(description) }

    fun maxIters(maxIters: Int): HarnessAgentBuilder = apply { builder.maxIters(maxIters) }

    fun systemPrompt(systemPrompt: String): HarnessAgentBuilder = apply { builder.sysPrompt(systemPrompt) }

    fun model(model: ChatModelBase): HarnessAgentBuilder = apply { builder.model(model) }

    fun agentWorkspace(workspace: Path): HarnessAgentBuilder = apply {
        builder.workspace(workspace)
    }

    /**
     * Registers MCP clients asynchronously (same approach as [com.agnetix.harnax.agent.AscopeAgentBuilder]).
     */
    fun addMcp(mcpClient: McpClientWrapper): HarnessAgentBuilder = apply {
        CompletableFuture.runAsync {
            this.toolkit.registerMcpClient(mcpClient).block()
        }.get(1, TimeUnit.MINUTES)
    }

    fun enableMetaTool(enable: Boolean): HarnessAgentBuilder = apply { builder.enableMetaTool(enable) }

    fun addTool(vararg toolBoxes: ToolBox): HarnessAgentBuilder = apply {
        toolBoxes.forEach { this.toolkit.registerTool(it) }
    }

    fun addToolContext(ctx: ToolExecutionContext): HarnessAgentBuilder = apply {
        builder.toolExecutionContext(ctx)
    }

    fun addSubAgentAsTool(agent: io.agentscope.core.ReActAgent): HarnessAgentBuilder = apply {
        this.toolkit.registration().subAgent { agent }.apply()
    }

    /**
     * Stores skills to be registered via an in-memory [AgentSkillRepository] at build time.
     * HarnessAgent internally creates a SkillBox from the repository.
     */
    fun addSkill(agentSkill: AgentSkill): HarnessAgentBuilder = apply {
        this.skills.add(agentSkill)
    }

    /**
     * Adds a middleware (replaces addHook in agentscope 2.0.0).
     */
    fun addMiddleware(middleware: MiddlewareBase): HarnessAgentBuilder = apply { builder.middleware(middleware) }

    fun enablePlan(enable: Boolean): HarnessAgentBuilder = apply {
        if (enable) builder.enablePlanMode()
    }

    // ===== Harness-specific API =====

    fun workspace(path: Path): HarnessAgentBuilder = apply {
        builder.workspace(path)
    }

    /**
     * Sets the AgentStateStore (replaces session() in agentscope 2.0.0).
     */
    fun stateStore(stateStore: AgentStateStore): HarnessAgentBuilder = apply { builder.stateStore(stateStore) }

    fun filesystem(spec: SandboxFilesystemSpec): HarnessAgentBuilder = apply { builder.filesystem(spec) }

    fun filesystem(spec: RemoteFilesystemSpec): HarnessAgentBuilder = apply { builder.filesystem(spec) }

    /**
     * Sets the DistributedStore (replaces sandboxDistributed() in agentscope 2.0.0).
     */
    fun distributedStore(store: DistributedStore): HarnessAgentBuilder = apply {
        builder.distributedStore(store)
    }

    // ===== Disable built-in features =====

    fun disableWorkspaceContext(): HarnessAgentBuilder = apply { builder.disableWorkspaceContext() }

    fun disableMemoryHooks(): HarnessAgentBuilder = apply { builder.disableMemoryHooks() }

    fun disableSessionPersistence(): HarnessAgentBuilder = apply { builder.disableSessionPersistence() }

    fun disableFilesystemTools(): HarnessAgentBuilder = apply { builder.disableFilesystemTools() }

    fun disableShellTool(): HarnessAgentBuilder = apply { builder.disableShellTool() }

    fun disableSubagents(): HarnessAgentBuilder = apply { builder.disableSubagents() }

    // ===== Build =====

    fun build(): HarnessAgent {
        builder.toolkit(toolkit)
        if (skills.isNotEmpty()) {
            builder.skillRepository(InMemorySkillRepository(skills.toList()))
        }
        return builder.build()
    }

    fun registerAgentTool(resolvedTool: AgentTool): HarnessAgentBuilder {
        toolkit.registerTool(resolvedTool)
        return this
    }

    /**
     * Simple in-memory [AgentSkillRepository] that wraps a pre-loaded list of [AgentSkill]s.
     */
    private class InMemorySkillRepository(
        private val skills: List<AgentSkill>,
    ) : AgentSkillRepository {
        override fun getSkill(name: String): AgentSkill = skills.first { it.name == name }

        override fun getAllSkillNames(): List<String> = skills.map { it.name }

        override fun getAllSkills(): List<AgentSkill> = skills

        override fun save(skills: List<AgentSkill>, force: Boolean): Boolean = false

        override fun delete(skillName: String): Boolean = false

        override fun skillExists(skillName: String): Boolean = this.skills.any { it.name == skillName }

        override fun getRepositoryInfo(): AgentSkillRepositoryInfo = AgentSkillRepositoryInfo("in-memory", "memory", false)

        override fun getSource(): String = "in-memory"

        override fun setWriteable(writeable: Boolean) {}

        override fun isWriteable(): Boolean = false
    }
}

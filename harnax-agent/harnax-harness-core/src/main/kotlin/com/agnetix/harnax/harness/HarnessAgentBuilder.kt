package com.agnetix.harnax.harness

import com.agnetix.harnax.harness.permission.DangerousInputCheckingTool
import com.agnetix.harnax.harness.skill.SessionEnabledSkillRepository
import com.agnetix.harnax.harness.skill.SkillDraftStaging
import com.agnetix.harnax.tools.sdk.ToolBox
import io.agentscope.core.middleware.MiddlewareBase
import io.agentscope.core.model.ChatModelBase
import io.agentscope.core.permission.PermissionContextState
import io.agentscope.core.skill.AgentSkill
import io.agentscope.core.skill.repository.AgentSkillRepository
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo
import io.agentscope.core.state.AgentStateStore
import io.agentscope.core.tool.AgentTool
import io.agentscope.core.tool.ToolExecutionContext
import io.agentscope.core.tool.Toolkit
import io.agentscope.core.tool.mcp.McpClientWrapper
import io.agentscope.core.tool.mcp.McpTool
import io.agentscope.harness.agent.DistributedStore
import io.agentscope.harness.agent.HarnessAgent
import io.agentscope.harness.agent.filesystem.AbstractFilesystem
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec
import io.agentscope.harness.agent.memory.MemoryConfig
import io.agentscope.harness.agent.memory.compaction.CompactionConfig
import io.agentscope.harness.agent.skill.curator.SkillPromotionGate
import io.agentscope.harness.agent.skill.curator.SkillVisibilityFilter
import io.agentscope.harness.agent.tool.SkillManageConfig
import org.slf4j.LoggerFactory
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
    private var skillsReadListener: ((List<AgentSkill>) -> Unit)? = null
    private var visibilityFilter: SkillVisibilityFilter? = null
    private var promotionGate: SkillPromotionGate? = null
    private var skillStaging: SkillDraftStaging? = null
    private var sessionSkills: SessionEnabledSkillRepository? = null

    private val log = LoggerFactory.getLogger(HarnessAgentBuilder::class.java)

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

    /**
     * Remove a single tool method by name (e.g. a disabled @Tool method).
     */
    fun removeTool(toolName: String): HarnessAgentBuilder = apply {
        this.toolkit.removeTool(toolName)
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
     *
     * One entry per name, the last one winning: the harness merges repositories low-to-high by
     * `AgentSkill.name` and the binding table is read `ORDER BY id`, so "the binding registered last"
     * is what the registry offers the model. Keeping both copies here instead would let the prompt
     * list one skill and the repository resolve another.
     */
    fun addSkill(agentSkill: AgentSkill): HarnessAgentBuilder = apply {
        val shadowed = skills.indexOfLast { it.name == agentSkill.name }
        if (shadowed >= 0) {
            log.warn(
                "Skill '{}' is bound more than once, keeping the last binding and dropping the earlier one",
                agentSkill.name,
            )
            skills.removeAt(shadowed)
        }
        this.skills.add(agentSkill)
    }

    /**
     * Receives the delivered skills each time the harness reads them out of the repository.
     *
     * That read is where Admin's choice for this session becomes the model's context, so it is the one place
     * a load count can be taken. Two consequences for the listener: the harness merges its repositories once
     * per system-prompt composition — once per model call inside a single answer — so it must throttle or it
     * counts prompt renders; and it must not throw, because the harness swallows an exception from a
     * repository and skips that repository, which would drop every delivered skill from the prompt over a
     * lost counter.
     */
    fun onSkillsRead(listener: (List<AgentSkill>) -> Unit): HarnessAgentBuilder = apply {
        this.skillsReadListener = listener
    }

    /**
     * Restricts which of the delivered skills reach the model on this session's calls.
     *
     * Upstream pairs this filter with the promotion gate in one setter, so the two are handed over together in
     * [build] whichever of them is set; a null gate there is what keeps the filter usable on an agent that may
     * not write skills.
     */
    fun skillVisibilityFilter(filter: SkillVisibilityFilter?): HarnessAgentBuilder = apply {
        this.visibilityFilter = filter
    }

    /**
     * Lets this agent author skills of its own, staged for a human to review.
     *
     * The grant has to be decided here, at assembly, and not filtered afterwards: `enableSkillManageTool`
     * registers `skill_manage` and `propose_skill` into the framework's own toolkit, which runs past Harnax's
     * tool configuration filter. So an agent that was not granted self-write simply never sees the tools.
     *
     * Two directories are redirected with it. Upstream's defaults are `skills/_drafts` and `skills`, and the
     * writable repository it installs for the main directory joins the model's load sources — while `skills`
     * is exactly where [com.agnetix.harnax.harness.skill.SandboxSkillProjector] writes the skills Admin
     * delivered. [staging] therefore points both halves at their own tree, and nothing on this path ever moves
     * a draft into it: [gate] files the draft with Admin and defers, which leaves the workspace directory as
     * scratch space and Admin's row as the only thing a reviewer can act on.
     *
     * [sessionSkills] is the repository of the tree a reviewer's confirmation writes into: the skills this
     * session may use without any of them being promoted to a system skill. [build] installs it as a load
     * source, ahead of the delivered ones, and binds it once the agent exists.
     */
    fun skillSelfWrite(
        staging: SkillDraftStaging,
        gate: SkillPromotionGate,
        sessionSkills: SessionEnabledSkillRepository,
    ): HarnessAgentBuilder = apply {
        this.skillStaging = staging
        this.promotionGate = gate
        this.sessionSkills = sessionSkills
    }

    /**
     * Adds a middleware (replaces addHook in agentscope 2.0.0).
     */
    fun addMiddleware(middleware: MiddlewareBase): HarnessAgentBuilder = apply { builder.middleware(middleware) }

    fun permissionContext(ctx: PermissionContextState): HarnessAgentBuilder = apply { builder.permissionContext(ctx) }

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

    /**
     * The extraction/consolidation pipeline configuration: which model writes the memory files, how
     * often, and with what prompt.
     */
    fun memory(config: MemoryConfig): HarnessAgentBuilder = apply { builder.memory(config) }

    /**
     * Mounts [filesystem] under [prefix], ahead of the filesystem the spec describes.
     *
     * A route is the only way to move two paths into a bucket of harnax's own choosing without moving
     * everything else with them: the composite matches by longest prefix, so `MEMORY.md` here wins over
     * the route the spec builds for the same name while every other file keeps the isolation scope it
     * has today.
     */
    fun filesystemRoute(prefix: String, filesystem: AbstractFilesystem): HarnessAgentBuilder = apply {
        builder.filesystemRoute(prefix, filesystem)
    }

    /**
     * Overrides the compaction tier the automatic path runs on. Passing null would turn automatic compaction
     * off entirely — upstream reads it as `disableCompaction` (`HarnessAgent.java:1875`) — which is why the
     * launcher passes [AutoCompactionTier.auto] and never nothing.
     */
    fun compaction(config: CompactionConfig): HarnessAgentBuilder = apply { builder.compaction(config) }

    // ===== Disable built-in features =====

    fun disableWorkspaceContext(): HarnessAgentBuilder = apply { builder.disableWorkspaceContext() }

    fun disableMemoryHooks(): HarnessAgentBuilder = apply { builder.disableMemoryHooks() }

    /**
     * Skips the four tools the harness registers by default (`memory_search`, `memory_get`,
     * `memory_save`, `session_search`). There was no way to turn them off before this passthrough.
     */
    fun disableMemoryTools(): HarnessAgentBuilder = apply { builder.disableMemoryTools() }

    fun disableSessionPersistence(): HarnessAgentBuilder = apply { builder.disableSessionPersistence() }

    fun disableFilesystemTools(): HarnessAgentBuilder = apply { builder.disableFilesystemTools() }

    fun disableShellTool(): HarnessAgentBuilder = apply { builder.disableShellTool() }

    fun disableSubagents(): HarnessAgentBuilder = apply { builder.disableSubagents() }

    fun disableTranscript(): HarnessAgentBuilder = apply { builder.disableTranscript() }

    // ===== Build =====

    fun build(): HarnessAgent {
        builder.toolkit(toolkit)
        // The default workspace repository is merged on top of the ones installed below and wins on a
        // name clash, so an agent could override a skill the operator configured by writing a
        // SKILL.md with the same name into its own sandbox. Admin is the only skill source here. Where a
        // self-write agent does get a writable repository, the directories below move it out of `skills`.
        builder.disableDefaultWorkspaceSkills()
        // Installed before the delivered skills on purpose: composeSkillRepositories keeps Layer 2 in add order
        // and mergeRepositories lets the later repository win a name clash, so a skill the operator approved
        // outranks the session's own copy of the same name the moment the delivered list carries it.
        val enabled = sessionSkills
        if (enabled != null) {
            builder.skillRepository(enabled)
        }
        if (skills.isNotEmpty()) {
            builder.skillRepository(InMemorySkillRepository(skills.toList(), skillsReadListener))
        }
        val staging = skillStaging
        if (staging != null) {
            // autoPromote stays off, so the only way an agent's own skill leaves the staging directory is the
            // promotion pipeline — and securityScan stays on, because that scan is what the reviewer's
            // verdict column is computed from; turning it off would leave the queue judging on nothing.
            builder.enableSkillManageTool(
                SkillManageConfig.builder()
                    .autoPromote(false)
                    .securityScan(true)
                    .draftsDir(staging.draftsDir)
                    .mainDir(staging.promotedDir)
                    .build(),
            )
        }
        // Outside the skills block on purpose: a filter set with no skills delivered is a valid state and
        // the harness installs its middleware from the composed repository list, not from this call
        val gate = promotionGate
        if (gate != null || visibilityFilter != null) {
            builder.enableSkillPromotionGate(gate, visibilityFilter)
        }
        val agent = builder.build()
        // Last, because everything the staging stands for — the workspace filesystem, the agent the
        // promotion pipeline runs on — only exists once the framework has built it.
        staging?.bind(agent)
        enabled?.bind(agent)
        return agent
    }

    fun registerAgentTool(resolvedTool: AgentTool): HarnessAgentBuilder {
        toolkit.registerTool(resolvedTool)
        return this
    }

    /**
     * Retrieves a registered tool by name.
     * Used by the launcher to find tools that need post-registration wrapping.
     */
    fun getTool(toolName: String): AgentTool? = toolkit.getTool(toolName)

    /**
     * Toolkit tools that came from an MCP server, mapped to the client that registered them.
     *
     * Read-only, and asked for once, after all the `addMcp` calls: the caller has the server rows and needs
     * the tool names, while only the live registry knows which server a name belongs to. `addMcp` registers
     * synchronously, so what it installed is visible here. Harness' own built-ins (`execute`, `read_file`,
     * `memory_*`) are attached later, at `build()`, so they are absent — which is exactly right, since none
     * of them belongs to an MCP server.
     */
    fun mcpToolClientNames(): Map<String, String> = toolkit.getToolNames().mapNotNull { name ->
        (toolkit.getTool(name) as? McpTool)?.clientName?.let { name to it }
    }.toMap()

    /**
     * Wraps an already-registered tool with [DangerousInputCheckingTool].
     *
     * This replaces the original tool in the toolkit's registry with a [io.agentscope.core.tool.ToolBase]
     * subclass whose `checkPermissions()` scans all string-valued inputs for dangerous patterns
     * (shell commands like `rm -rf`, sensitive paths like `.env`).
     *
     * The wrapped tool delegates execution to the original, but the permission check happens
     * **before** execution — ensuring bypass-immune safety even in BYPASS mode.
     *
     * Call this after [addTool] to wrap specific @Tool methods marked with `@ToolMeta(dangerousInput=true)`.
     *
     * @param toolName the name of the tool to wrap (must already be registered)
     * @throws IllegalArgumentException if the tool is not found
     */
    fun wrapWithDangerousInputCheck(toolName: String): HarnessAgentBuilder = apply {
        val original = toolkit.getTool(toolName)
            ?: throw IllegalArgumentException("Cannot wrap tool '$toolName': not found in toolkit")
        val wrapped = DangerousInputCheckingTool(original)
        toolkit.registerTool(wrapped)
    }

    /**
     * Simple in-memory [AgentSkillRepository] that wraps a pre-loaded list of [AgentSkill]s.
     *
     * @param onRead invoked with the delivered list on every [getAllSkills] read; see [onSkillsRead]
     */
    private class InMemorySkillRepository(
        private val skills: List<AgentSkill>,
        private val onRead: ((List<AgentSkill>) -> Unit)? = null,
    ) : AgentSkillRepository {
        override fun getSkill(name: String): AgentSkill = skills.first { it.name == name }

        override fun getAllSkillNames(): List<String> = skills.map { it.name }

        override fun getAllSkills(): List<AgentSkill> {
            onRead?.invoke(skills)
            return skills
        }

        override fun save(skills: List<AgentSkill>, force: Boolean): Boolean = false

        override fun delete(skillName: String): Boolean = false

        override fun skillExists(skillName: String): Boolean = this.skills.any { it.name == skillName }

        override fun getRepositoryInfo(): AgentSkillRepositoryInfo = AgentSkillRepositoryInfo(IN_MEMORY_SKILL_SOURCE, "memory", false)

        override fun getSource(): String = IN_MEMORY_SKILL_SOURCE

        override fun setWriteable(writeable: Boolean) {}

        override fun isWriteable(): Boolean = false
    }

    companion object {
        /**
         * Source of [InMemorySkillRepository], the only repository holding what Admin delivered.
         *
         * A caller that needs the delivered skills — [HarnessAgentWrapper], which writes them into the
         * sandbox — selects them by this marker rather than by "every repository present", because the
         * harness merges its own workspace repository on top of the installed ones.
         */
        const val IN_MEMORY_SKILL_SOURCE = "in-memory"
    }
}

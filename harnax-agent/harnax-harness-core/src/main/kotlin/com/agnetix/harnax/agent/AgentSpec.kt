package com.agnetix.harnax.agent

import com.agnetix.harnax.entity.dto.CliDetailDto
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

    /**
     * The `agent` row this run's statistics and logs belong to, or null when no agent row stands behind
     * it.
     *
     * A team's lead is configured by the `team` row (design D1), so it carries [LEAD_ID] as its [id]
     * just to give the runtime something to key on. `token_stats`, `tool_call_log` and `process_log`
     * all keep a nullable `agent_id`, and a 0 there reads back as an agent no id resolves — so a lead
     * is recorded as having none rather than as being agent 0.
     */
    val attributableAgentId: Long? get() = id.takeUnless { it == LEAD_ID }

    companion object {
        /** [id] of a team lead, which has no `agent` row: see [attributableAgentId]. */
        const val LEAD_ID = 0L

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
        // 0 is a team's lead: its configuration is the `team` row and no agent record stands behind it
        // (design D1). An unset builder still reports -1.
        require(id >= 0) { "Agent id must be set (0 for a team lead)" }
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
)

/**
 * One CLI an agent selected, as registered from its plugin package.
 *
 * [payloadDigest] is what the sandbox image is named after, [packageDigest] what the archive is stored
 * and cached under — see `CliPackageLayout`. [runtimeEnv] still holds unresolved platform slots
 * (`${platform.adminUrl}`); the launcher fills them, since only it knows this deployment's admin URL.
 */
data class CliSpec(
    val cliId: Long,
    val name: String,
    val version: String = "",
    val packageObject: String = "",
    val packageDigest: String = "",
    val payloadDigest: String = "",
    val depsApt: List<String> = emptyList(),
    val checkCommand: String = "",
    val runtimeEnv: Map<String, String> = emptyMap(),
    val envBindings: Map<String, String> = emptyMap(),
)

/**
 * The one reading of admin's CLI rows as a [CliSpec].
 *
 * Both consumers must agree field for field: the agent spec path builds an image from these values, and the
 * reclaim sweep recomputes each live CLI set's tag to know which images to keep. A second, independent
 * reading would let the whitelist hash different material than the build did — which is how a sweep ends up
 * deleting the image a live agent starts from.
 */
fun CliDetailDto.toCliSpec(): CliSpec = CliSpec(
    cliId = id,
    name = name,
    version = version,
    packageObject = packageObject,
    packageDigest = packageDigest,
    payloadDigest = payloadDigest,
    depsApt = depsApt,
    checkCommand = checkCommand,
    runtimeEnv = runtimeEnv,
    envBindings = envBindings.mapNotNull { binding ->
        val key = binding["envKey"] ?: return@mapNotNull null
        val value = binding["envValue"] ?: return@mapNotNull null
        key to value
    }.toMap(),
)

data class PlanSpec(
    val enablePlan: Boolean,
    val maxSubTask: Int? = null,
    val needUserConfirmed: Boolean? = null,
)

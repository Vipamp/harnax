package com.vipamp.vipclaw.agent

import com.vipamp.vipclaw.agent.adaptor.*
import com.vipamp.vipclaw.agent.adaptor.mcp.McpHelper
import com.vipamp.vipclaw.agent.adaptor.model.DashScopeChatModelConfig
import com.vipamp.vipclaw.agent.adaptor.model.ModelHelper
import com.vipamp.vipclaw.agent.adaptor.token.TokenStatBuilder
import com.vipamp.vipclaw.agent.provider.HOOK_SET
import com.vipamp.vipclaw.agent.provider.TOOL_SET
import com.vipamp.vipclaw.agent.provider.hook.ConfirmToolsHook
import com.vipamp.vipclaw.agent.provider.hook.ProcessLogHook
import com.vipamp.vipclaw.agent.provider.tool.SessionMetaContext
import com.vipamp.vipclaw.agent.provider.tool.UserIdentifier
import com.vipamp.vipclaw.agent.session.SessionConfig
import com.vipamp.vipclaw.agent.session.SessionLoader
import com.vipamp.vipclaw.common.log.logger
import io.agentscope.core.memory.InMemoryMemory
import io.agentscope.core.memory.Memory
import io.agentscope.core.memory.autocontext.AutoContextMemory
import io.agentscope.core.message.Msg
import io.agentscope.core.plan.PlanNotebook
import io.agentscope.core.session.Session
import io.agentscope.core.session.SessionManager
import io.agentscope.core.state.PlanNotebookState
import io.agentscope.core.state.SimpleSessionKey
import io.agentscope.core.tool.ToolExecutionContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

/**
 * @Author: heqingsong
 * @Date: 2026/4/11
 * @Description: AscopeAgentLauncher
 * @Project: vipclaw
 */
class AscopeAgentLauncher(
    val chatModelConfigAdaptor: ChatModelConfigAdaptor,
    val mcpConfigAdaptor: McpConfigAdaptor,
    val session: Session,
    val skillAdaptor: SkillAdaptor,
    val tokenStatAdaptor: TokenStatAdaptor,
    val processLogAdaptor: ProcessLogAdaptor,
    val toolCallLogAdaptor: ToolCallLogAdaptor,
    val planNoteAdaptor: PlanNoteAdaptor,
    val localRootTmpDir: Path
) {
    val needConfirmedTools: MutableSet<String> = mutableSetOf()

    fun createSingleAgent(
        agentSpec: AgentSpec,
        sessionId: String = UUID.randomUUID().toString(),
        stateless: Boolean = false,
        chatSpec: ChatSpec = ChatSpec.builder().build(),
        userIdentifier: UserIdentifier,
    ): ReActAgentWrapper {
        return createAgentBase(agentSpec, sessionId, stateless, listOf(), chatSpec, userIdentifier)
    }

    private fun createAgentBase(
        agentSpec: AgentSpec,
        sessionId: String,
        stateless: Boolean = false,
        subAgent: List<ReActAgentWrapper>,
        chatSpec: ChatSpec = ChatSpec.builder().build(),
        userIdentifier: UserIdentifier,
    ): ReActAgentWrapper {
        val agentBuilder = AscopeAgentBuilder()
            .name(agentSpec.name)
            .description(agentSpec.description)
            .maxIters(agentSpec.maxIterNum)
            .systemPrompt(agentSpec.systemPrompt)
            .reminder(agentSpec.reminder)
            .agentWorkspace(localRootTmpDir.resolve(agentSpec.name).resolve(sessionId))

        // chat model
//        val chatModelConfig = chatModelConfigAdaptor.getConfig(agentSpec.chatModelId) ?: throw IllegalArgumentException(
//            "Chat model config not found"
//        )
        val chatModelConfig = DashScopeChatModelConfig("qwen3-max-2026-01-23", "sk-5404e4ddac8645a1bd3555c00376a1f5")
        val chatModel = ModelHelper.createChatModel(chatModelConfig, chatSpec)
        agentBuilder.model(chatModel)

        // mcp
        agentSpec.mcpServices.forEach {
            val mcpConfig = mcpConfigAdaptor.getConfig(it.mcpId)
            if (mcpConfig != null) {
                agentBuilder.addMcp(McpHelper.createMcpClient(mcpConfig, it.isAsync))
            } else if (!it.skipIfMissing) {
                logger().error("Mcp config with id `${it.mcpId}` not found.")
                throw IllegalArgumentException("Mcp config with id `${it.mcpId}` not found.")
            } else {
                logger().warn("Mcp config with id `${it.mcpId}` not found.")
            }
        }

        agentSpec.enableMetaTool?.let { agentBuilder.enableMetaTool(it) }
        TOOL_SET.forEach { toolBox ->
            toolBox.init(
                toolCallLogAdaptor,
                SessionMetaContext(agentSpec.id, sessionId),
                userIdentifier
            )
            agentBuilder.addTool(toolBox)
            needConfirmedTools.addAll(toolBox.needConfirmedTools())
        }

        if (agentSpec.contextForTools.isNotEmpty()) {
            val builder = ToolExecutionContext.builder()
            agentSpec.contextForTools.forEach { builder.register(it) }
            agentBuilder.addToolContext(builder.build())
        }

        // sub agents
        subAgent.forEach { agentBuilder.addSubAgentAsTool(it.reActAgent) }

        // skill
        agentSpec.skills.forEach {
            val skill = skillAdaptor.getSkill(it.skillId)
            if (skill != null) {
                agentBuilder.addSkill(skill)
            } else {
                if (!it.skipIfMissing) {
                    logger().error("Skill with id `${it.skillId}` not found.")
                    throw IllegalArgumentException("Skill with id `${it.skillId}` not found.")
                } else {
                    logger().warn("Skill with id `${it.skillId}` not found.")
                }
            }
        }

        HOOK_SET.forEach {
            if (it is ConfirmToolsHook) {
                it.setDangerousTools(needConfirmedTools)
            }
            if (it is ProcessLogHook) {
                it.initial(processLogAdaptor, agentSpec.id, agentSpec.name, sessionId)
            }
            agentBuilder.addHook(it)
        }
        // short memory
        var memory: Memory? = null
        if (!stateless) {
            memory = if (agentSpec.useAutoContextMemory && agentSpec.autoContextConfig != null)
                AutoContextMemory(agentSpec.autoContextConfig, chatModel) else InMemoryMemory()
            agentBuilder.memory(memory)
        }

        // plan
        var planNotebook: PlanNotebook? = null
        if (chatSpec.enablePlan) {
            val planNotebookBuilder =
                PlanNotebook.builder().storage(CustomerPlanNoteStorage(sessionId, planNoteAdaptor))
            if (agentSpec.planSpec.maxSubTask != null) {
                planNotebookBuilder.maxSubtasks(agentSpec.planSpec.maxSubTask)
            }
            if (agentSpec.planSpec.needUserConfirmed != null) {
                planNotebookBuilder.needUserConfirm(agentSpec.planSpec.needUserConfirmed)
            }
            agentBuilder.enablePlan(true)
            planNotebook = planNotebookBuilder.build()
            agentBuilder.addPlanNotebook(planNotebook)
        }
        // Load session
        val agent = agentBuilder.build()
        var sessionManager: SessionManager? = null
        if (!stateless) {
            sessionManager = SessionManager.forSessionId(sessionId).withSession(session)
            sessionManager.addComponent(agent)
            sessionManager.addComponent(memory)
            planNotebook?.let { sessionManager.addComponent(it) }
            sessionManager.loadIfExists()
            logger().info("Loaded ${agent.name} with session $sessionId successfully.")
        }
        return ReActAgentWrapper(
            agent, needConfirmedTools,
            sessionManager,
            TokenStatBuilder().agentId(agentSpec.id).sessionId(sessionId).modelId(agentSpec.chatModelId),
            tokenStatAdaptor
        )
    }

    fun clearSession(sessionId: String) {
        session.delete(SimpleSessionKey.of(sessionId))
        planNoteAdaptor.deletePlan(sessionId)
    }

    fun loadSessionMessages(sessionId: String): List<Msg> {
        return session.getList(SimpleSessionKey.of(sessionId), "memory_messages", Msg::class.java)
    }

    fun loadSessionHistoryPlan(sessionId: String): List<PlanNote> {
        return planNoteAdaptor.getPlanNotes(sessionId)
    }

    fun loadSessionCurrentPlanNote(sessionId: String): PlanNote? {
        val planNote = session.get(SimpleSessionKey.of(sessionId), "planNotebook_state", PlanNotebookState::class.java)
        if (planNote.isPresent) {
            return CustomerPlanNoteStorage.convertToPlanNote(sessionId, planNote.get().currentPlan)
        }
        return null
    }

    companion object {
        fun initLauncher(
            chatModelConfigAdaptor: ChatModelConfigAdaptor,
            mcpConfigAdaptor: McpConfigAdaptor,
            skillAdaptor: SkillAdaptor,
            tokenStatAdaptor: TokenStatAdaptor,
            sessionConfig: SessionConfig?,
            processLogAdaptor: ProcessLogAdaptor,
            toolCallLogAdaptor: ToolCallLogAdaptor,
            planNoteAdaptor: PlanNoteAdaptor,
            localRootTmpDir: Path = Files.createTempDirectory("agent-tmp-dir")
        ): AscopeAgentLauncher {
            return AscopeAgentLauncher(
                chatModelConfigAdaptor,
                mcpConfigAdaptor,
                SessionLoader.load(sessionConfig),
                skillAdaptor,
                tokenStatAdaptor,
                processLogAdaptor,
                toolCallLogAdaptor,
                planNoteAdaptor,
                localRootTmpDir
            )
        }
    }
}

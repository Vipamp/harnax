package com.agnetix.harnax.agent.service.runner.impl

import com.agnetix.harnax.agent.AgentSpec
import com.agnetix.harnax.agent.ChatSpecBuilder
import com.agnetix.harnax.agent.McpSpec
import com.agnetix.harnax.agent.SkillSpec
import com.agnetix.harnax.agent.adaptor.PlanNote
import com.agnetix.harnax.agent.chat.MessageLog
import com.agnetix.harnax.agent.chat.MessageLogConverter
import com.agnetix.harnax.agent.protocol.ChatAgentRequest
import com.agnetix.harnax.agent.protocol.ChatEvent
import com.agnetix.harnax.agent.protocol.ChatResponse
import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.agent.protocol.CommandResponse
import com.agnetix.harnax.agent.protocol.CommandType
import com.agnetix.harnax.agent.protocol.ConfirmAgentRequest
import com.agnetix.harnax.agent.protocol.EndEventChatEvent
import com.agnetix.harnax.agent.protocol.ErrorChatEvent
import com.agnetix.harnax.agent.provider.tool.UserIdentifier
import com.agnetix.harnax.agent.service.runner.AgentRunner
import com.agnetix.harnax.common.error.HarnaxErrorCode
import com.agnetix.harnax.common.error.HarnaxException
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.harness.HarnessAgentLauncher
import com.agnetix.harnax.harness.HarnessAgentWrapper
import com.agnetix.harnax.mapper.SessionMapper
import com.agnetix.harnax.mapper.SkillMapper
import com.github.benmanes.caffeine.cache.Caffeine
import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import io.agentscope.core.message.TextBlock
import io.agentscope.core.message.ToolResultBlock
import org.reactivestreams.Subscription
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap as JConcurrentHashMap

/**
 * Default implementation of AgentRunner.
 * Uses HarnessAgentLauncher to create agents based on session configuration,
 * similar to ChatService but adapted for the AgentRunner interface.
 */
@Service
class DefaultAgentRunner(
    private val launcher: HarnessAgentLauncher,
    private val sessionMapper: SessionMapper,
    private val skillMapper: SkillMapper,
    private val objectMapper: ObjectMapper,
    @Value($$"${agent.cache.max-size:500}")
    private val cacheMaxSize: Long,
) : AgentRunner {

    private val log = LoggerFactory.getLogger(DefaultAgentRunner::class.java)
    private val agentCache = Caffeine.newBuilder()
        .maximumSize(cacheMaxSize)
        .expireAfterWrite(30, TimeUnit.MINUTES)
        .removalListener<String, HarnessAgentWrapper> { key, _, cause ->
            log.info("Agent evicted from cache: session=$key, cause=$cause")
        }
        .build<String, HarnessAgentWrapper>()
    private val activeStreams = JConcurrentHashMap<String, Subscription>()

    override fun process(request: ChatAgentRequest): ChatResponse {
        val sessionId = request.sessionId
        val message = request.message
        val imageUrls = request.imageUrls
        log.info("Processing direct (non-streaming) chat request for session=$sessionId")
        try {
            // TODO [P1] UserIdentifier(0) is hardcoded — all requests share userId=0.
            //   Should extract real user ID from request context (e.g. SecurityContext or request header).
            val userIdentifier = UserIdentifier(0)
            val agent = getOrCreateAgent(sessionId, userIdentifier)
            return agent.call(message, imageUrls)
        } catch (e: Exception) {
            log.error("Error creating agent or calling for session=$sessionId: ${e.message}", e)
            throw e as? HarnaxException
                ?: HarnaxException(
                    HarnaxErrorCode.AGENT_INIT_FAILED.code,
                    e.message ?: "Agent call failed",
                    e,
                )
        }
    }

    override fun streamProcess(request: ChatAgentRequest): Flux<ChatEvent> {
        val sessionId = request.sessionId
        val message = request.message
        val imageUrls = request.imageUrls
        log.info("Streaming message for session=$sessionId: $message, images=${imageUrls.size}")
        try {
            // TODO [P1] UserIdentifier(0) is hardcoded — see process() for details.
            val userIdentifier = UserIdentifier(0)
            val agent = getOrCreateAgent(sessionId, userIdentifier)
            return agent.callStream(message, imageUrls)
                .doOnSubscribe { subscription ->
                    activeStreams[sessionId] = subscription
                    log.debug("Stream started for session=$sessionId")
                }
                .doFinally {
                    activeStreams.remove(sessionId)
                    log.debug("Stream ended for session=$sessionId")
                }
        } catch (e: Exception) {
            log.error("Error creating agent or streaming for session=$sessionId: ${e.message}", e)
            val errorEvent = if (e is HarnaxException) {
                ErrorChatEvent.from(e)
            } else {
                ErrorChatEvent(
                    code = HarnaxErrorCode.AGENT_INIT_FAILED.code,
                    message = e.message ?: "Agent initialization failed",
                )
            }
            return Flux.just(errorEvent, EndEventChatEvent())
        }
    }

    override fun executeCommand(request: CommandAgentRequest): CommandResponse {
        val sessionId = request.sessionId
        val command = request.command
        val args = request.args
        log.info("Executing command for session=$sessionId, command=$command, args='$args'")
        return when (command) {
            CommandType.INTERRUPT -> {
                interrupt(sessionId)
                CommandResponse.success(sessionId, message = "Stream interrupted")
            }
            CommandType.CLEAR -> {
                clearSession(sessionId)
                CommandResponse.success(sessionId, message = "Session cleared")
            }
            CommandType.COMPACT -> {
                // TODO: implement memory compaction/summarization (args may carry token limit etc.)
                log.info("Compact command received for session=$sessionId, args='$args' (not yet implemented)")
                CommandResponse.success(sessionId, message = "Compact not yet implemented")
            }
            CommandType.APPROVE -> {
                // TODO: implement approve with optional args
                log.info("Approve command received for session=$sessionId, args='$args' (not yet implemented)")
                CommandResponse.success(sessionId, message = "Approve not yet implemented")
            }
            CommandType.STOP_SANDBOX -> {
                val sandboxManager = launcher.keepAliveSandboxManager
                if (sandboxManager != null) {
                    sandboxManager.destroy(sessionId)
                    log.info("Sandbox stopped for session=$sessionId")
                    CommandResponse.success(sessionId, message = "Sandbox stopped")
                } else {
                    log.warn("Stop-sandbox command received but keepAliveSandboxManager is null for session=$sessionId")
                    CommandResponse.failure(sessionId, "Sandbox manager not available")
                }
            }
        }
    }

    override fun interrupt(sessionId: String) {
        val subscription = activeStreams.remove(sessionId)
        if (subscription != null) {
            subscription.cancel()
            log.info("Interrupted active stream for session=$sessionId")
        } else {
            log.info("No active stream to interrupt for session=$sessionId")
        }
    }

    override fun loadHistory(sessionId: String): List<MessageLog> = launcher.loadSessionMessages(sessionId)
        .flatMap { MessageLogConverter.convert(it) }

    override fun confirm(request: ConfirmAgentRequest): Flux<ChatEvent> {
        val sessionId = request.sessionId
        log.info("Confirm request for session=$sessionId, confirmed=${request.isConfirmed}")
        val agent = agentCache.getIfPresent(sessionId)
            ?: run {
                log.warn("Agent not in cache for confirm, rebuilding: session=$sessionId")
                getOrCreateAgent(sessionId, UserIdentifier(0))
            }
        val stream = if (request.isConfirmed) {
            agent.callStream()
        } else {
            val results = request.toolInfoList.map { tool ->
                ToolResultBlock.of(
                    tool.toolId,
                    tool.toolName,
                    TextBlock.builder().text("Operation cancelled by user").build(),
                )
            }
            val cancelResult = Msg.builder()
                .name("Assistant").role(MsgRole.TOOL)
                .content(*results.toTypedArray())
                .build()
            agent.callStream(msg = cancelResult)
        }
        return stream
            .doOnSubscribe { subscription ->
                activeStreams[sessionId] = subscription
                log.debug("Confirm stream started for session=$sessionId")
            }
            .doFinally {
                activeStreams.remove(sessionId)
                log.debug("Confirm stream ended for session=$sessionId")
            }
    }

    override fun clearSession(sessionId: String) {
        interrupt(sessionId)
        agentCache.invalidate(sessionId)
        launcher.clearSession(sessionId)
        log.info("Cleared session and agent cache for sessionId=$sessionId")
    }

    override fun loadPlans(sessionId: String): List<PlanNote> = launcher.loadSessionHistoryPlan(sessionId)

    override fun loadCurrentPlan(sessionId: String): PlanNote? = launcher.loadSessionCurrentPlanNote(sessionId)

    override suspend fun initAgent(agentId: Long) {
        log.info("Initializing agent: $agentId")
        // Agent will be lazily created on first request via streamProcess
    }

    override suspend fun destroyAgent(agentId: Long) {
        log.info("Destroying agent: $agentId")
        agentCache.invalidateAll()
        log.info("Cleared all cached agents")
    }

    /**
     * Get or create an agent for the given sessionId.
     * Retrieves session configuration from DB and builds the agent via HarnessAgentLauncher.
     */
    private fun getOrCreateAgent(sessionId: String, userIdentifier: UserIdentifier): HarnessAgentWrapper = agentCache.get(sessionId) { sid ->
        val session = sessionMapper.selectBySessionIdAndStatus(sid, 1)
            ?: throw IllegalArgumentException("Session not found: $sid")

        log.info("Creating agent for session=$sid, agentId=${session.agentId}")

        val chatSpec = ChatSpecBuilder()
            .enableThinking(session.enableThink == 1)
            .enableSearch(session.enableSearch == 1)
            .enablePlan(session.enablePlan == 1)
            .build()

        val agentSpecBuilder = AgentSpec.builder()
            .id(session.agentId)
            .name(session.name)
            .description(session.description)
            .systemPrompt(session.systemPrompt)
            .chatModelId(session.modelId)

        // Parse MCP list (JSON format: [{"id":1,"enable_skip":"true"}])
        if (session.mcpList.isNotEmpty() && session.mcpList != "[]") {
            try {
                val mcpConfigs: List<Map<String, Any>> = objectMapper.readValue(
                    session.mcpList,
                    object : TypeReference<List<Map<String, Any>>>() {},
                )
                for (config in mcpConfigs) {
                    val mcpId = (config["id"] as Number).toLong()
                    val enableSkip = config["enable_skip"] as? String
                    agentSpecBuilder.addMcpService(
                        McpSpec(mcpId = mcpId, skipIfMissing = enableSkip == "true"),
                    )
                }
            } catch (e: Exception) {
                log.warn("Failed to parse MCP list for session=$sid: ${e.message}", e)
            }
        }

        // Parse skill list (comma-separated IDs)
        if (session.skillList.isNotEmpty() && session.skillList != "[]") {
            val skillIds = session.skillList.split(",")
            for (skillIdStr in skillIds) {
                try {
                    val skillId = skillIdStr.trim().toLong()
                    val skill: Skill? = skillMapper.selectById(skillId)
                    if (skill != null) {
                        agentSpecBuilder.addSkill(
                            SkillSpec(skillId = skill.id, skillName = skill.name),
                        )
                    } else {
                        log.warn("Skill not found: $skillId")
                    }
                } catch (e: NumberFormatException) {
                    log.warn("Invalid skill ID: $skillIdStr")
                }
            }
        }

        val agentSpec = agentSpecBuilder.build()

        val agent = launcher.createSingleAgent(
            agentSpec = agentSpec,
            sessionId = sid,
            stateless = false,
            chatSpec = chatSpec,
            userIdentifier = userIdentifier,
        )

        log.info("Agent for session=$sid created and cached successfully")
        agent
    }
}

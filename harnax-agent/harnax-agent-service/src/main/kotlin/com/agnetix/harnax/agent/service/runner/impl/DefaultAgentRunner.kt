package com.agnetix.harnax.agent.service.runner.impl

import com.agnetix.harnax.agent.AgentSpec
import com.agnetix.harnax.agent.ChatSpecBuilder
import com.agnetix.harnax.agent.McpSpec
import com.agnetix.harnax.agent.SkillSpec
import com.agnetix.harnax.agent.chat.MessageLog
import com.agnetix.harnax.agent.chat.MessageLogConverter
import com.agnetix.harnax.agent.protocol.ChatAgentRequest
import com.agnetix.harnax.agent.protocol.ChatEvent
import com.agnetix.harnax.agent.protocol.ChatResponse
import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.agent.protocol.CommandResponse
import com.agnetix.harnax.agent.protocol.CommandType
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
import org.reactivestreams.Subscription
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.ConcurrentHashMap

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
) : AgentRunner {

    private val log = LoggerFactory.getLogger(DefaultAgentRunner::class.java)
    private val agentCache = ConcurrentHashMap<String, HarnessAgentWrapper>()
    private val activeStreams = ConcurrentHashMap<String, Subscription>()

    override fun process(request: ChatAgentRequest): ChatResponse {
        val sessionId = request.sessionId
        log.info("Processing direct chat request for session=$sessionId")
        val events = streamProcess(request).collectList().block() ?: emptyList()
        return ChatResponse.fromEvents(sessionId, events)
    }

    override fun streamProcess(request: ChatAgentRequest): Flux<ChatEvent> {
        val sessionId = request.sessionId
        val message = request.message
        val imageUrls = request.imageUrls
        log.info("Streaming message for session=$sessionId: $message, images=${imageUrls.size}")
        try {
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
        log.info("Executing command for session=$sessionId, command=$command")
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
                // TODO: implement memory compaction/summarization
                log.info("Compact command received for session=$sessionId (not yet implemented)")
                CommandResponse.success(sessionId, message = "Compact not yet implemented")
            }
            CommandType.APPROVE -> {
                // TODO: implement memory compaction/summarization
                log.info("Approve command received for session=$sessionId (not yet implemented)")
                CommandResponse.success(sessionId, message = "Approve not yet implemented")
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

    override suspend fun initAgent(agentId: Long) {
        log.info("Initializing agent: $agentId")
        // Agent will be lazily created on first request via streamProcess
    }

    override suspend fun destroyAgent(agentId: Long) {
        log.info("Destroying agent: $agentId")
        agentCache.keys.forEach { key ->
            agentCache.remove(key)
            log.info("Removed cached agent for session=$key")
        }
    }

    /**
     * Clear session and remove cached agent.
     */
    fun clearSession(sessionId: String) {
        interrupt(sessionId)
        agentCache.remove(sessionId)
        launcher.clearSession(sessionId)
        log.info("Cleared session and agent cache for sessionId=$sessionId")
    }

    /**
     * Get or create an agent for the given sessionId.
     * Retrieves session configuration from DB and builds the agent via HarnessAgentLauncher.
     */
    private fun getOrCreateAgent(sessionId: String, userIdentifier: UserIdentifier): HarnessAgentWrapper = agentCache.computeIfAbsent(sessionId) { sid ->
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

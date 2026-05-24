package com.agnetix.harnax.agent.service.runner.impl

import com.agnetix.harnax.agent.service.runner.AgentRunner
import com.agnetix.harnax.agent.service.runner.AgentStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Placeholder implementation of AgentRunner.
 * Currently returns a simple echo response.
 * Will be integrated with harnax-agent-core's ReActAgentWrapper later.
 */
@Service
class PlaceholderAgentRunner : AgentRunner {

    private val log = LoggerFactory.getLogger(PlaceholderAgentRunner::class.java)
    private val agentCache = ConcurrentHashMap<Long, Boolean>()

    override suspend fun process(sessionId: String, message: String, agentId: Long): String {
        log.info("Processing message for session=$sessionId, agentId=$agentId: $message")
        // Placeholder: return echo response
        // TODO: Integrate with ReActAgentWrapper from harnax-agent-core
        return "Echo response for session $sessionId: You said \"$message\""
    }

    override fun streamProcess(sessionId: String, message: String, agentId: Long): Flow<AgentStreamEvent> {
        log.info("Streaming message for session=$sessionId, agentId=$agentId: $message")
        // Placeholder: return streaming echo response
        // TODO: Integrate with ReActAgentWrapper.streamProcess
        return flow {
            val response = "Echo streaming response for session $sessionId: You said \"$message\""
            emit(AgentStreamEvent.TextStreamEvent(response, true))
            emit(AgentStreamEvent.EndStreamEvent(fullContent = response))
        }
    }

    override suspend fun initAgent(agentId: Long) {
        log.info("Initializing agent: $agentId")
        agentCache[agentId] = true
    }

    override suspend fun destroyAgent(agentId: Long) {
        log.info("Destroying agent: $agentId")
        agentCache.remove(agentId)
    }
}

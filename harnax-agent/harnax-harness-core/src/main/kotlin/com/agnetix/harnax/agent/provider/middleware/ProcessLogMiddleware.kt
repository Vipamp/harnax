package com.agnetix.harnax.agent.provider.middleware

import com.agnetix.harnax.agent.adaptor.ProcessLogAdaptor
import com.agnetix.harnax.agent.adaptor.ProcessLogBuilder
import io.agentscope.core.agent.Agent
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.event.AgentEvent
import io.agentscope.core.event.AgentEventType
import io.agentscope.core.event.ToolCallStartEvent
import io.agentscope.core.event.ToolResultEndEvent
import io.agentscope.core.event.ToolResultTextDeltaEvent
import io.agentscope.core.middleware.AgentInput
import io.agentscope.core.middleware.ActingInput
import io.agentscope.core.middleware.MiddlewareBase
import reactor.core.publisher.Flux
import java.util.function.Function

/**
 * ProcessLogMiddleware — replaces ProcessLogHook in agentscope 2.0.0.
 *
 * In 2.0.0, the Hook interface was deprecated in favor of MiddlewareBase.
 * This middleware uses the onion pattern to log agent lifecycle events:
 * - onAgent: logs agent start/end
 * - onActing: logs tool call start and results
 *
 * Priority: 500 (same as the old ProcessLogHook)
 */
class ProcessLogMiddleware : MiddlewareBase {

    private lateinit var adaptor: ProcessLogAdaptor
    private lateinit var builder: ProcessLogBuilder

    fun initial(
        adaptor: ProcessLogAdaptor,
        agentId: Long,
        agentName: String,
        sessionId: String,
    ) {
        this.adaptor = adaptor
        this.builder = ProcessLogBuilder(agentId, agentName, sessionId)
    }

    override fun onAgent(
        agent: Agent,
        ctx: RuntimeContext,
        input: AgentInput,
        next: Function<AgentInput, Flux<AgentEvent>>,
    ): Flux<AgentEvent> {
        adaptor.emitLog(builder.info("[Processing] Agent '${agent.name}' calling."))
        return next.apply(input)
            .doOnNext { event ->
                when (event.type) {
                    AgentEventType.AGENT_END -> {
                        adaptor.emitLog(builder.info("[Processing] Agent execution completed"))
                    }
                    AgentEventType.TOOL_CALL_START -> {
                        val toolEvent = event as ToolCallStartEvent
                        adaptor.emitLog(builder.info("[Processing] Call tool: '${toolEvent.toolCallName}'"))
                    }
                    AgentEventType.TOOL_RESULT_END -> {
                        val resultEvent = event as ToolResultEndEvent
                        adaptor.emitLog(builder.info("[Processing] Tool '${resultEvent.toolCallName}' completed (state=${resultEvent.state})"))
                    }
                    AgentEventType.TOOL_RESULT_TEXT_DELTA -> {
                        val deltaEvent = event as ToolResultTextDeltaEvent
                        val output = deltaEvent.delta ?: ""
                        if (output.isNotEmpty()) {
                            adaptor.emitLog(builder.info("[Processing] Tool '${deltaEvent.toolCallName}' output: '$output'"))
                        }
                    }
                    else -> {}
                }
            }
            .doOnError { error ->
                adaptor.emitLog(builder.error(message = "[Processing] Error.", throwable = error))
            }
    }

    override fun onActing(
        agent: Agent,
        ctx: RuntimeContext,
        input: ActingInput,
        next: Function<ActingInput, Flux<AgentEvent>>,
    ): Flux<AgentEvent> {
        input.toolCalls.forEach { toolCall ->
            adaptor.emitLog(builder.info("[Processing] Call tool: '${toolCall.name}' with input '${toolCall.input}'"))
        }
        return next.apply(input)
    }
}

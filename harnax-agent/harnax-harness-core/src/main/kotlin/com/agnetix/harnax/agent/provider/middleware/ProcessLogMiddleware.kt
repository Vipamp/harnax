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
import io.agentscope.core.middleware.ActingInput
import io.agentscope.core.middleware.AgentInput
import io.agentscope.core.middleware.MiddlewareBase
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicInteger
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

    private val log = LoggerFactory.getLogger(ProcessLogMiddleware::class.java)

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
        // Diagnostic: trace whether the downstream acting chain actually emits events or
        // completes empty (which would indicate executeToolCalls was never invoked).
        val eventCount = AtomicInteger(0)
        val toolNames = input.toolCalls.joinToString(",") { it.name }
        log.info("[acting-debug] {} onActing ENTER: tools=[{}]", agent.name, toolNames)
        return next.apply(input)
            .doOnNext { event ->
                eventCount.incrementAndGet()
                log.debug("[acting-debug] {} onActing event #{}: {}", agent.name, eventCount.get(), event.javaClass.simpleName)
            }
            .doOnComplete {
                log.info(
                    "[acting-debug] {} onActing COMPLETE: totalEvents={}, tools=[{}]",
                    agent.name,
                    eventCount.get(),
                    toolNames,
                )
                if (eventCount.get() == 0) {
                    log.warn(
                        "[acting-debug] {} onActing completed with ZERO events! " +
                            "Tool execution was likely never invoked. tools=[{}]",
                        agent.name,
                        toolNames,
                    )
                }
            }
            .doOnError { err ->
                log.error(
                    "[acting-debug] {} onActing ERROR after {} events: {}: {}",
                    agent.name,
                    eventCount.get(),
                    err.javaClass.simpleName,
                    err.message,
                )
            }
            .doOnCancel {
                log.warn(
                    "[acting-debug] {} onActing CANCELLED after {} events! tools=[{}]",
                    agent.name,
                    eventCount.get(),
                    toolNames,
                )
            }
    }
}

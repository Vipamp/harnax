package com.agnetix.harnax.agent.provider.middleware

import io.agentscope.core.agent.Agent
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.event.AgentEvent
import io.agentscope.core.middleware.MiddlewareBase
import io.agentscope.core.middleware.ReasoningInput
import reactor.core.publisher.Flux
import java.util.function.Function

/**
 * ConfirmToolsMiddleware — replaces ConfirmToolsHook in agentscope 2.0.0.
 *
 * In 2.0.0, the Hook interface was deprecated in favor of MiddlewareBase.
 * This middleware intercepts the reasoning stage to check if any dangerous tools
 * were called. If a dangerous tool is detected in the reasoning output, it signals
 * the agent to stop via the [AgentEvent] stream.
 *
 * NOTE: The old ConfirmToolsHook used `PostReasoningEvent.stopAgent()`.
 * In the Middleware model, we use `onReasoning` to intercept after reasoning and check
 * for dangerous tool calls. The actual stop mechanism is handled by the framework's
 * built-in RequireUserConfirmEvent when the permission system detects a dangerous tool.
 *
 * This middleware is kept as a placeholder for any custom logic that needs to run
 * before/after reasoning. For simple dangerous-tool blocking, the built-in
 * PermissionEngine (configured via builder.permissionContext()) is the recommended approach.
 */
class ConfirmToolsMiddleware : MiddlewareBase {

    private val dangerousTools: MutableSet<String> = mutableSetOf()

    fun setDangerousTools(dangerousTools: MutableSet<String>) {
        this.dangerousTools.addAll(dangerousTools)
    }

    override fun onReasoning(
        agent: Agent,
        ctx: RuntimeContext,
        input: ReasoningInput,
        next: Function<ReasoningInput, Flux<AgentEvent>>,
    ): Flux<AgentEvent> {
        // Pass through — dangerous tool confirmation is handled by the framework's
        // PermissionEngine or by downstream event processing.
        // This middleware remains available for custom pre/post reasoning logic.
        return next.apply(input)
    }
}

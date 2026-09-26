package com.agnetix.harnax.agent.provider.middleware

import com.agnetix.harnax.agent.adaptor.TokenStatAdaptor
import com.agnetix.harnax.agent.adaptor.token.TokenStatBuilder
import com.agnetix.harnax.agent.protocol.TokenUsage
import io.agentscope.core.agent.Agent
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.event.AgentEvent
import io.agentscope.core.event.ModelCallEndEvent
import io.agentscope.core.middleware.MiddlewareBase
import io.agentscope.core.middleware.ModelCallInput
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import java.util.function.Function

/**
 * Records what each model call consumed into `token_stats`.
 *
 * Sitting on [onModelCall] is what makes both call paths report: agentscope runs the model call through
 * the same middleware chain whether the caller subscribed to the event stream or to the single-result
 * `Mono`, and [ModelCallEndEvent] is the one event that carries that call's accumulated usage. Recording
 * where the usage is produced rather than at one of the two exits is also what keeps the row granularity
 * one thing — a row per model call — so a turn that called the model three times reads the same on a
 * channel as in the WebUI.
 *
 * A team's members are separate agents with separate chains and separate seeds, so a member's calls land
 * on the member's own attribution and never on the lead's.
 */
class TokenStatsMiddleware(
    private val adaptor: TokenStatAdaptor,
    /**
     * The run's attribution, seeded once per agent build; only the per-call numbers are set here. Public
     * because this middleware is the seed's only owner now: what a run gets charged to is asserted here
     * instead of on a second copy carried by the wrapper.
     */
    val tokenStatBuilder: TokenStatBuilder,
) : MiddlewareBase {

    private val log = LoggerFactory.getLogger(TokenStatsMiddleware::class.java)

    override fun onModelCall(
        agent: Agent,
        ctx: RuntimeContext,
        input: ModelCallInput,
        next: Function<ModelCallInput, Flux<AgentEvent>>,
    ): Flux<AgentEvent> = next.apply(input)
        .doOnNext { event ->
            if (event is ModelCallEndEvent) {
                record(agent, event)
            }
        }

    private fun record(agent: Agent, event: ModelCallEndEvent) {
        try {
            // `usage` is read inside the guard because that is where a malformed accumulation surfaces,
            // and the streaming converter already treats reading it as throwable.
            val usage = TokenUsage.fromChatUsage(event.usage) ?: return
            adaptor.saveTokenStat(
                tokenStatBuilder.inputToken(usage.inputTokens)
                    .outputToken(usage.outputTokens)
                    .totalToken(usage.totalTokens)
                    // The call's own moment, not the agent build's: a keep-alive wrapper serves many
                    // turns, and stamping them all with the first one piles a whole session's cost into
                    // one bucket of every time-series view.
                    .timestamp(usage.timestamp)
                    .build(),
            )
        } catch (e: Exception) {
            // A failed write costs a row, not the answer. The reply is already produced by the time this
            // runs, and letting it throw would turn a statistics outage into a dead conversation — the
            // row detail is already in the adaptor's own log, which rethrows here.
            log.warn(
                "[tokenStats] Usage of agent '{}' was not recorded: {}: {}",
                agent.name,
                e.javaClass.simpleName,
                e.message,
            )
        }
    }
}

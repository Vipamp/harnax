package com.agnetix.harnax.agent.provider.middleware

import com.agnetix.harnax.agent.adaptor.SkillUsageAdaptor
import com.agnetix.harnax.entity.ToolInvocationLog
import com.agnetix.harnax.tools.sdk.adaptor.ToolInvocationAdaptor
import com.agnetix.harnax.tools.sdk.adaptor.ToolInvocationEvent
import io.agentscope.core.agent.Agent
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.event.AgentEvent
import io.agentscope.core.event.AgentEventType
import io.agentscope.core.event.ToolResultEndEvent
import io.agentscope.core.event.ToolResultTextDeltaEvent
import io.agentscope.core.message.ToolResultState
import io.agentscope.core.middleware.ActingInput
import io.agentscope.core.middleware.MiddlewareBase
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Function

/**
 * The one place that sees every tool call this session makes (design section 3).
 *
 * `onActing` is handed the calls the model asked for before any of them run, and it watches the same
 * stream's result events afterwards, so both halves of a call — what was asked and how it ended — are
 * available here with no per-tool cooperation. Recording from inside a tool base class would see only
 * built-in tools: an MCP tool or a shell command never passes through such a class.
 *
 * A fresh instance per assembled agent, for the reason [ProcessLogMiddleware]'s comment records: this holds
 * the run's attribution in fields, and a shared instance lets the last build decide whose rows everybody's
 * calls are attributed to.
 */
class ToolInvocationMiddleware(
    private val adaptor: ToolInvocationAdaptor,
    private val tenantId: Long?,
    private val agentId: Long?,
    private val sessionId: String,
    private val userId: Long?,
    private val mcpIdsByTool: Map<String, Long> = emptyMap(),
    private val cliIdsByCommand: Map<String, Long> = emptyMap(),
    private val builtinToolNames: Set<String> = emptySet(),
    private val skillUsageAdaptor: SkillUsageAdaptor? = null,
    private val adminSkillIdsBySkillId: Map<String, Long> = emptyMap(),
) : MiddlewareBase {

    private val log = LoggerFactory.getLogger(ToolInvocationMiddleware::class.java)
    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    /** One call as it started: the name is the authority when the end event's copy disagrees. */
    private class Start(
        val name: String,
        val input: Map<String, Any?>,
        val startMillis: Long,
    )

    /**
     * Assembled eagerly: the start table and the returned `Flux` are built when this is called, so
     * `Start.startMillis` is the moment the middleware saw the batch, not the moment somebody subscribed.
     */
    override fun onActing(
        agent: Agent,
        ctx: RuntimeContext,
        input: ActingInput,
        next: Function<ActingInput, Flux<AgentEvent>>,
    ): Flux<AgentEvent> {
        val started = ConcurrentHashMap<String, Start>()
        val results = ConcurrentHashMap<String, StringBuffer>()
        val openByName = ConcurrentHashMap<String, ConcurrentLinkedDeque<String>>()
        val failure = AtomicReference<Throwable?>()
        var suffix = 0
        input.toolCalls.forEach { call ->
            // One turn can ask twice for the same tool, and `ToolUseBlock.id` is nullable upstream, so two
            // calls can compute the same key. A plain put would drop the first accumulator and that call
            // would never be counted, so a colliding key gets a private suffix and every open key stays
            // reachable by name in issue order. That queue is also how an end event finds its start when
            // the id is present on one side only.

            // A nameless call is dropped here rather than registered: `ToolUseBlock.name` is deserialised from
            // the model's JSON and validated by nothing upstream, and without a name there is no key to compute,
            // no kind to decide and no `tool_name` to write into a NOT NULL column, so the row could not have
            // been filed anyway. That costs one row; registering it would throw out of this method and cost
            // the turn, which D9 forbids.
            val name = call.name ?: return@forEach
            val base = key(call.id, name)
            var k = base
            while (started.putIfAbsent(k, Start(name, call.input ?: emptyMap(), System.currentTimeMillis())) != null) {
                k = "$base#${++suffix}"
            }
            openByName.computeIfAbsent(name) { ConcurrentLinkedDeque() }.addLast(k)
        }
        return next.apply(input)
            .doOnNext { event -> onEvent(event, started, results, openByName) }
            .doOnError { error -> failure.set(error) }
            .doFinally { emitUnresolved(started, results, openByName, failure.get()) }
    }

    /**
     * The accumulator key: the call's id when the runtime gave one, otherwise its name. The name fallback
     * is unique only while a single call of that name is open, which is why `onActing` suffixes a collision
     * and `matchKey` falls back to issue order.
     */
    private fun key(
        id: String?,
        name: String?,
    ): String = id ?: name ?: ""

    private fun onEvent(
        event: AgentEvent,
        started: ConcurrentHashMap<String, Start>,
        results: ConcurrentHashMap<String, StringBuffer>,
        openByName: ConcurrentHashMap<String, ConcurrentLinkedDeque<String>>,
    ) {
        runCatching {
            when (event.type) {
                AgentEventType.TOOL_RESULT_TEXT_DELTA -> {
                    val delta = event as ToolResultTextDeltaEvent
                    val text = delta.delta
                    if (!text.isNullOrEmpty()) results.computeIfAbsent(key(delta.toolCallId, delta.toolCallName)) { StringBuffer() }.append(text)
                }

                AgentEventType.TOOL_RESULT_END -> resolve(event as ToolResultEndEvent, started, results, openByName)
                else -> {}
            }
        }.exceptionOrNull()?.let {
            log.warn("Tool invocation recording skipped for session {}: {}", sessionId, it.message)
        }
    }

    private fun resolve(
        end: ToolResultEndEvent,
        started: ConcurrentHashMap<String, Start>,
        results: ConcurrentHashMap<String, StringBuffer>,
        openByName: ConcurrentHashMap<String, ConcurrentLinkedDeque<String>>,
    ) {
        val key = key(end.toolCallId, end.toolCallName)
        val startKey = matchKey(key, end.toolCallName, started, openByName) ?: return
        val start = started[startKey] ?: return
        val outcome = when (end.state) {
            ToolResultState.SUCCESS -> ToolInvocationLog.OUTCOME_SUCCESS
            ToolResultState.ERROR -> ToolInvocationLog.OUTCOME_ERROR
            ToolResultState.DENIED -> ToolInvocationLog.OUTCOME_DENIED
            ToolResultState.INTERRUPTED -> ToolInvocationLog.OUTCOME_INTERRUPTED
            // Still in flight (an async tool's first end event): keep the start so the terminal event that
            // follows can still time it.
            else -> return
        }
        started.remove(startKey)
        // The queue mirrors the table one key to one start: registered under `call.name`, spent under
        // `Start.name`, which is that same value. So a key peeked here is always a live start, and there is
        // nothing to skip.
        openByName[start.name]?.remove(startKey)
        // Deltas accumulate under the key their own frame carries, so a start keyed by name and an end keyed by
        // id is one buffer read by two keys: the second lookup is what keeps the body of such a call. The first
        // hit short-circuits, and where both keys are the same the second lookup never runs. Only two calls that
        // are both nameless and of one name truly share a buffer — their keys were never distinguishable, and
        // what survives there is the row count, with merged text as the documented concession.
        val resultText = (results.remove(startKey) ?: results.remove(key))?.toString()
        if (end.toolCallName != null && end.toolCallName != start.name) {
            log.warn(
                "Tool call {} in session {} ended under name '{}' but was recorded as '{}'",
                startKey,
                sessionId,
                end.toolCallName,
                start.name,
            )
        }
        emit(start, start.name, outcome, resultText, failureText(outcome, resultText), System.currentTimeMillis())
        if (outcome == ToolInvocationLog.OUTCOME_SUCCESS) reportSkillUse(start)
    }

    /**
     * Which start this end event owns: the exact key while it is still open, otherwise the oldest key still
     * open under the frame's name. A model gets its own calls answered in the order it asked for them, so FIFO
     * is the only defensible guess, and a wrong guess costs a duration measured against the wrong start rather
     * than a lost row.
     *
     * A lookup only: nothing here changes the queue. The key leaves it where the start itself is dropped, and
     * located by the name recorded at the start — the queue is named after that, so taking a key out under the
     * frame's name (which a renamed frame differs in) would spend nothing where it looked and leave an
     * already-consumed key in the queue where the start actually sits. The next frame of that name without an
     * id would then pair with that ghost, find no start behind it and lose a call that had succeeded.
     *
     * Rejected, and deliberately so: falling back to `started.keys.singleOrNull()` when the frame's name matches
     * no queue. That needs two absences at once — no id, and a frame name this run never registered — and no
     * upstream is known to emit such a frame, so it would be a guess. Section 3's contract is match, never
     * guess: an unpairable call goes to the stream-end interrupted row instead.
     */
    private fun matchKey(
        key: String,
        name: String?,
        started: ConcurrentHashMap<String, Start>,
        openByName: ConcurrentHashMap<String, ConcurrentLinkedDeque<String>>,
    ): String? {
        if (started.containsKey(key)) return key
        val open = name?.let { openByName[it] } ?: return null
        return open.peekFirst()
    }

    /** Non-success rows carry a reason: the tool's own output is the reason when it produced one. */
    private fun failureText(
        outcome: String,
        resultText: String?,
    ): String? = if (outcome == ToolInvocationLog.OUTCOME_SUCCESS) null else resultText?.takeIf { it.isNotBlank() } ?: outcome

    private fun emitUnresolved(
        started: ConcurrentHashMap<String, Start>,
        results: ConcurrentHashMap<String, StringBuffer>,
        openByName: ConcurrentHashMap<String, ConcurrentLinkedDeque<String>>,
        failure: Throwable?,
    ) {
        started.keys.toList().forEach { key ->
            val start = started.remove(key) ?: return@forEach
            openByName[start.name]?.remove(key)
            // Whatever the tool streamed before the stream died is the most readable part of the row, so it
            // is filed rather than dropped: the reason goes to `errorMessage`, the partial output to
            // `resultText`, and the writer truncates it like any other.
            val text = failure?.let { "${it.javaClass.simpleName}: ${it.message ?: ""}" } ?: "stream ended before the tool returned"
            // The same two keys as the resolved path, other order: this start's own key first, then the name it
            // was recorded under, because a frame that carries no id streams its text there. An identifiable
            // start met exactly that, and would otherwise lose what it had managed to stream.
            val resultText = (results.remove(key) ?: results.remove(start.name))?.toString()
            emit(start, start.name, ToolInvocationLog.OUTCOME_INTERRUPTED, resultText, text, System.currentTimeMillis())
        }
    }

    private fun emit(
        start: Start,
        name: String,
        outcome: String,
        resultText: String?,
        errorMessage: String?,
        endMillis: Long,
    ) {
        runCatching {
            val attribution = ToolInvocationClassifier.classify(name, start.input, mcpIdsByTool, cliIdsByCommand, builtinToolNames)
            adaptor.emit(
                ToolInvocationEvent(
                    tenantId = tenantId,
                    agentId = agentId,
                    sessionId = sessionId,
                    userId = userId,
                    kind = attribution.kind,
                    toolName = attribution.toolName,
                    mcpId = attribution.mcpId,
                    cliId = attribution.cliId,
                    outcome = outcome,
                    argsJson = jsonOf(start.input),
                    resultText = resultText,
                    errorMessage = errorMessage,
                    startEpochMilli = start.startMillis,
                    endEpochMilli = endMillis,
                ),
            )
        }.exceptionOrNull()?.let {
            // The reporter's own contract is that it does not throw; this covers everything else, including
            // an adaptor implemented by somebody else's code. A lost row is worth less than a lost answer.
            log.warn("Tool invocation event for '{}' in session {} was not filed: {}", name, sessionId, it.message)
        }
    }

    private fun jsonOf(input: Map<String, Any?>): String? = runCatching { objectMapper.writeValueAsString(input) }.getOrNull()

    /**
     * The model worked through this skill's instructions: it asked the loader for the skill body and got it.
     *
     * `SKILL.md` is what carries the instructions, so a skill whose resource file was read is not yet used,
     * and a load that failed told the model nothing. No cooldown: one load is one use.
     */
    private fun reportSkillUse(start: Start) {
        if (start.name != ToolInvocationClassifier.SKILL_LOAD_TOOL_NAME) return
        val skillId = start.input[PARAM_SKILL_ID] as? String ?: return
        val path = start.input[PARAM_PATH] as? String ?: return
        if (path != SKILL_BODY) return
        val adminSkillId = adminSkillIdsBySkillId[skillId] ?: return
        runCatching { skillUsageAdaptor?.reportUses(sessionId, listOf(adminSkillId), userId) }
            .exceptionOrNull()
            ?.let { log.warn("Skill use report for skill {} in session {} was not filed: {}", skillId, sessionId, it.message) }
    }

    companion object {
        private const val PARAM_SKILL_ID = "skillId"
        private const val PARAM_PATH = "path"
        private const val SKILL_BODY = "SKILL.md"
    }
}

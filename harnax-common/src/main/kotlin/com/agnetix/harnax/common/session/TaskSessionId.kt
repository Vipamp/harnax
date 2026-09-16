package com.agnetix.harnax.common.session

import java.util.UUID

/**
 * Contract C1: the grammar of a scheduled-task session id — `task-{taskId}-{agentId}-{uuid}`.
 *
 * The id used to be `task-{taskId}-{uuid}`, and reading it back meant taking the task id out of the string
 * and then looking the task *row* up to find the agent the run belongs to. That was fine while admin owned
 * the scheduled-task domain. It is not fine now: release 2 moves `agent_task` into `harnax-scheduler`, so
 * the one consumer that still has to turn a session id into an agent spec can no longer read the table the
 * answer lived in. The fix is to stop storing half the identity in a table both services used to share and
 * start carrying it in the id, which is what this object defines.
 *
 * It lives in `harnax-common` because both sides of the contract do: the scheduler mints ids
 * (`SchedulerServiceImpl.insertRunningLog`) and admin parses them
 * (`InternalApiController.resolveFromTask`), and neither module may depend on the other.
 *
 * The two segments are generated from one row read once, so they cannot disagree — that single-source rule
 * is what replaced the design spec's plan to have admin compare the id's agent id against
 * `agent_task.agent_id` (spec §5 C1), which the migration made unexecutable: the comparison needs a table
 * admin no longer has.
 *
 * The shape defined here is the only one [parse] accepts. [parse]'s comment says what a refusal means and
 * why widening it back to the pre-C1 spelling would be a mistake rather than a kindness.
 */
object TaskSessionId {

    /** What every consumer classifies a task session by; the rest of the grammar is [of] and [parse]. */
    const val PREFIX = "task-"

    /** The shape, spelled out for error messages: never retype it, or the two sides drift apart. */
    const val FORMAT = "task-{taskId}-{agentId}-{uuid}"

    /**
     * What [parse] answers for a well-formed C1 id: both ids, and the opaque tail [of] appended to make two
     * executions of one task differ — a `UUID` with its dashes removed, so the whole id holds exactly four
     * `-`-separated tokens and [parse] can recognise it by *counting*.
     *
     * Every field is present or the type does not exist: [parse] returns null instead of a half answer.
     * There is deliberately no representation for "a task session that names no agent", because the only
     * thing a consumer could do with one is refuse it, and a refusal is what null already says.
     */
    data class Parsed(
        val taskId: Long,
        val agentId: Long,
        val random: String,
    )

    /**
     * The only place a task session id is built.
     *
     * Both ids come from the same `agent_task` row, read once by the caller: that is what makes the id
     * self-validating, and it is why neither may be a value re-queried later. That row is read by
     * harnax-scheduler, which has owned this table since release 2.
     *
     * [uuid] is written without its dashes. That is not cosmetics: [parse] decides the shape by counting
     * segments, so a tail carrying a `-` would not be a C1 id at all even with both ids correctly in front
     * of it — and an id this function returns always round-trips through it.
     *
     * @throws IllegalArgumentException when either id is not a persisted one, because minting
     *   `task-0-…` would produce an id whose own parser rejects it — the failure would surface in whichever
     *   service is reading it, far from the row that was never written.
     */
    fun of(
        taskId: Long,
        agentId: Long,
        uuid: String = UUID.randomUUID().toString(),
    ): String {
        require(taskId > 0L) { "A task session id needs a persisted task id, got $taskId" }
        require(agentId > 0L) { "A task session id needs the agent id of that same task row, got $agentId" }
        val random = uuid.replace("-", "")
        require(random.isNotBlank()) { "A task session id needs a random tail that survives its own parser" }
        return "$PREFIX$taskId-$agentId-$random"
    }

    /**
     * Parse [sessionId] as a C1 task session id, or null when it is not one.
     *
     * Exactly one shape is recognised: `task-{taskId}-{agentId}-{uuid}`, four `-`-separated tokens with both
     * ids in positive decimal and a non-blank tail. This is the shape [of] writes and the only one that has
     * ever been written *into a system that has to read it back*.
     *
     * What a refusal means. The producer before release 2 wrote `task-{taskId}-{uuid}` — a dashed UUID, so
     * five or more tokens. No such row exists anywhere: release 2 copies nothing over, the scheduler's
     * `agent_task_log` starts empty and the old table is dropped, so every task session id a consumer can be
     * handed was minted by [of]. A `task-…` string that reaches null therefore means one thing worth telling
     * an operator — the producer is out of step with this parser (an un-upgraded scheduler, a hand-edited
     * row, a caller that invented the id) — and `resolveFromTask`'s message names both the expected shape
     * and the offending string so that is checkable from the log line alone.
     *
     * So the pre-C1 form is refused, not half-read. An earlier version of this function answered
     * `Parsed(taskId)` with a null agent for it, on the theory that an operator holding a log row still
     * wants the task id; that branch had no such operator, only one consumer that had to reject it, and it
     * left `agentId` nullable in a type whose whole job is to be non-null about the agent. **Do not reinstate
     * it as a convenience.** If pre-C1 rows ever turn up for real, the answer is to read the agent id from
     * wherever the row lives — the same place every other agent lookup gets it — not to soften this parser
     * into producing a value nothing can resolve an agent from.
     */
    fun parse(sessionId: String): Parsed? {
        if (!sessionId.startsWith(PREFIX)) return null
        val segments = sessionId.removePrefix(PREFIX).split('-')
        // The count is the contract, not a hint: a tail carrying a `-` would land on the wrong side of it,
        // which is why of() strips the dashes out of the UUID it appends.
        if (segments.size != 3) return null
        val taskId = segments[0].toIdOrNull() ?: return null
        val agentId = segments[1].toIdOrNull() ?: return null
        val random = segments[2]
        if (random.isBlank()) return null
        return Parsed(taskId = taskId, agentId = agentId, random = random)
    }

    /**
     * An id segment, and only ever in the digits-only form [of] writes. [String.toLongOrNull] would also
     * accept `+1`, which [of] never writes, so the sign is refused here rather than at whoever has to look
     * the id up afterwards — and a digit string too wide for a `Long` comes back null, so it is refused
     * rather than turned into some other id.
     */
    private fun String.toIdOrNull(): Long? {
        if (isEmpty() || !all { it.isDigit() }) return null
        return toLongOrNull()?.takeIf { it > 0L }
    }
}

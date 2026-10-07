package com.agnetix.harnax.agent.adaptor

/**
 * Files what this session did with the skills Admin delivered to it.
 *
 * The runtime holds no skill table of its own — Admin delivered the text — so the count has to travel back
 * over the same internal API that delivered it. Two events exist because two questions exist: whether a
 * skill entered the context, and whether the model then worked through its body.
 *
 * Implementations must return without waiting for the network. Both calls sit on a path that streams an
 * answer: a reporter that blocks would add its latency to every model call, and one that throws would fail
 * a turn over a lost counter. Failures belong to the implementation — log them, drop the batch, never
 * rethrow.
 */
interface SkillUsageAdaptor {
    /**
     * @param userId the [com.agnetix.harnax.tools.sdk.UserIdentifier] this run is attributed to, or null when
     * the conversation names no harnax user — a channel conversation, or a service caller that did not
     * resolve one. Admin keeps the row and leaves its user column empty; it does not take the id on faith,
     * since the tenant of the report is resolved from [sessionId] and a user outside that tenant would put a
     * count on somebody else's analytics page.
     */
    fun reportViews(
        sessionId: String,
        skillIds: List<Long>,
        userId: Long?,
    )

    /**
     * A skill whose instructions the model actually worked through: its `SKILL.md` was read back through the
     * skill loader and came back successfully. Reading a resource file of the same skill is not a use —
     * only the body carries instructions.
     *
     * No cooldown, unlike [reportViews]: a load repeats because the harness re-reads the repository on every
     * system-prompt assembly, while one turn loads one skill once. Same argument shape and same non-blocking,
     * non-throwing contract.
     */
    fun reportUses(
        sessionId: String,
        skillIds: List<Long>,
        userId: Long?,
    )
}

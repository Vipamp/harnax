package com.agnetix.harnax.agent.adaptor

/**
 * Files the fact that a set of Admin-delivered skills just entered one session's context.
 *
 * The runtime holds no skill table of its own — Admin delivered the text — so the count has to travel back
 * over the same internal API that delivered it. The event it records is `VIEW`, the load; whether the model
 * then followed the instructions is a second event this path cannot judge and does not guess at.
 *
 * Implementations must return without waiting for the network. This runs on the path that composes the
 * system prompt, so a reporter that blocks would turn a telemetry endpoint into something a turn has to
 * wait for, and one that throws would fail an answer over a lost counter. Failures belong to the
 * implementation: log them, drop the batch, never rethrow.
 */
fun interface SkillUsageAdaptor {
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
}

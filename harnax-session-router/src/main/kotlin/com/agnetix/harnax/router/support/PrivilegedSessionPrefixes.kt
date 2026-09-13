package com.agnetix.harnax.router.support

/**
 * The session-id prefixes that name a conversation the **server** decides, as opposed to one the caller
 * is entitled to pick.
 *
 * Scheduler mints `task-` (`task-{taskId}-{uuid}`) and the channel service mints `chn-` (`chn-{uuid}`);
 * admin resolves both by parsing the prefix against the `agent_task` and `channel` tables rather than
 * by looking the id up — see `InternalApiController.getAgentSpec`. That split is the hole this object
 * closes: the router's ownership check reads admin's `session` table, where these two deliberately do
 * not live, so a forged `task-7-…` came back Unknown and Unknown passes. Until now any valid credential
 * could name someone else's task or channel session and have the agent answer with that session's
 * configuration — and, for a task, with `BYPASS` permission mode.
 *
 * So the decision has to be made by prefix, before the lookup, because no lookup can answer it today.
 * Teaching the lookup all four tables is the F3-A follow-up; this rule is what stands in front of it.
 *
 * This list pairs with admin's four-prefix classification and names only the privileged half of it.
 * `web-` and `mp-` keep their behaviour exactly: they are the caller's own sessions and still settle by
 * tenant.
 *
 * It is deliberately a router-side copy instead of a constant shared with admin: the router reaches
 * admin over HTTP only, `harnax-admin` is a Spring Boot application module the router must not depend
 * on, and admin spells these prefixes as inline literals inside its `when`. Hoisting them into
 * `harnax-common` to force a single definition would put an admin routing table in front of every
 * module that reads it, while leaving admin's own `when` free to drift from it anyway. These prefixes
 * are stable session-id grammar: renaming one is a change to what the ids *mean*, and it lands on
 * admin, scheduler and the channel service at once rather than quietly on one side.
 */
object PrivilegedSessionPrefixes {

    /** Scheduler-owned task executions: `task-{taskId}-{uuid}`. */
    const val TASK = "task-"

    /** Channel conversations: `chn-{uuid}`. */
    const val CHANNEL = "chn-"

    private val all = listOf(TASK, CHANNEL)

    /** True when [sessionId] names a server-decided conversation. */
    fun matches(sessionId: String): Boolean = all.any { sessionId.startsWith(it) }
}

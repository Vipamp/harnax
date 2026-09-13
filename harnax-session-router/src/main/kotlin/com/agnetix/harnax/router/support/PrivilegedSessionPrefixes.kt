package com.agnetix.harnax.router.support

/**
 * The session-id prefixes that name a conversation the **server** decides, as opposed to one the caller
 * is entitled to pick.
 *
 * Scheduler mints `task-` (`task-{taskId}-{uuid}`), and admin resolves it by parsing the prefix against
 * the `agent_task` table rather than by looking the id up — see `InternalApiController.getAgentSpec`.
 * That split is the hole this object closes: the router's ownership check reads admin's `session`
 * table, where a task id deliberately does not live, so a forged `task-7-…` came back Unknown and
 * Unknown passes. Any valid credential could name someone else's task and have the agent answer with
 * that task's configuration — with `BYPASS` permission mode, which `resolveFromTask` hardcodes.
 *
 * So the decision has to be made by prefix, before the lookup, because no lookup can answer it today.
 *
 * ## Why `chn-` is not on this list
 *
 * It was, and that was too wide: the webui channel-admin page has legitimate end-user traffic against
 * `chn-` (it polls sandbox status with `batchGetWorkspaceStatus(channels[].sessionId)` and opens the
 * same id in the workspace drawer), and its credential is the visitor's login-issued user-bound key,
 * which is exactly `userId != null`. Refusing that did not close a hole; it took a live read path
 * offline, and silently, because the page swallows the failure.
 *
 * The two prefixes differ in the one thing a refusal has to be worth — what an attacker can *reach*:
 * - `task-{taskId}`: the id is an auto-increment integer. One valid credential enumerates
 *   `task-1`…`task-N` and reads every tenant's task configuration. Enumerable, so a prefix rule is the
 *   only thing between a login and someone else's agent spec.
 * - `chn-{uuid}`: the id is a UUID. Naming one already requires knowing it, so the rule stopped nobody
 *   who could not already have aimed at that specific id.
 *
 * And on the side of what the rule *buys*: admin's `/sessions/{id}/info` reads only the `session`
 * table, so it answers `Unknown` for a `chn-` id no matter who asks. There is no ownership query here
 * to protect — the refusal could not turn into an authorisation decision, only into a denial. A rule
 * that blocks a working feature without adding a verifiable grant is not worth its blast radius.
 *
 * So `chn-` keeps the branch it had before: the ordinary tenant comparison in
 * [com.agnetix.harnax.router.service.SessionAccessGuard], which is a no-op against `Unknown` today and
 * starts to bite the moment admin can answer for it. Teaching that lookup the `channel` table is F3-A,
 * and it is the informative fix for `chn-`, because it adds the check rather than removing the access.
 *
 * `web-` and `mp-` are untouched: they are the caller's own sessions and still settle by tenant.
 *
 * It is deliberately a router-side copy instead of a constant shared with admin: the router reaches
 * admin over HTTP only, `harnax-admin` is a Spring Boot application module the router must not depend
 * on, and admin spells these prefixes as inline literals inside its `when`. Hoisting them into
 * `harnax-common` to force a single definition would put an admin routing table in front of every
 * module that reads it, while leaving admin's own `when` free to drift from it anyway. These prefixes
 * are stable session-id grammar: renaming one is a change to what the ids *mean*, and it lands on
 * admin, scheduler and the channel service at once rather than quietly on one side.
 * [PrivilegedSessionPrefixesTest] pins this list against admin's classification for the shapes that
 * can drift apart in practice — case, separator, leading whitespace.
 */
object PrivilegedSessionPrefixes {

    /**
     * Scheduler-owned task executions: `task-{taskId}-{uuid}`. The only privileged prefix, and the one
     * whose `{taskId}` is an enumerating integer; see the class comment for why `chn-` is not.
     */
    const val TASK = "task-"

    private val all = listOf(TASK)

    /** True when [sessionId] names a server-decided conversation. */
    fun matches(sessionId: String): Boolean = all.any { sessionId.startsWith(it) }
}

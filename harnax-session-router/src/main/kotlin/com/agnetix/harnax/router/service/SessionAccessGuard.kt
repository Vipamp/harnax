package com.agnetix.harnax.router.service

import com.agnetix.harnax.auth.AuthContextHolder
import com.agnetix.harnax.router.support.IdFormat
import com.agnetix.harnax.router.support.PrivilegedSessionPrefixes
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * The gate every session-scoped proxy call goes through before the router looks for an instance.
 *
 * Inbound authentication answers "may this caller use the router". It never answered "may this caller
 * read *this* session": each of the fourteen proxy endpoints took a session id on faith and forwarded
 * it to whichever agent held it, so any valid credential — another tenant's API key, any logged-in
 * user's own token — could pull a stranger's transcript, plans and sandbox files, or delete them. The
 * agent cannot cover for the router here: the router stamps its own service token on the call, so what
 * arrives upstream looks like a peer service, not like a user.
 *
 * Tenants are the unit that exists on both sides. A caller carries one in its [com.agnetix.harnax.auth.AuthContext]
 * (a login token's `tenantId` claim, or the tenant on the API key), and admin knows the tenant owning
 * each session.
 *
 * Two cases deliberately pass through:
 * - A caller with no tenant: an internal service token or a SYSTEM key. The channel service routes on
 *   behalf of users it does not own and is itself the one that authenticated them; there is nothing to
 *   compare, and inventing a denial would only push operators to disable authentication.
 * - A session admin says does not exist — no `session` row and, for a `chn-` id, no `channel` row at all
 *   (admin reads that row whatever its `active` flag says). For the ids that really are nothing's — a
 *   fresh `web-` id the user opens with first — nothing is bound to it, so the proxy endpoints answer
 *   "not bound" without touching an agent, and the tenant that owns it cannot be proven either way.
 *
 * The second case is narrower than it used to be, and it is worth being precise about the residue.
 * `chn-` ids used to land in it permanently: admin resolved a channel session from the `channel` table
 * for the agent spec but read only `session` here, so every one of them came back Unknown and the
 * comparison below never ran — which is how any logged-in user in any tenant could read another
 * tenant's channel conversation, plans and sandbox files by naming its sessionId. Admin answers from
 * `channel` now, so a cross-tenant `chn-` read is refused by the comparison below, and the same-tenant
 * reads the webui channel page makes keep passing. What is left of the pass is the genuinely unbound
 * id, plus the [SessionInfoClient] cache: it remembers an `Unknown` for five minutes, so a `chn-` id
 * asked about before admin learned to answer it keeps passing for that long after a rollout.
 *
 * That reasoning stops short of `task-`. Admin resolves those from the `agent_task` table and they are
 * somebody's, but its ownership endpoint still does not consult that table, so they arrive Unknown here
 * and would have passed with the forged id choosing whose configuration and credentials the agent runs
 * with — and `task-{taskId}` is an id a caller can count through. A prefix rule decided before the
 * lookup closes that; see [PrivilegedSessionPrefixes] for why it is a rule and not a query, and for
 * why `chn-` needs no such rule now that the query answers it.
 */
@Component
class SessionAccessGuard(
    private val sessionInfoClient: SessionInfoClient,
) {

    private val log = LoggerFactory.getLogger(SessionAccessGuard::class.java)

    /**
     * @throws IllegalArgumentException when the id could not be a session id at all.
     * @throws SecurityException when the session demonstrably belongs to another tenant.
     */
    fun requireAccessible(sessionId: String) {
        IdFormat.requireSessionId(sessionId)

        val context = AuthContextHolder.get() ?: return

        // Scheduler's task sessions are the ones the caller has no business naming.
        //
        // This is a prefix rule and not an ownership query, because a query cannot answer it: admin
        // resolves `task-` by parsing the prefix against the agent_task table, while the lookup below
        // only reads the `session` table. Every forged id of that shape comes back Unknown, and
        // Unknown passes — so the asymmetry between what this guard can see and what admin can resolve
        // was the hole.
        //
        // A caller with no end user behind it keeps access. Scheduler mints these ids itself and is the
        // one that decided who the human is; refusing it would stop scheduled runs outright. That is
        // the same ground the no-tenant pass below stands on, and the same `userId == null` the router
        // already trusts when it lets a service body carry the user it is acting for (see
        // AgentProxyController.resolveUserId).
        //
        // `chn-` is *not* refused here even though admin resolves it from the channel table for the
        // agent spec. Its id is a UUID no caller can enumerate, so a rule would stop nobody the
        // comparison below does not already stop — and the webui channel page's legitimate end-user
        // reads (sandbox status, workspace files) run on the visitor's user-bound key, which is exactly
        // what this rule would have denied. Those reads are guarded by the tenant comparison below,
        // which admin can now answer for `chn-`: same tenant passes, another tenant's channel session
        // does not. See [PrivilegedSessionPrefixes] for the full reasoning.
        //
        // `web-` and `mp-` are untouched: they are the caller's own sessions and still settle by the
        // tenant comparison.
        if (context.userId != null && PrivilegedSessionPrefixes.matches(sessionId)) {
            log.warn(
                "Rejected a privileged session prefix: caller '${context.callerId}' (user ${context.userId}) " +
                    "named $sessionId, which only an internal caller may",
            )
            throw SecurityException("Privileged session prefix requires an internal caller")
        }

        val callerTenant = context.tenantId ?: return

        when (val lookup = sessionInfoClient.lookup(sessionId)) {
            is AdminClientService.SessionLookup.Found -> {
                // A null owner means admin recorded no tenant for the row at all — nothing to compare,
                // so this stays a pass rather than inventing a denial out of a missing column.
                //
                // That is why a `channel` row with no tenant stamped is *not* reported as null: admin
                // answers with the row's own value, so a `tenant_id` of 0 lands here as 0 and every
                // tenant-bearing caller is a cross-tenant caller against it. Collapsing it to null would
                // hand exactly the free pass this comparison exists to remove, to the one shape of row
                // that cannot prove an owner. Such a channel stays unreadable through the router until an
                // operator gives it a tenant, and admin logs a warning naming it when asked.
                val ownerTenant = lookup.info.tenantId
                if (ownerTenant != null && ownerTenant != callerTenant) {
                    log.warn(
                        "Rejected a cross-tenant session access: caller '${context.callerId}' (tenant $callerTenant) " +
                            "asked for a session owned by tenant $ownerTenant",
                    )
                    throw SecurityException("Session belongs to another tenant")
                }
            }

            // Admin answers a `chn-` id from the `channel` row whatever its active flag says, so Unknown
            // for one of those is no longer "a deleted channel" — that row still reports its tenant and
            // the comparison above refuses a foreign caller. What is left is an id with no channel row at
            // all, and since a `chn-` id is minted with the row that creates it, that is an id admin never
            // issued. Still a pass, and still cached for five minutes by [SessionInfoClient], which is the
            // window a rollout leaves open for an id that was asked about while the answer was Unknown.
            AdminClientService.SessionLookup.Unknown ->
                log.debug("Session $sessionId is not known to admin; ownership could not be checked for caller '${context.callerId}'")

            AdminClientService.SessionLookup.Unreachable ->
                log.warn("Admin unreachable: session ownership for $sessionId could not be enforced for caller '${context.callerId}'")
        }
    }
}

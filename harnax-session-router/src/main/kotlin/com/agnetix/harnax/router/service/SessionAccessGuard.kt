package com.agnetix.harnax.router.service

import com.agnetix.harnax.auth.AuthContextHolder
import com.agnetix.harnax.router.support.IdFormat
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
 * - A session admin cannot be asked about. Unknown-to-admin is still reported, because it is the one
 *   state where this guard is knowingly not in force.
 *
 * A session admin says does not exist passes too, and is not a hole: nothing is bound to it, so the
 * proxy endpoints answer "not bound" without touching an agent — and the tenant that owns it cannot be
 * proven either way.
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
        val callerTenant = context.tenantId ?: return

        when (val lookup = sessionInfoClient.lookup(sessionId)) {
            is AdminClientService.SessionLookup.Found -> {
                val ownerTenant = lookup.info.tenantId
                if (ownerTenant != null && ownerTenant != callerTenant) {
                    log.warn(
                        "Rejected a cross-tenant session access: caller '${context.callerId}' (tenant $callerTenant) " +
                            "asked for a session owned by tenant $ownerTenant",
                    )
                    throw SecurityException("Session belongs to another tenant")
                }
            }

            AdminClientService.SessionLookup.Unknown ->
                log.debug("Session $sessionId is not known to admin; ownership could not be checked for caller '${context.callerId}'")

            AdminClientService.SessionLookup.Unreachable ->
                log.warn("Admin unreachable: session ownership for $sessionId could not be enforced for caller '${context.callerId}'")
        }
    }
}

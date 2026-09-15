package com.agnetix.harnax.router.service

import com.agnetix.harnax.auth.AuthContext
import com.agnetix.harnax.auth.AuthContextHolder
import com.agnetix.harnax.auth.CallerType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

/**
 * The guard has two directions to get wrong: letting one tenant read another's session, and turning
 * a refusal into an outage by denying whenever admin is not looking. Both are pinned here.
 */
class SessionAccessGuardTest {

    private lateinit var sessionInfoClient: SessionInfoClient
    private lateinit var guard: SessionAccessGuard

    @BeforeEach
    fun setUp() {
        sessionInfoClient = mock(SessionInfoClient::class.java)
        guard = SessionAccessGuard(sessionInfoClient)
    }

    @AfterEach
    fun tearDown() {
        AuthContextHolder.clear()
    }

    private fun stubLookup(sessionId: String, lookup: AdminClientService.SessionLookup) {
        `when`(sessionInfoClient.lookup(sessionId)).thenReturn(lookup)
    }

    private fun sessionOf(tenantId: Long?) = AdminClientService.SessionInfo(
        sessionId = "web-1",
        agentId = 7L,
        tenantId = tenantId,
    )

    @Test
    fun `a malformed id is refused before anything is looked up`() {
        assertThrows(IllegalArgumentException::class.java) {
            guard.requireAccessible("../../admin/internal/sessions/x/info")
        }
        verifyNoInteractions(sessionInfoClient)
    }

    @Test
    fun `an unauthenticated caller passes`() {
        // Inbound authentication is a separate gate; with no context there is no tenant to compare,
        // and denying here would only push operators to switch authentication off.
        guard.requireAccessible("web-1")
        verifyNoInteractions(sessionInfoClient)
    }

    @Test
    fun `a caller with no tenant passes without asking admin`() {
        AuthContextHolder.set(
            AuthContext(
                callerId = "agent-service",
                callerType = CallerType.INTERNAL_SERVICE,
                tenantId = null,
            ),
        )
        guard.requireAccessible("web-1")
        verifyNoInteractions(sessionInfoClient)
    }

    @Test
    fun `the owning tenant is granted access`() {
        AuthContextHolder.set(AuthContext(callerId = "user-9", tenantId = 3L))
        stubLookup("web-1", AdminClientService.SessionLookup.Found(sessionOf(tenantId = 3L)))
        guard.requireAccessible("web-1")
    }

    @Test
    fun `another tenant is denied`() {
        AuthContextHolder.set(AuthContext(callerId = "user-9", tenantId = 3L))
        stubLookup("web-1", AdminClientService.SessionLookup.Found(sessionOf(tenantId = 4L)))
        val e = assertThrows(SecurityException::class.java) {
            guard.requireAccessible("web-1")
        }
        assertEquals("Session belongs to another tenant", e.message)
    }

    @Test
    fun `a session admin has no tenant recorded for is not treated as someone else's`() {
        AuthContextHolder.set(AuthContext(callerId = "user-9", tenantId = 3L))
        stubLookup("web-1", AdminClientService.SessionLookup.Found(sessionOf(tenantId = null)))
        guard.requireAccessible("web-1")
    }

    @Test
    fun `an unknown session passes because nothing is bound to it`() {
        AuthContextHolder.set(AuthContext(callerId = "user-9", tenantId = 3L))
        stubLookup("web-1", AdminClientService.SessionLookup.Unknown)
        guard.requireAccessible("web-1")
    }

    @Test
    fun `admin being unreachable does not take the router down`() {
        AuthContextHolder.set(AuthContext(callerId = "user-9", tenantId = 3L))
        stubLookup("web-1", AdminClientService.SessionLookup.Unreachable)
        guard.requireAccessible("web-1")
    }

    // ==================== Privileged prefixes ====================
    //
    // `task-` names a conversation the *server* decides: scheduler mints it, and it does not live in any
    // table admin's ownership endpoint consults. That makes it invisible to the lookup below — which
    // answers Unknown for it and passes — so the lookup can never be what stops an end user from naming
    // one. Only a prefix rule decided before the lookup can. `chn-` is the counter-example: it is just
    // as server-decided in shape, but admin answers its tenant from `channel`, so the ordinary
    // comparison below guards it and no rule is needed. See PrivilegedSessionPrefixes.

    @Test
    fun `an end-user caller cannot name a task session`() {
        AuthContextHolder.set(AuthContext(callerId = "user-9", userId = 9L, tenantId = 3L))

        val e = assertThrows(SecurityException::class.java) {
            guard.requireAccessible("task-7-6f1d0a2e")
        }

        assertEquals("Privileged session prefix requires an internal caller", e.message)
        // The lookup could not have helped: admin never heard of this id, so asking it is what used to
        // turn the forgery into a pass.
        verifyNoInteractions(sessionInfoClient)
    }

    @Test
    fun `an end user reading a channel session is not refused by the prefix rule`() {
        // The webui channel-admin page reads sandbox status and workspace files of `chn-{uuid}` sessions
        // using the visitor's own user-bound key, so `userId != null` is what that legitimate traffic
        // looks like. Refusing it by prefix took a live read path offline — and it is still what the
        // rule does *not* do now that admin can answer the owner, because the owner is what decides.
        //
        // This is the shape of the page's own request: a channel session in the visitor's tenant. The
        // answer comes from the `channel` row, and same tenant means same tenant.
        val sessionId = "chn-da0b56ff-c712-4bb6-8536-3b3e88b1818b"
        AuthContextHolder.set(AuthContext(callerId = "user-9", userId = 9L, tenantId = 3L))
        stubLookup(sessionId, AdminClientService.SessionLookup.Found(sessionOf(tenantId = 3L)))

        guard.requireAccessible(sessionId)

        // Reaching the lookup is the point: the prefix rule does not short-circuit this caller, and the
        // tenant comparison it reaches has nothing to object to.
        verify(sessionInfoClient).lookup(sessionId)
    }

    @Test
    fun `a channel session still settles by the tenant comparison`() {
        // The prefix rule staying off `chn-` is not a free pass: admin answers these ids from the
        // `channel` table now, so an end user is denied another tenant's channel session exactly as they
        // are denied another tenant's `web-` one. This case was written against F3-A before admin could
        // answer it; it is the reason the lookup was taught the channel table.
        val sessionId = "chn-da0b56ff-c712-4bb6-8536-3b3e88b1818b"
        AuthContextHolder.set(AuthContext(callerId = "user-9", userId = 9L, tenantId = 3L))
        stubLookup(sessionId, AdminClientService.SessionLookup.Found(sessionOf(tenantId = 4L)))

        val e = assertThrows(SecurityException::class.java) {
            guard.requireAccessible(sessionId)
        }
        assertEquals("Session belongs to another tenant", e.message)
    }

    @Test
    fun `a channel row with no tenant stamped is not a free pass either`() {
        // `tenant_id` 0 on a channel row is not "no owner", and admin reports the row's own value
        // instead of collapsing it to null — null is precisely what the comparison below reads as
        // "cannot be judged" and passes. A tenant-less channel is therefore refused to every tenant
        // caller until an operator gives it one.
        val sessionId = "chn-11111111-2222-3333-4444-555555555555"
        AuthContextHolder.set(AuthContext(callerId = "user-9", userId = 9L, tenantId = 3L))
        stubLookup(sessionId, AdminClientService.SessionLookup.Found(sessionOf(tenantId = 0L)))

        val e = assertThrows(SecurityException::class.java) {
            guard.requireAccessible(sessionId)
        }
        assertEquals("Session belongs to another tenant", e.message)
    }

    @Test
    fun `an end user's own web session still goes through the ownership lookup`() {
        // The prefix rule is a short list, not a general tightening: everything the session table knows
        // about must keep routing exactly as before.
        AuthContextHolder.set(AuthContext(callerId = "user-9", userId = 9L, tenantId = 3L))
        stubLookup("web-1", AdminClientService.SessionLookup.Found(sessionOf(tenantId = 3L)))

        guard.requireAccessible("web-1")

        verify(sessionInfoClient).lookup("web-1")
    }

    @Test
    fun `a caller with no end user behind it still opens a task session`() {
        // Scheduler and channel-service reach the router on a SYSTEM key, whose `userId` is null, and
        // that is what carries their legitimate task-/chn- traffic. Denying here would stop scheduled
        // runs — the fix must not become the outage it prevents.
        AuthContextHolder.set(AuthContext(callerId = "scheduler", userId = null, tenantId = null))

        guard.requireAccessible("task-7-6f1d0a2e")

        verifyNoInteractions(sessionInfoClient)
    }

    @Test
    fun `a tenant-bearing service caller is judged on the end user, not on its tenant`() {
        // The rule keys on `userId`, so a service token that names a tenant still routes its own
        // sessions and goes on with the ordinary tenant comparison below; pinning this keeps a later
        // tenant-based rewrite from quietly narrowing the pass to tenant-less callers only.
        val sessionId = "chn-da0b56ff-c712-4bb6-8536-3b3e88b1818b"
        AuthContextHolder.set(AuthContext(callerId = "channel-service", userId = null, tenantId = 3L))
        // Unknown for a chn- id now means "no active channel row" — a deleted channel, or one nobody
        // minted — and that stays a pass.
        stubLookup(sessionId, AdminClientService.SessionLookup.Unknown)

        guard.requireAccessible(sessionId)

        // Reaching the lookup is the point: the prefix rule let this caller through to the ordinary
        // ownership check instead of refusing it.
        verify(sessionInfoClient).lookup(sessionId)
    }
}

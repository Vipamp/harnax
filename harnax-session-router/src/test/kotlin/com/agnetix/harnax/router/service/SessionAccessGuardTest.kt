package com.agnetix.harnax.router.service

import com.agnetix.harnax.auth.AuthContext
import com.agnetix.harnax.auth.AuthContextHolder
import com.agnetix.harnax.auth.CallerType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
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
}

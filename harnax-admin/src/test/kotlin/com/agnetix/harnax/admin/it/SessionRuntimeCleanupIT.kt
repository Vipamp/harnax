package com.agnetix.harnax.admin.it

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a deleted session leaves behind in the runtime (AGENT-08's remaining half).
 *
 * The row is only one part of a conversation: the stored agent state, the plan notes and the sandbox
 * container live with whichever agent-service instance served the session. admin cannot reach any of that,
 * so it asks the router — and the unit layer can only prove it asked, not that the ask went out on the path
 * the router actually serves, through this application's real filters. That is what this class runs over
 * [FakeRouter], on a real MySQL 8.
 *
 * The other half of the contract is the refusal: a runtime that could not let go has to keep the session,
 * because a row pointing at nothing while the state lives on is worse than a session that can be deleted
 * again later.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SessionRuntimeCleanupIT : BaseAdminIT() {

    private val suffix = Random.nextInt(100000, 999999)
    private val agentName = "it_runtime_agent_$suffix"

    private var agentId: Long = -1

    @BeforeEach
    fun resetRouter() {
        fakeRouter.reset()
    }

    private fun ensureAgent(): Long {
        if (agentId > 0) return agentId
        assertOk(postJson("/api/admin/agents", agentCreateBody(agentName)))
        val record = findInPage("/api/admin/agents/page", "name=$agentName") {
            it["name"]?.asText() == agentName
        }
        assertNotNull(record, "prerequisite agent should exist")
        agentId = record["id"].asLong()
        return agentId
    }

    /** A session of this tenant, created through the API, returning its row id and its `web-…` id. */
    private fun createSession(title: String): Pair<Long, String> {
        assertOk(postJson("/api/admin/sessions", mapOf("title" to title, "agentId" to ensureAgent())))
        val record = findInPage("/api/admin/sessions/page", "keyword=$title") {
            it["title"]?.asText() == title
        }
        assertNotNull(record, "created session should be found: $title")
        return record["id"].asLong() to record["sessionId"].asText()
    }

    @Test
    @Order(1)
    @DisplayName("deleting a session releases it on the runtime side first, using the session's own id")
    fun deleteReleasesRuntimeState() {
        val (id, sessionId) = createSession("it_runtime_delete_$suffix")

        assertOk(deleteJson("/api/admin/sessions/$id"))

        // The runtime keys everything by `web-…`, not by the row id, so a release naming the row would
        // clear nothing and still answer "deleted".
        assertEquals(listOf(sessionId), clearedSessions())
        assertAbsent(id)
    }

    @Test
    @Order(2)
    @DisplayName("when the runtime fails to release, the session stays and the reason reaches the caller")
    fun refusalKeepsTheSession() {
        val (id, sessionId) = createSession("it_runtime_refuse_$suffix")
        fakeRouter.refusal = "sandbox container is busy"

        val refused = deleteJson("/api/admin/sessions/$id")
        assertTrue(refused["code"].asInt() != 200, "a runtime refusal must fail the delete: $refused")
        assertTrue(
            refused["message"].asText().contains("sandbox container is busy"),
            "the runtime's own reason must reach the caller, got: ${refused["message"].asText()}",
        )
        assertEquals(listOf(sessionId), clearedSessions(), "the release was attempted once, and it is what refused")

        // Still there, still readable — and deletable once the runtime stops refusing.
        assertEquals(sessionId, assertOk(getJson("/api/admin/sessions/$id"))["sessionId"].asText())
        fakeRouter.refusal = null
        assertOk(deleteJson("/api/admin/sessions/$id"))
        assertAbsent(id)
    }

    @Test
    @Order(3)
    @DisplayName("when no session can be named, the runtime side stays untouched")
    fun unknownSessionNeverReachesTheRuntime() {
        val refused = deleteJson("/api/admin/sessions/999999999")

        // The ownership gate is admin's, and it has to hold before the release: asking the runtime about an
        // id this caller cannot name would hand it a probe of someone else's session.
        assertTrue(clearedSessions().isEmpty(), "no release may go out at all: ${clearedSessions()}")
        assertTrue(refused["code"].asInt() != 200, "a session that cannot be named cannot be deleted: $refused")
        assertEquals("Session not found", refused["message"].asText())
    }

    /** A row this service has let go of: the by-id read says so with a 404 and carries nothing. */
    private fun assertAbsent(id: Long) {
        val node = getJson("/api/admin/sessions/$id")
        assertEquals(404, node["code"].asInt(), "a session we cannot read must not answer as success: $node")
        assertTrue(node["data"] == null || node["data"].isNull, "a deleted session must not be readable any more: $node")
    }
}

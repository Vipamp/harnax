package com.agnetix.harnax.admin.it

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
 * Session CRUD regression: /api/admin/sessions
 *
 * Sessions require an existing agent, so one is created up front and removed
 * after the session lifecycle has been verified.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SessionCrudIT : BaseAdminIT() {

    private val suffix = Random.nextInt(100000, 999999)
    private val agentName = "it_sess_agent_$suffix"
    private val sessionTitle = "it_session_$suffix"

    private var agentId: Long = -1
    private var sessionId: Long = -1
    private var sessionUuid: String = ""

    private fun ensureAgent(): Long {
        if (agentId > 0) return agentId
        assertOk(postJson("/api/admin/agents", mapOf("name" to agentName, "status" to 1)))
        val record = findInPage("/api/admin/agents/page", "name=$agentName") {
            it["name"]?.asText() == agentName
        }
        assertNotNull(record, "prerequisite agent should exist")
        agentId = record["id"].asLong()
        return agentId
    }

    private fun locateSession(): Long {
        if (sessionId > 0) return sessionId
        val record = findInPage("/api/admin/sessions/page", "keyword=$sessionTitle") {
            it["title"]?.asText() == sessionTitle
        }
        assertNotNull(record, "created session should be found in page result")
        sessionId = record["id"].asLong()
        sessionUuid = record["sessionId"].asText()
        return sessionId
    }

    @Test
    @Order(1)
    fun `create session succeeds`() {
        val body = mapOf(
            "title" to sessionTitle,
            "sessionDescription" to "IT session",
            "agentId" to ensureAgent(),
        )
        assertOk(postJson("/api/admin/sessions", body))
    }

    @Test
    @Order(2)
    fun `page query finds created session`() {
        assertTrue(locateSession() > 0)
        assertTrue(sessionUuid.startsWith("web-"), "session uuid should have web- prefix: $sessionUuid")
    }

    @Test
    @Order(3)
    fun `get detail returns created session`() {
        val data = assertOk(getJson("/api/admin/sessions/${locateSession()}"))
        assertEquals(sessionTitle, data["title"].asText())
        assertEquals(agentId, data["agentId"].asLong())
        assertEquals(agentName, data["name"].asText())
        assertEquals(1, data["status"].asInt())
    }

    @Test
    @Order(4)
    fun `check title reports existence`() {
        val exists = assertOk(getJson("/api/admin/sessions/check-title?title=$sessionTitle"))
        assertTrue(exists.asBoolean())
        val notExists = assertOk(getJson("/api/admin/sessions/check-title?title=it_no_such_session_$suffix"))
        assertTrue(!notExists.asBoolean())
    }

    @Test
    @Order(5)
    fun `create session with duplicate title fails`() {
        val body = mapOf("title" to sessionTitle, "agentId" to ensureAgent())
        assertErr(postJson("/api/admin/sessions", body))
    }

    @Test
    @Order(6)
    fun `create session without title returns 400`() {
        assertErr(postJson("/api/admin/sessions", mapOf("title" to "", "agentId" to ensureAgent())))
    }

    @Test
    @Order(7)
    fun `update session changes title`() {
        val body = mapOf(
            "title" to "$sessionTitle-upd",
            "sessionDescription" to "IT session updated",
            "agentId" to ensureAgent(),
        )
        assertOk(putJson("/api/admin/sessions/update/${locateSession()}", body))

        val data = assertOk(getJson("/api/admin/sessions/$sessionId"))
        assertEquals("$sessionTitle-upd", data["title"].asText())
    }

    @Test
    @Order(8)
    fun `get and update session chat config`() {
        locateSession()
        val before = assertOk(getJson("/api/admin/sessions/$sessionUuid/config"))
        assertEquals(sessionId, before["id"].asLong())

        val body = mapOf(
            "enableThink" to true,
            "enableSearch" to true,
            "permissionMode" to "ACCEPT_EDITS",
        )
        assertOk(putJson("/api/admin/sessions/$sessionUuid/config", body))

        val after = assertOk(getJson("/api/admin/sessions/$sessionUuid/config"))
        assertEquals(1, after["enableThink"].asInt())
        assertEquals(1, after["enableSearch"].asInt())
        assertEquals("ACCEPT_EDITS", after["permissionMode"].asText())
    }

    @Test
    @Order(9)
    fun `update config of unknown session fails`() {
        assertErr(putJson("/api/admin/sessions/web-no-such-$suffix/config", mapOf("enableThink" to true)))
    }

    @Test
    @Order(10)
    fun `toggle session status off and on`() {
        assertOk(putJson("/api/admin/sessions/toggle/${locateSession()}?status=0"))
        var data = assertOk(getJson("/api/admin/sessions/$sessionId"))
        assertEquals(0, data["status"].asInt())

        assertOk(putJson("/api/admin/sessions/toggle/$sessionId?status=1"))
        data = assertOk(getJson("/api/admin/sessions/$sessionId"))
        assertEquals(1, data["status"].asInt())
    }

    @Test
    @Order(11)
    fun `delete session then detail returns empty`() {
        assertOk(deleteJson("/api/admin/sessions/${locateSession()}"))

        val node = getJson("/api/admin/sessions/$sessionId")
        assertEquals(200, node["code"].asInt())
        assertTrue(node["data"] == null || node["data"].isNull, "deleted session should not be returned")

        // Cleanup prerequisite agent
        assertOk(deleteJson("/api/admin/agents/$agentId"))
    }
}

package com.agnetix.harnax.admin.it

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Session chat config reads and writes (AGENT-26): the pair of endpoints under
 * `/api/admin/sessions/{sessionId}/config` resolved the row by its runtime id only, so a sessionId
 * surfacing in a channel binding, a runtime log or a team artifact path was readable and writable by
 * any signed in caller — model, system prompt and permission mode included. The same controller's
 * by-id read had been judging ownership all along, which is what makes the gap a bug and not a rule.
 *
 * Disabling is `status = 0` and deleting is `active = 0`, two columns with two answers: a disabled
 * conversation says so, a deleted one is indistinguishable from an id nobody ever held.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SessionChatConfigAccessIT : BaseAdminIT() {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private val suffix = Random.nextInt(100000, 999999)
    private val agentName = "it_cfg_agent_$suffix"
    private val sessionTitle = "it_cfg_session_$suffix"

    /** A tenant that exists nowhere else, so these rows cannot collide with another class's. */
    private val otherTenant = 940_005L

    /** Resolves to no row anywhere, the yardstick every refusal below is compared against. */
    private val unknownSessionId = "web-no-such-$suffix"

    private var agentId: Long = -1
    private var rowId: Long = -1
    private var sessionId: String = ""

    /** Captured before any refusal is attempted, so a landed write cannot be mistaken for a no-op. */
    private var initialPermissionMode: String = ""

    private fun configPath(target: String = sessionId) = "/api/admin/sessions/$target/config"

    private fun permissionMode(): String = jdbc.queryForObject(
        "SELECT permission_mode FROM session WHERE id = ?",
        String::class.java,
        rowId,
    ) ?: ""

    /** The three lines an absent id answers with; a row this caller may not read has to match them. */
    private fun assertAnswersAsAbsent(node: JsonNode) {
        // The miss travels in the envelope, so the answer may not turn into an HTTP error status.
        assertEquals(404, node["code"].asInt(), "a session this caller cannot read is a 404, not a server fault")
        val text = node["message"].asText()
        assertNotEquals("error.session.notfound", text, "the refusal must be the bundle's text, not the key")
        assertTrue(text.isNotBlank(), "the refusal has to say what happened")
        assertTrue(node["data"] == null || node["data"].isNull, "an unreadable session must not carry a configuration")
    }

    @Test
    @Order(1)
    fun `the config of an enabled session of my own tenant reads back`() {
        assertOk(postJson("/api/admin/agents", agentCreateBody(agentName)))
        val agent = findInPage("/api/admin/agents/page", "name=$agentName") { it["name"]?.asText() == agentName }
        agentId = requireNotNull(agent) { "prerequisite agent should exist" }["id"].asLong()

        assertOk(postJson("/api/admin/sessions", mapOf("title" to sessionTitle, "agentId" to agentId)))
        val record = findInPage("/api/admin/sessions/page", "keyword=$sessionTitle") { it["title"]?.asText() == sessionTitle }
        requireNotNull(record) { "created session should be found in the page result" }
        rowId = record["id"].asLong()
        sessionId = record["sessionId"].asText()
        initialPermissionMode = permissionMode()

        val data = assertOk(getJson(configPath()))
        assertEquals(rowId, data["id"].asLong())
        assertEquals(1, data["status"].asInt())
    }

    @Test
    @Order(2)
    fun `a disabled session is named as disabled rather than as missing`() {
        assertOk(putJson("/api/admin/sessions/toggle/$rowId?status=0"))

        val read = parseBody(exchange(HttpMethod.GET, configPath()))
        assertErr(read)
        assertEquals(403, read["code"].asInt(), "disabling is not deleting, so the answer may not be the absent one")
        // getMessage echoes the key itself when the bundle lacks it, which would leave a test asserting
        // the key passing on an untranslated response — so pin that the text resolved.
        assertNotEquals("error.session.disabled", read["message"].asText(), "the refusal must be the bundle's text, not the key")
        assertTrue(read["data"] == null || read["data"].isNull, "a disabled session must not answer with a configuration")

        val write = parseBody(exchange(HttpMethod.PUT, configPath(), mapOf("permissionMode" to "BYPASS")))
        assertErr(write)
        assertEquals(403, write["code"].asInt(), "a disabled session is not configurable either")
        assertEquals(initialPermissionMode, permissionMode(), "a refused write must not have landed")

        assertOk(putJson("/api/admin/sessions/toggle/$rowId?status=1"))
        assertOk(getJson(configPath()))
    }

    @Test
    @Order(3)
    fun `another tenant's session reads and writes as the absent one it is to this caller`() {
        val absent = parseBody(exchange(HttpMethod.GET, configPath(unknownSessionId)))
        val absentWrite = parseBody(
            exchange(HttpMethod.PUT, configPath(unknownSessionId), mapOf("permissionMode" to "BYPASS")),
        )
        assertErr(absentWrite)

        jdbc.update("UPDATE session SET tenant_id = ? WHERE id = ?", otherTenant, rowId)
        try {
            val hidden = parseBody(exchange(HttpMethod.GET, configPath()))
            assertEquals(absent["code"].asInt(), hidden["code"].asInt(), "another tenant's session must not read differently from a missing one")
            assertEquals(absent["message"].asText(), hidden["message"].asText(), "the refusal must not reveal that the session exists at all")
            assertAnswersAsAbsent(hidden)

            val hijack = parseBody(exchange(HttpMethod.PUT, configPath(), mapOf("permissionMode" to "BYPASS")))
            assertEquals(absentWrite["code"].asInt(), hijack["code"].asInt(), "the write must refuse a foreign session as it refuses an unknown id")
            assertEquals(absentWrite["message"].asText(), hijack["message"].asText(), "a refused write must not name the session it found")
            assertEquals(initialPermissionMode, permissionMode(), "another tenant's session must not be configurable from here")

            // The owning tenant still reads it, so the refusals above came from the tenant and not from
            // anything about the request.
            assertOk(parseBody(exchange(HttpMethod.GET, configPath(), tenantId = otherTenant)))
        } finally {
            jdbc.update("UPDATE session SET tenant_id = 1 WHERE id = ?", rowId)
        }
    }

    @Test
    @Order(4)
    fun `a deleted session answers as the absent one and cleanup takes the fixture with it`() {
        val absent = parseBody(exchange(HttpMethod.GET, configPath(unknownSessionId)))

        assertOk(deleteJson("/api/admin/sessions/$rowId"))

        val gone = parseBody(exchange(HttpMethod.GET, configPath()))
        assertEquals(absent["code"].asInt(), gone["code"].asInt(), "a deleted session must answer like an id that never existed")
        assertEquals(absent["message"].asText(), gone["message"].asText(), "deletion is the absent case; the disabled case is above")
        assertAnswersAsAbsent(gone)

        assertOk(deleteJson("/api/admin/agents/$agentId"))
    }
}

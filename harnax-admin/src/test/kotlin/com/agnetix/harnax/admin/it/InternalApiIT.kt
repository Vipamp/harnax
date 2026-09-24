package com.agnetix.harnax.admin.it

import com.agnetix.harnax.mapper.ApiKeyMapper
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Internal service API regression: /api/admin/internal endpoints.
 *
 * These endpoints are consumed by other harnax services (router, channel,
 * agent-service) and are guarded by InternalApiAuthFilter with a shared secret
 * (admin.internal-api.secret, fixed in application-it.yml) instead of JWT.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class InternalApiIT : BaseAdminIT() {

    /** Must match admin.internal-api.secret in application-it.yml. */
    private val internalSecret = "it-internal-api-secret-0123456789abcdef"

    @Autowired
    private lateinit var apiKeyMapper: ApiKeyMapper

    private val suffix = Random.nextInt(100000, 999999)
    private val agentName = "it_internal_agent_$suffix"
    private val sessionTitle = "it_internal_session_$suffix"

    private var agentId: Long = -1
    private var sessionRowId: Long = -1
    private var sessionUuid: String = ""
    private var channelId: Long = -1

    private fun ensureSession(): String {
        if (sessionUuid.isNotEmpty()) return sessionUuid
        assertOk(postJson("/api/admin/agents", agentCreateBody(agentName)))
        val agent = findInPage("/api/admin/agents/page", "name=$agentName") {
            it["name"]?.asText() == agentName
        }
        assertNotNull(agent, "prerequisite agent should exist")
        agentId = agent["id"].asLong()

        assertOk(postJson("/api/admin/sessions", mapOf("title" to sessionTitle, "agentId" to agentId)))
        val session = findInPage("/api/admin/sessions/page", "keyword=$sessionTitle") {
            it["title"]?.asText() == sessionTitle
        }
        assertNotNull(session, "prerequisite session should exist")
        sessionRowId = session["id"].asLong()
        sessionUuid = session["sessionId"].asText()
        return sessionUuid
    }

    @Test
    @Order(1)
    fun `internal endpoint without secret returns 401`() {
        val noToken = exchange(HttpMethod.GET, "/api/admin/internal/sessions/web-x/info", token = null)
        assertEquals(401, noToken.statusCode.value())

        val wrongToken = exchange(HttpMethod.GET, "/api/admin/internal/sessions/web-x/info", token = "wrong-secret")
        assertEquals(401, wrongToken.statusCode.value())
    }

    @Test
    @Order(2)
    fun `admin JWT is not accepted as internal secret`() {
        val response = exchange(HttpMethod.GET, "/api/admin/internal/sessions/web-x/info", token = adminToken())
        assertEquals(401, response.statusCode.value())
    }

    @Test
    @Order(3)
    fun `validate api key returns admin permanent key info`() {
        val permanentKey = apiKeyMapper.selectPermanentKeyByUserId(1L)
        assertNotNull(permanentKey, "admin permanent key should exist (created on startup)")

        val node = parseBody(
            exchange(
                HttpMethod.POST,
                "/api/admin/internal/api-keys/validate",
                mapOf("keyHash" to permanentKey.keyHash),
                internalSecret,
            ),
        )
        val data = assertOk(node)
        assertEquals(permanentKey.keyHash, data["keyHash"].asText())
        assertTrue(data["enabled"].asBoolean())
    }

    @Test
    @Order(4)
    fun `validate api key with unknown hash returns null data`() {
        val node = parseBody(
            exchange(
                HttpMethod.POST,
                "/api/admin/internal/api-keys/validate",
                mapOf("keyHash" to "no-such-hash-$suffix"),
                internalSecret,
            ),
        )
        assertEquals(200, node["code"].asInt())
        assertTrue(node["data"] == null || node["data"].isNull)
    }

    @Test
    @Order(5)
    fun `session info resolves created session`() {
        val uuid = ensureSession()
        val node = parseBody(
            exchange(HttpMethod.GET, "/api/admin/internal/sessions/$uuid/info", token = internalSecret),
        )
        val data = assertOk(node)
        assertEquals(uuid, data["sessionId"].asText())
        assertEquals(agentId, data["agentId"].asLong())
    }

    @Test
    @Order(6)
    fun `agent spec resolves from web session`() {
        val uuid = ensureSession()
        val node = parseBody(
            exchange(HttpMethod.GET, "/api/admin/internal/agent-spec/$uuid", token = internalSecret),
        )
        val data = assertOk(node)
        assertEquals(agentId, data["agentId"].asLong())
        assertEquals(agentName, data["agentName"].asText())
        assertEquals("[]", data["toolList"].asText())
        assertEquals("[]", data["mcpList"].asText())
    }

    @Test
    @Order(7)
    fun `agent spec with unknown prefix fails`() {
        val node = parseBody(
            exchange(HttpMethod.GET, "/api/admin/internal/agent-spec/bogus-$suffix", token = internalSecret),
        )
        assertErr(node)
    }

    @Test
    @Order(8)
    fun `toggle capability updates session config`() {
        val uuid = ensureSession()
        val node = parseBody(
            exchange(
                HttpMethod.PUT,
                "/api/admin/internal/sessions/$uuid/capabilities",
                mapOf("capability" to "search", "enable" to true),
                internalSecret,
            ),
        )
        assertOk(node)

        val config = assertOk(getJson("/api/admin/sessions/$uuid/config"))
        assertEquals(1, config["enableSearch"].asInt())
    }

    @Test
    @Order(9)
    fun `toggle unknown capability fails`() {
        val uuid = ensureSession()
        val node = parseBody(
            exchange(
                HttpMethod.PUT,
                "/api/admin/internal/sessions/$uuid/capabilities",
                mapOf("capability" to "teleport", "enable" to true),
                internalSecret,
            ),
        )
        assertErr(node)
    }

    @Test
    @Order(10)
    fun `update permission mode validates and persists`() {
        val uuid = ensureSession()

        val invalid = parseBody(
            exchange(
                HttpMethod.PUT,
                "/api/admin/internal/sessions/$uuid/permission-mode",
                mapOf("mode" to "NOT_A_MODE"),
                internalSecret,
            ),
        )
        assertErr(invalid)

        val valid = parseBody(
            exchange(
                HttpMethod.PUT,
                "/api/admin/internal/sessions/$uuid/permission-mode",
                mapOf("mode" to "ACCEPT_EDITS"),
                internalSecret,
            ),
        )
        assertOk(valid)

        val config = assertOk(getJson("/api/admin/sessions/$uuid/config"))
        assertEquals("ACCEPT_EDITS", config["permissionMode"].asText())
    }

    @Test
    @Order(11)
    fun `session info answers the tenant that owns a channel session`() {
        ensureSession()
        val channelName = "it_internal_channel_$suffix"
        assertOk(
            postJson(
                "/api/admin/channels",
                mapOf(
                    "name" to channelName,
                    "type" to "http",
                    "agentId" to agentId,
                    "communicationMode" to "webhook",
                    "permissionMode" to "DEFAULT",
                    "enabled" to 1,
                    "description" to "IT chn ownership",
                    "status" to 1,
                ),
            ),
        )
        val record = findInPage("/api/admin/channels/page", "keyword=$channelName") {
            it["name"]?.asText() == channelName
        }
        assertNotNull(record, "prerequisite channel should exist")
        channelId = record["id"].asLong()

        val detail = assertOk(getJson("/api/admin/channels/$channelId"))
        val sessionId = detail["sessionId"].asText()
        assertTrue(sessionId.startsWith("chn-"), "channel sessions are chn-prefixed, was $sessionId")

        val data = assertOk(
            parseBody(
                exchange(HttpMethod.GET, "/api/admin/internal/sessions/$sessionId/info", token = internalSecret),
            ),
        )
        // The router compares this tenant against the one its caller carries, so the answer has to be the
        // channel row's own tenant. A `chn-` id used to come back with no answer at all — and no answer is
        // the pass that let one tenant read another's channel session.
        assertEquals(sessionId, data["sessionId"].asText())
        assertEquals(agentId, data["agentId"].asLong())
        assertEquals(detail["tenantId"].asLong(), data["tenantId"].asLong())

        // A `chn-` id with no channel row is still "unknown" rather than a denial. That is the endpoint's
        // not-found answer, not a first-contact case: a `chn-` id is minted together with its channel row
        // and a deleted one still answers with its tenant, so no row means an id this admin never issued.
        val missing = parseBody(
            exchange(
                HttpMethod.GET,
                "/api/admin/internal/sessions/chn-00000000-0000-0000-0000-000000000000/info",
                token = internalSecret,
            ),
        )
        assertEquals(200, missing["code"].asInt())
        assertTrue(missing["data"] == null || missing["data"].isNull, "an unknown chn- id must stay unknown")
    }

    @Test
    @Order(12)
    fun `cleanup session and agent`() {
        ensureSession()
        if (channelId > 0) {
            assertOk(deleteJson("/api/admin/channels/$channelId"))
        }
        assertOk(deleteJson("/api/admin/sessions/$sessionRowId"))
        assertOk(deleteJson("/api/admin/agents/$agentId"))
    }
}

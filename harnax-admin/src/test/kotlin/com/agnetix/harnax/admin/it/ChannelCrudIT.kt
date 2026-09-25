package com.agnetix.harnax.admin.it

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Channel CRUD regression: /api/admin/channels
 *
 * Channels require an existing agent, so one is created up front and removed
 * after the channel lifecycle has been verified.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ChannelCrudIT : BaseAdminIT() {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private val suffix = Random.nextInt(100000, 999999)
    private val agentName = "it_chan_agent_$suffix"
    private val channelName = "it_channel_$suffix"

    private var agentId: Long = -1
    private var channelId: Long = -1

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

    private fun locateChannelId(): Long {
        if (channelId > 0) return channelId
        val record = findInPage("/api/admin/channels/page", "keyword=$channelName") {
            it["name"]?.asText() == channelName
        }
        assertNotNull(record, "created channel should be found in page result")
        channelId = record["id"].asLong()
        return channelId
    }

    @Test
    @Order(1)
    fun `create channel succeeds`() {
        val body = mapOf(
            "name" to channelName,
            "type" to "http",
            "agentId" to ensureAgent(),
            "communicationMode" to "webhook",
            "permissionMode" to "DEFAULT",
            "enabled" to 1,
            "description" to "IT channel",
            "status" to 1,
        )
        assertOk(postJson("/api/admin/channels", body))
    }

    @Test
    @Order(2)
    fun `page query finds created channel`() {
        assertTrue(locateChannelId() > 0)

        val record = findInPage("/api/admin/channels/page", "keyword=$channelName") {
            it["name"]?.asText() == channelName
        }
        // The key alone is everything `POST /api/channel/callback/{key}` checks, and nothing on the
        // list page needs it, so a list read should not be a way to collect usable credentials.
        assertFalse(record!!.has("callbackKey"), "the list must not carry the callback credential")
    }

    @Test
    @Order(3)
    fun `get detail returns created channel without its callback credential`() {
        val data = assertOk(getJson("/api/admin/channels/${locateChannelId()}"))
        assertEquals(channelName, data["name"].asText())
        assertEquals("http", data["type"].asText())
        assertEquals(agentId, data["agentId"].asLong())
        assertEquals("webhook", data["communicationMode"].asText())
        assertTrue(data["sessionId"].asText().isNotBlank(), "sessionId should be generated")
        // What the operator pastes into the platform console is the URL, and the service builds that
        // URL from the stored key, so the bare field is just a second way to spend the credential.
        assertFalse(data.has("callbackKey"), "the response must not carry the callback credential")
        assertTrue(data["callbackUrl"].asText().isNotBlank(), "webhook channels still need the callback URL")
        // Redacting the field must not stop the key being generated — a channel with no key cannot be
        // addressed by its own callback.
        val storedKey = jdbc.queryForObject(
            "SELECT callback_key FROM channel WHERE id = ?",
            String::class.java,
            channelId,
        )!!
        assertTrue(
            storedKey.startsWith("http-") && storedKey.length > "http-".length,
            "the key is still generated and stored: $storedKey",
        )
    }

    @Test
    @Order(4)
    fun `create channel without required fields returns 400`() {
        assertErr(postJson("/api/admin/channels", mapOf("name" to "", "type" to "http", "agentId" to agentId)))
        assertErr(postJson("/api/admin/channels", mapOf("name" to "it_chan_no_type_$suffix", "type" to "", "agentId" to agentId)))
        assertErr(postJson("/api/admin/channels", mapOf("name" to "it_chan_no_agent_$suffix", "type" to "http")))
    }

    @Test
    @Order(5)
    fun `update channel changes description and permission mode`() {
        val body = mapOf(
            "description" to "IT channel updated",
            "permissionMode" to "ACCEPT_EDITS",
        )
        assertOk(putJson("/api/admin/channels/update/${locateChannelId()}", body))

        val data = assertOk(getJson("/api/admin/channels/$channelId"))
        assertEquals("IT channel updated", data["description"].asText())
        assertEquals("ACCEPT_EDITS", data["permissionMode"].asText())
    }

    @Test
    @Order(6)
    fun `config credentials read back masked and survive the edit form round trip`() {
        val name = "it_chan_secret_$suffix"
        val secret = "it-app-secret-$suffix"
        val body = mapOf(
            "name" to name,
            "type" to "feishu",
            "agentId" to ensureAgent(),
            "communicationMode" to "websocket",
            "configJson" to """{"appId":"cli-it-$suffix","appSecret":"$secret"}""",
        )
        assertOk(postJson("/api/admin/channels", body))
        val record = findInPage("/api/admin/channels/page", "keyword=$name") {
            it["name"]?.asText() == name
        }
        assertNotNull(record, "created channel should be found in page result")
        val id = record["id"].asLong()
        try {
            // The complaint this closes is "being able to list channels means being able to export every
            // credential", so the list is the surface to prove. A missing field would pass a bare
            // does-not-contain check, so the blob itself has to still be there.
            val listed = record["configJson"].asText()
            assertEquals("cli-it-$suffix", json.readTree(listed)["appId"].asText(), "the list still carries the config")
            assertFalse(listed.contains(secret), "the list must not carry the platform secret: $listed")

            val shown = assertOk(getJson("/api/admin/channels/$id"))["configJson"].asText()
            assertFalse(shown.contains(secret), "the detail must not carry the platform secret either: $shown")
            assertEquals("cli-it-$suffix", json.readTree(shown)["appId"].asText(), "an identifier is not a credential")

            // This is exactly what UpdateForm.tsx sends: the blob it was shown, merged, sent back whole.
            assertOk(putJson("/api/admin/channels/update/$id", mapOf("configJson" to shown, "description" to "echoed")))
            val stored = jdbc.queryForObject("SELECT config_json FROM channel WHERE id = ?", String::class.java, id)!!
            assertTrue(stored.contains(secret), "an echoed mask must keep the credential it stands for: $stored")
            assertEquals(
                "echoed",
                jdbc.queryForObject("SELECT description FROM channel WHERE id = ?", String::class.java, id),
                "the rest of the same save must still land",
            )
        } finally {
            deleteJson("/api/admin/channels/$id")
        }
    }

    @Test
    @Order(7)
    fun `toggle channel status off and on`() {
        assertOk(putJson("/api/admin/channels/toggle/${locateChannelId()}?status=0"))
        var data = assertOk(getJson("/api/admin/channels/$channelId"))
        assertEquals(0, data["status"].asInt())

        assertOk(putJson("/api/admin/channels/toggle/$channelId?status=1"))
        data = assertOk(getJson("/api/admin/channels/$channelId"))
        assertEquals(1, data["status"].asInt())
    }

    @Test
    @Order(8)
    fun `delete channel then detail reports not found`() {
        assertOk(deleteJson("/api/admin/channels/${locateChannelId()}"))

        val node = getJson("/api/admin/channels/$channelId")
        assertEquals(404, node["code"].asInt(), "a row we cannot read must not answer as success")
        assertTrue(node["data"] == null || node["data"].isNull, "deleted channel should not be returned")

        val record = findInPage("/api/admin/channels/page", "keyword=$channelName") {
            it["name"]?.asText() == channelName
        }
        assertTrue(record == null, "deleted channel should not appear in page result")

        // Cleanup prerequisite agent
        assertOk(deleteJson("/api/admin/agents/$agentId"))
    }
}

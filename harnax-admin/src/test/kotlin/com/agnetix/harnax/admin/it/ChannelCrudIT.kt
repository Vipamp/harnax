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
 * Channel CRUD regression: /api/admin/channels
 *
 * Channels require an existing agent, so one is created up front and removed
 * after the channel lifecycle has been verified.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ChannelCrudIT : BaseAdminIT() {

    private val suffix = Random.nextInt(100000, 999999)
    private val agentName = "it_chan_agent_$suffix"
    private val channelName = "it_channel_$suffix"

    private var agentId: Long = -1
    private var channelId: Long = -1

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
    }

    @Test
    @Order(3)
    fun `get detail returns created channel`() {
        val data = assertOk(getJson("/api/admin/channels/${locateChannelId()}"))
        assertEquals(channelName, data["name"].asText())
        assertEquals("http", data["type"].asText())
        assertEquals(agentId, data["agentId"].asLong())
        assertEquals("webhook", data["communicationMode"].asText())
        assertTrue(data["callbackKey"].asText().isNotBlank(), "callbackKey should be generated")
        assertTrue(data["sessionId"].asText().isNotBlank(), "sessionId should be generated")
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
    fun `toggle channel status off and on`() {
        assertOk(putJson("/api/admin/channels/toggle/${locateChannelId()}?status=0"))
        var data = assertOk(getJson("/api/admin/channels/$channelId"))
        assertEquals(0, data["status"].asInt())

        assertOk(putJson("/api/admin/channels/toggle/$channelId?status=1"))
        data = assertOk(getJson("/api/admin/channels/$channelId"))
        assertEquals(1, data["status"].asInt())
    }

    @Test
    @Order(7)
    fun `delete channel then detail returns empty`() {
        assertOk(deleteJson("/api/admin/channels/${locateChannelId()}"))

        val node = getJson("/api/admin/channels/$channelId")
        assertEquals(200, node["code"].asInt())
        assertTrue(node["data"] == null || node["data"].isNull, "deleted channel should not be returned")

        val record = findInPage("/api/admin/channels/page", "keyword=$channelName") {
            it["name"]?.asText() == channelName
        }
        assertTrue(record == null, "deleted channel should not appear in page result")

        // Cleanup prerequisite agent
        assertOk(deleteJson("/api/admin/agents/$agentId"))
    }
}

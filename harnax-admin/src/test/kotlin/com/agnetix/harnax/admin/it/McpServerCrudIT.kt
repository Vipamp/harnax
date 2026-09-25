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
 * MCP server CRUD regression: /api/admin/mcp
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class McpServerCrudIT : BaseAdminIT() {

    private val suffix = Random.nextInt(100000, 999999)
    private val mcpName = "it_mcp_$suffix"

    private var mcpId: Long = -1

    private fun locateMcpId(): Long {
        if (mcpId > 0) return mcpId
        val record = findInPage("/api/admin/mcp/page", "keyword=$mcpName") {
            it["name"]?.asText() == mcpName
        }
        assertNotNull(record, "created MCP server should be found in page result")
        mcpId = record["id"].asLong()
        return mcpId
    }

    @Test
    @Order(1)
    fun `create sse mcp server succeeds`() {
        val body = mapOf(
            "name" to mcpName,
            "description" to "IT mcp server",
            "type" to "sse",
            "url" to "http://localhost:39999/sse",
            "status" to 1,
        )
        assertOk(postJson("/api/admin/mcp", body))
    }

    @Test
    @Order(2)
    fun `page query finds created mcp server`() {
        assertTrue(locateMcpId() > 0)
    }

    @Test
    @Order(3)
    fun `get detail returns created mcp server`() {
        val data = assertOk(getJson("/api/admin/mcp/${locateMcpId()}"))
        assertEquals(mcpName, data["name"].asText())
        assertEquals("sse", data["type"].asText())
        assertEquals("http://localhost:39999/sse", data["url"].asText())
    }

    @Test
    @Order(4)
    fun `update mcp server and verify`() {
        val body = mapOf(
            "id" to locateMcpId(),
            "name" to mcpName,
            "description" to "IT mcp updated",
            "type" to "streamablehttp",
            "url" to "http://localhost:39999/mcp",
            "status" to 1,
        )
        assertOk(putJson("/api/admin/mcp/update/${locateMcpId()}", body))

        val data = assertOk(getJson("/api/admin/mcp/${locateMcpId()}"))
        assertEquals("IT mcp updated", data["description"].asText())
        assertEquals("streamablehttp", data["type"].asText())
        assertEquals("http://localhost:39999/mcp", data["url"].asText())
    }

    @Test
    @Order(5)
    fun `toggle mcp server status off and on`() {
        assertOk(putJson("/api/admin/mcp/toggle/${locateMcpId()}?status=0"))
        var data = assertOk(getJson("/api/admin/mcp/${locateMcpId()}"))
        assertEquals(0, data["status"].asInt())

        assertOk(putJson("/api/admin/mcp/toggle/${locateMcpId()}?status=1"))
        data = assertOk(getJson("/api/admin/mcp/${locateMcpId()}"))
        assertEquals(1, data["status"].asInt())
    }

    @Test
    @Order(6)
    fun `create with duplicate name fails`() {
        val body = mapOf(
            "name" to mcpName,
            "type" to "sse",
            "url" to "http://localhost:39999/sse2",
        )
        assertErr(postJson("/api/admin/mcp", body))
    }

    @Test
    @Order(7)
    fun `create sse type without url fails`() {
        val body = mapOf("name" to "it_mcp_nourl_$suffix", "type" to "sse")
        assertErr(postJson("/api/admin/mcp", body))
    }

    @Test
    @Order(8)
    fun `create with invalid type fails`() {
        val body = mapOf("name" to "it_mcp_badtype_$suffix", "type" to "websocket", "url" to "http://x")
        assertErr(postJson("/api/admin/mcp", body))
    }

    @Test
    @Order(9)
    fun `create stdio type is rejected in current edition`() {
        val body = mapOf("name" to "it_mcp_stdio_$suffix", "type" to "stdio", "command" to "echo hi")
        val node = postJson("/api/admin/mcp", body)
        assertErr(node)
        assertTrue(node["message"].asText().contains("stdio"), "error should mention stdio: $node")
    }

    @Test
    @Order(10)
    fun `related-agents names the agents that would lose this server`() {
        // The delete confirmation reads this list, so a server nobody binds has to answer with nothing
        // rather than an error.
        assertEquals(0, assertOk(getJson("/api/admin/mcp/${locateMcpId()}/related-agents")).size())

        val agentName = "it_mcp_agent_$suffix"
        val body = mapOf(
            "name" to agentName,
            "description" to "IT mcp holder",
            "systemPrompt" to "You are an IT agent.",
            "modelId" to ensureAgentModelId(),
            "status" to 1,
            "mcpList" to listOf(mapOf("id" to locateMcpId())),
        )
        assertOk(postJson("/api/admin/agents", body))
        val record = findInPage("/api/admin/agents/page", "name=$agentName") {
            it["name"]?.asText() == agentName
        }
        assertNotNull(record, "the binding agent should be listed")

        val related = assertOk(getJson("/api/admin/mcp/${locateMcpId()}/related-agents"))
        assertEquals(1, related.size(), "the bound agent is the only one losing the server: $related")
        assertEquals(agentName, related[0]["agentName"].asText())
        assertEquals(1, related[0]["status"].asInt())

        // The count follows live bindings, not rows that outlived their agent.
        assertOk(deleteJson("/api/admin/agents/${record["id"].asLong()}"))
        assertEquals(0, assertOk(getJson("/api/admin/mcp/${locateMcpId()}/related-agents")).size())
    }

    @Test
    @Order(11)
    fun `delete mcp server then detail reports not found`() {
        assertOk(deleteJson("/api/admin/mcp/${locateMcpId()}"))

        val node = getJson("/api/admin/mcp/$mcpId")
        assertEquals(404, node["code"].asInt(), "a row we cannot read must not answer as success")
        assertTrue(node["data"] == null || node["data"].isNull, "deleted MCP server should not be returned")

        val record = findInPage("/api/admin/mcp/page", "keyword=$mcpName") {
            it["name"]?.asText() == mcpName
        }
        assertTrue(record == null, "deleted MCP server should not appear in page result")
    }
}

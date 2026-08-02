package com.agnetix.harnax.admin.it

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.http.HttpMethod
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CLI CRUD regression: /api/admin/clis
 *
 * Covers page/create/detail/update/toggle/delete plus related-agents and
 * related-sessions, including the "CLI still bound to an agent" guards.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CliCrudIT : BaseAdminIT() {

    private val suffix = Random.nextInt(100000, 999999)
    private val cliName = "it_cli_$suffix"
    private val agentName = "it_cli_agent_$suffix"

    private var cliId: Long = -1
    private var agentId: Long = -1

    private fun locateCliId(): Long {
        if (cliId > 0) return cliId
        val record = findInPage("/api/admin/clis/page", "name=$cliName") {
            it["name"]?.asText() == cliName
        }
        assertNotNull(record, "created cli should be found in page result")
        cliId = record["id"].asLong()
        return cliId
    }

    @Test
    @Order(1)
    fun `create cli succeeds`() {
        val body = mapOf(
            "name" to cliName,
            "description" to "IT cli",
            "version" to "1.0.0",
            "installScript" to "RUN echo install-it-cli",
            "checkCommand" to "it-cli --version",
            "status" to 1,
            "isPublic" to 0,
        )
        assertOk(postJson("/api/admin/clis", body))
    }

    @Test
    @Order(2)
    fun `page query finds created cli`() {
        assertTrue(locateCliId() > 0)
    }

    @Test
    @Order(3)
    fun `get detail returns created cli`() {
        val data = assertOk(getJson("/api/admin/clis/${locateCliId()}"))
        assertEquals(cliName, data["name"].asText())
        assertEquals("IT cli", data["description"].asText())
        assertEquals("1.0.0", data["version"].asText())
        assertEquals("RUN echo install-it-cli", data["installScript"].asText())
        assertEquals("it-cli --version", data["checkCommand"].asText())
        assertEquals(1, data["status"].asInt())
    }

    @Test
    @Order(4)
    fun `create cli with duplicate name fails`() {
        val body = mapOf(
            "name" to cliName,
            "installScript" to "RUN echo dup",
        )
        assertErr(postJson("/api/admin/clis", body))
    }

    @Test
    @Order(5)
    fun `create cli without name or install script returns 400`() {
        assertErr(postJson("/api/admin/clis", mapOf("name" to "", "installScript" to "RUN echo x")))
        assertErr(postJson("/api/admin/clis", mapOf("name" to "it_cli_noscript_$suffix", "installScript" to "")))
    }

    @Test
    @Order(6)
    fun `update cli changes description and version`() {
        val body = mapOf(
            "description" to "IT cli updated",
            "version" to "1.1.0",
            "installScript" to "RUN echo install-it-cli-v2",
        )
        assertOk(putJson("/api/admin/clis/update/${locateCliId()}", body))

        val data = assertOk(getJson("/api/admin/clis/$cliId"))
        assertEquals("IT cli updated", data["description"].asText())
        assertEquals("1.1.0", data["version"].asText())
        assertEquals("RUN echo install-it-cli-v2", data["installScript"].asText())
    }

    @Test
    @Order(7)
    fun `update unknown cli fails`() {
        assertErr(putJson("/api/admin/clis/update/99999999", mapOf("description" to "nope")))
    }

    @Test
    @Order(8)
    fun `related agents and sessions of fresh cli are empty`() {
        val agents = assertOk(getJson("/api/admin/clis/${locateCliId()}/related-agents"))
        assertTrue(agents.isArray)
        assertEquals(0, agents.size())

        val sessions = assertOk(getJson("/api/admin/clis/$cliId/related-sessions"))
        assertTrue(sessions.isArray)
        assertEquals(0, sessions.size())
    }

    @Test
    @Order(9)
    fun `cli bound to enabled agent cannot be disabled or deleted`() {
        // Bind the CLI to a new enabled agent
        val body = mapOf(
            "name" to agentName,
            "status" to 1,
            "cliList" to listOf(mapOf("id" to locateCliId())),
        )
        assertOk(postJson("/api/admin/agents", body))
        val agent = findInPage("/api/admin/agents/page", "name=$agentName") {
            it["name"]?.asText() == agentName
        }
        assertNotNull(agent, "prerequisite agent should exist")
        agentId = agent["id"].asLong()

        // Related agents now lists the binding
        val related = assertOk(getJson("/api/admin/clis/$cliId/related-agents"))
        assertTrue(related.any { it["agentId"].asLong() == agentId }, "bound agent should be listed")

        // Guards: disabling and deleting are both blocked while the binding exists
        assertErr(putJson("/api/admin/clis/toggle/$cliId?status=0"))
        assertErr(deleteJson("/api/admin/clis/$cliId"))

        // Unbind by deleting the agent
        assertOk(deleteJson("/api/admin/agents/$agentId"))
    }

    @Test
    @Order(10)
    fun `toggle cli status off and on`() {
        assertOk(putJson("/api/admin/clis/toggle/${locateCliId()}?status=0"))
        var data = assertOk(getJson("/api/admin/clis/$cliId"))
        assertEquals(0, data["status"].asInt())

        assertOk(putJson("/api/admin/clis/toggle/$cliId?status=1"))
        data = assertOk(getJson("/api/admin/clis/$cliId"))
        assertEquals(1, data["status"].asInt())
    }

    @Test
    @Order(11)
    fun `toggle unknown cli fails`() {
        assertErr(putJson("/api/admin/clis/toggle/99999999?status=0"))
    }

    @Test
    @Order(12)
    fun `cli endpoints without token return 401`() {
        val page = exchange(HttpMethod.GET, "/api/admin/clis/page?pageNum=1&pageSize=1", token = null)
        assertEquals(401, page.statusCode.value())

        val create = exchange(HttpMethod.POST, "/api/admin/clis", mapOf("name" to "x", "installScript" to "RUN x"), token = null)
        assertEquals(401, create.statusCode.value())

        val delete = exchange(HttpMethod.DELETE, "/api/admin/clis/1", token = null)
        assertEquals(401, delete.statusCode.value())
    }

    @Test
    @Order(13)
    fun `delete unknown cli fails`() {
        assertErr(deleteJson("/api/admin/clis/99999999"))
    }

    @Test
    @Order(14)
    fun `delete cli then detail returns empty`() {
        assertOk(deleteJson("/api/admin/clis/${locateCliId()}"))

        val node = getJson("/api/admin/clis/$cliId")
        assertEquals(200, node["code"].asInt())
        assertTrue(node["data"] == null || node["data"].isNull, "deleted cli should not be returned")

        val record = findInPage("/api/admin/clis/page", "name=$cliName") {
            it["name"]?.asText() == cliName
        }
        assertTrue(record == null, "deleted cli should not appear in page result")
    }
}

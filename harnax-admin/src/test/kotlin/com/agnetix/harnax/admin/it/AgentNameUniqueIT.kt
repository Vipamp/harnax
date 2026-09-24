package com.agnetix.harnax.admin.it

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpMethod
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Agent name uniqueness (AGENT-04): the service guards that turn a clash into a refusal, and the
 * `uk_agent_tenant_active_name` key from V43 behind them.
 *
 * The name is scoped per tenant because the list query is: outside one tenant a name identifies no
 * agent, so a global key would refuse creates no caller could have collided with.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AgentNameUniqueIT : BaseAdminIT() {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private val suffix = Random.nextInt(100000, 999999)
    private val takenName = "it_name_$suffix"
    private val otherName = "it_name_other_$suffix"

    /** A tenant that exists nowhere else, so these rows cannot collide with another class's. */
    private val otherTenant = 920_001L

    /** Live agents this class created, drained as it goes so a later case never inherits a name. */
    private val created = mutableListOf<Long>()

    private fun namedRecords(
        basePath: String,
        name: String,
        tenantId: Long? = null,
    ): List<JsonNode> {
        val data = assertOk(
            parseBody(exchange(HttpMethod.GET, "$basePath?pageNum=1&pageSize=50&name=$name", tenantId = tenantId)),
        )
        val records = data["records"]
        return if (records == null || !records.isArray) emptyList() else records.map { it }.filter { it["name"]?.asText() == name }
    }

    private fun agentIdOf(
        name: String,
        tenantId: Long? = null,
    ): Long = namedRecords("/api/admin/agents/page", name, tenantId)
        .firstOrNull()
        ?.let { it["id"].asLong() }
        ?: error("no live agent named $name in tenant ${tenantId ?: "default"}")

    private fun createAgent(
        name: String,
        tenantId: Long? = null,
    ): JsonNode = parseBody(exchange(HttpMethod.POST, "/api/admin/agents", agentCreateBody(name), tenantId = tenantId))

    @Test
    @Order(1)
    fun `first create under a fresh name succeeds`() {
        assertOk(createAgent(takenName))
        created += agentIdOf(takenName)
    }

    @Test
    @Order(2)
    fun `a second create with the same name in the tenant is refused`() {
        val node = createAgent(takenName)
        assertErr(node)
        assertTrue(node["message"].asText().contains("already exists"), "the refusal should name the rule: $node")

        // A refusal is the only acceptable outcome: a half-written second row would leave the page
        // listing two agents nobody can tell apart, which is the defect the key closes.
        assertEquals(1, namedRecords("/api/admin/agents/page", takenName).size)
    }

    @Test
    @Order(3)
    fun `renaming onto a live name is refused and leaves the agent untouched`() {
        assertOk(createAgent(otherName))
        val otherId = agentIdOf(otherName)
        created += otherId

        val node = parseBody(exchange(HttpMethod.PUT, "/api/admin/agents/update/$otherId", mapOf("name" to takenName)))
        assertErr(node)
        assertTrue(node["message"].asText().contains("already exists"), "the refusal should name the rule: $node")

        val detail = assertOk(getJson("/api/admin/agents/$otherId"))
        assertEquals(otherName, detail["name"].asText(), "a refused rename must not have been written")
    }

    @Test
    @Order(4)
    fun `resubmitting an unchanged name is not read as a clash`() {
        // The update form sends the whole agent back, name included, so matching your own row has to
        // stay legal — otherwise every save of an existing agent would refuse itself.
        assertOk(putJson("/api/admin/agents/update/${agentIdOf(takenName)}", mapOf("name" to takenName, "description" to "resaved")))
        assertEquals("resaved", assertOk(getJson("/api/admin/agents/${agentIdOf(takenName)}"))["description"].asText())
    }

    @Test
    @Order(5)
    fun `another tenant may use the same name and neither sees the other row`() {
        assertOk(createAgent(takenName, otherTenant))
        val foreignId = agentIdOf(takenName, otherTenant)

        assertEquals(1, namedRecords("/api/admin/agents/page", takenName).size, "own tenant should list exactly its row")
        val foreignRows = namedRecords("/api/admin/agents/page", takenName, otherTenant)
        assertEquals(1, foreignRows.size, "the other tenant should list exactly its row")
        assertTrue(
            foreignRows.none { it["id"].asLong() == created[0] },
            "the two rows are the same one, so the tenant filter on the list is not doing anything",
        )

        // Reachable only with its own tenant. By id from here the row answers as not found — the detail
        // endpoint carries the same refusal for a missing row and for one this tenant may not see, so
        // probing ids cannot enumerate another tenant's agents.
        assertEquals(takenName, assertOk(parseBody(exchange(HttpMethod.GET, "/api/admin/agents/$foreignId", tenantId = otherTenant)))["name"].asText())
        assertEquals(404, getJson("/api/admin/agents/$foreignId")["code"].asInt(), "the other tenant's row must not answer by id from this tenant")

        parseBody(exchange(HttpMethod.DELETE, "/api/admin/agents/$foreignId", tenantId = otherTenant))
    }

    @Test
    @Order(6)
    fun `deleting releases the name for reuse`() {
        val id = created[0]
        assertOk(deleteJson("/api/admin/agents/$id"))
        created.removeAt(0)

        assertTrue(namedRecords("/api/admin/agents/page", takenName).isEmpty(), "a deleted agent should leave the list")
        assertOk(createAgent(takenName))
        created += agentIdOf(takenName)
    }

    @Test
    @Order(7)
    fun `cleanup deletes what this class created`() {
        created.forEach { assertOk(deleteJson("/api/admin/agents/$it")) }
    }

    // Below here the key itself needs proving: the service guard collapses a clash on its own, so only
    // SQL shows whether the migration statement actually reached the database.
    private fun insertLive(
        tenantId: Long,
        name: String,
    ): Int = jdbc.update(
        "INSERT INTO agent (tenant_id, name, description, system_prompt, model_id, owner, status, is_public, creator, active, create_time, update_time) " +
            "VALUES (?, ?, '', '', 1, 'it', 1, 0, 'it', 1, NOW(), NOW())",
        tenantId,
        name,
    )

    @Test
    @Order(8)
    fun `the database refuses a second live row with the same name in one tenant`() {
        val tenant = 920_002L
        val name = "it_direct_$suffix"
        try {
            assertEquals(1, insertLive(tenant, name))
            assertThrows<DuplicateKeyException> { insertLive(tenant, name) }
        } finally {
            jdbc.update("DELETE FROM agent WHERE tenant_id = ?", tenant)
        }
    }

    @Test
    @Order(9)
    fun `the same name in another tenant is a plain insert`() {
        val tenants = listOf(920_003L, 920_004L)
        val name = "it_cross_$suffix"
        try {
            tenants.forEach { assertEquals(1, insertLive(it, name)) }
            assertEquals(
                tenants.size,
                jdbc.queryForObject("SELECT COUNT(*) FROM agent WHERE name = ? AND active = 1", Int::class.java, name),
            )
        } finally {
            jdbc.update("DELETE FROM agent WHERE tenant_id IN (?, ?)", tenants[0], tenants[1])
        }
    }

    @Test
    @Order(10)
    fun `a soft-deleted name is free until that row comes back to life`() {
        val tenant = 920_005L
        val name = "it_released_$suffix"
        try {
            insertLive(tenant, name)
            val firstId = jdbc.queryForObject(
                "SELECT id FROM agent WHERE tenant_id = ? AND name = ?",
                Long::class.java,
                tenant,
                name,
            )!!
            jdbc.update("UPDATE agent SET active = 0 WHERE id = ?", firstId)

            // active_name turns NULL with active = 0, so the key stops covering that row.
            assertEquals(1, insertLive(tenant, name))

            // ... and it claims the name again the moment the deleted row is restored.
            assertThrows<DuplicateKeyException> { jdbc.update("UPDATE agent SET active = 1 WHERE id = ?", firstId) }
        } finally {
            jdbc.update("DELETE FROM agent WHERE tenant_id = ?", tenant)
        }
    }
}

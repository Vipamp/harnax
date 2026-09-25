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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Environment variable CRUD regression: /api/admin/env-variables
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class EnvVariableCrudIT : BaseAdminIT() {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private val suffix = Random.nextInt(100000, 999999)
    private val envKey = "IT_ENV_$suffix"

    /** A second account inside tenant 1: same tenant, different creator, which is the scope that divides. */
    private val intruderToken: String by lazy { jwtUtil.generateToken(99999L, "it_intruder", 1L, 0) }

    private fun messageOf(node: JsonNode): String = node["message"].asText()

    private var envId: Long = -1

    private fun locateEnvId(): Long {
        if (envId > 0) return envId
        val record = findInPage("/api/admin/env-variables/page", "keyword=$envKey") {
            it["envKey"]?.asText() == envKey
        }
        assertNotNull(record, "created env variable should be found in page result")
        envId = record["id"].asLong()
        return envId
    }

    @Test
    @Order(1)
    fun `create env variable succeeds`() {
        val body = mapOf(
            "envKey" to envKey,
            "envValue" to "plain-value-$suffix",
            "description" to "IT env variable",
            "sensitive" to 0,
            "enabled" to 1,
        )
        assertOk(postJson("/api/admin/env-variables", body))
    }

    @Test
    @Order(2)
    fun `page query only returns current user variables and finds created one`() {
        val record = findInPage("/api/admin/env-variables/page", "keyword=$envKey") {
            it["envKey"]?.asText() == envKey
        }
        assertNotNull(record)
        assertEquals("admin", record["creator"].asText())
    }

    @Test
    @Order(3)
    fun `get detail returns created env variable`() {
        val data = assertOk(getJson("/api/admin/env-variables/${locateEnvId()}"))
        assertEquals(envKey, data["envKey"].asText())
        assertEquals("plain-value-$suffix", data["envValue"].asText())
    }

    @Test
    @Order(4)
    fun `update env variable value and verify`() {
        val body = mapOf("envValue" to "updated-value-$suffix", "description" to "updated desc")
        assertOk(putJson("/api/admin/env-variables/update/${locateEnvId()}", body))

        val data = assertOk(getJson("/api/admin/env-variables/${locateEnvId()}"))
        assertEquals("updated-value-$suffix", data["envValue"].asText())
        assertEquals("updated desc", data["description"].asText())
    }

    @Test
    @Order(5)
    fun `toggle env variable enabled off and on`() {
        assertOk(putJson("/api/admin/env-variables/${locateEnvId()}/toggle?enabled=0"))
        var data = assertOk(getJson("/api/admin/env-variables/${locateEnvId()}"))
        assertEquals(0, data["enabled"].asInt())

        assertOk(putJson("/api/admin/env-variables/${locateEnvId()}/toggle?enabled=1"))
        data = assertOk(getJson("/api/admin/env-variables/${locateEnvId()}"))
        assertEquals(1, data["enabled"].asInt())
    }

    @Test
    @Order(6)
    fun `sensitive env variable value is masked in response`() {
        val sensitiveKey = "IT_ENV_SECRET_$suffix"
        val rawValue = "super-secret-value-123456"
        val body = mapOf(
            "envKey" to sensitiveKey,
            "envValue" to rawValue,
            "sensitive" to 1,
        )
        assertOk(postJson("/api/admin/env-variables", body))

        val record = findInPage("/api/admin/env-variables/page", "keyword=$sensitiveKey") {
            it["envKey"]?.asText() == sensitiveKey
        }
        assertNotNull(record)
        val display = record["envValue"].asText()
        assertTrue(display != rawValue, "sensitive value must not be returned raw")
        // mask rule: first 3 + **** + last 2 for values longer than 8 chars
        assertEquals("${rawValue.take(3)}****${rawValue.takeLast(2)}", display)

        // cleanup
        assertOk(deleteJson("/api/admin/env-variables/${record["id"].asLong()}"))
    }

    @Test
    @Order(7)
    fun `create with invalid key pattern returns 400`() {
        val body = mapOf("envKey" to "1BAD-KEY", "envValue" to "v")
        assertErr(postJson("/api/admin/env-variables", body))
    }

    @Test
    @Order(8)
    fun `create with blank value returns 400`() {
        val body = mapOf("envKey" to "IT_ENV_BLANK_$suffix", "envValue" to "")
        assertErr(postJson("/api/admin/env-variables", body))
    }

    @Test
    @Order(9)
    fun `a key the caller already holds is refused by name`() {
        val body = mapOf("envKey" to envKey, "envValue" to "another-value")
        val node = assertErr(postJson("/api/admin/env-variables", body))
        val message = messageOf(node)

        // The service checks the key before writing and now also folds the racing insert's
        // DuplicateKeyException into the same sentence, so what reaches the operator names the clash
        // instead of quoting the index, and is not one of the two shapes that used to answer here:
        // the controller's fallback and the message code an unset bundle key echoes as.
        assertTrue(message.contains(envKey), "the refusal should name the key it refused: $node")
        assertTrue(message != "Failed to create env variable", "the refusal must not be the generic fallback: $message")
        assertTrue(
            !message.contains("error.env.variable."),
            "the refusal has to be a resolved message, not an echoed message code: $message",
        )
        assertTrue(!message.contains("Duplicate entry"), "no SQL text may travel: $message")
        assertTrue(!message.contains("uk_env_"), "no index name may travel: $message")
    }

    @Test
    @Order(10)
    fun `other user cannot modify admin env variable (IDOR)`() {
        // Token of a non-existent regular user with same tenant: creator mismatch
        val node = assertErr(
            parseBody(
                exchange(
                    HttpMethod.PUT,
                    "/api/admin/env-variables/update/${locateEnvId()}",
                    mapOf("envValue" to "hacked"),
                    intruderToken,
                ),
            ),
        )
        // 拒因是「这行不存在」，与查无此 id 的回答逐字相同：别人的行存在与否不是能问出来的东西
        assertEquals(
            messageOf(parseBody(exchange(HttpMethod.PUT, "/api/admin/env-variables/update/999999999", mapOf("envValue" to "x")))),
            messageOf(node),
            "a foreign row must answer exactly like a missing one: $node",
        )

        // Value unchanged
        val data = assertOk(getJson("/api/admin/env-variables/${locateEnvId()}"))
        assertEquals("updated-value-$suffix", data["envValue"].asText())
    }

    @Test
    @Order(11)
    fun `delete env variable then detail reports not found`() {
        assertOk(deleteJson("/api/admin/env-variables/${locateEnvId()}"))

        val node = getJson("/api/admin/env-variables/$envId")
        assertEquals(404, node["code"].asInt(), "读不到的行不该是成功响应")
        assertTrue(node["data"] == null || node["data"].isNull, "deleted env variable should not be returned")
    }

    @Test
    @Order(12)
    fun `a re-created key can be deleted again`() {
        // The delete that used to collide with its own tombstone: `uk_tenant_key_active` covered the
        // raw `active` column, so only one (tenant, key, 0) row could exist and this second delete
        // answered code 500 with a "name already in use" message no delete path should ever carry.
        assertOk(postJson("/api/admin/env-variables", mapOf("envKey" to envKey, "envValue" to "recreated-$suffix")))

        val recreated = findInPage("/api/admin/env-variables/page", "keyword=$envKey") {
            it["envKey"]?.asText() == envKey
        }
        assertNotNull(recreated, "a deleted key must be free for reuse")
        val recreatedId = recreated["id"].asLong()
        assertTrue(recreatedId != envId, "the page returned the deleted row itself, so tombstones leak into the list")

        assertOk(deleteJson("/api/admin/env-variables/$recreatedId"))
    }

    @Test
    @Order(15)
    fun `two users of one tenant may hold the same key and each sees only their own row`() {
        val shared = "IT_ENV_SHARED_$suffix"
        try {
            assertOk(
                parseBody(
                    exchange(HttpMethod.POST, "/api/admin/env-variables", mapOf("envKey" to shared, "envValue" to "theirs-$suffix"), intruderToken),
                ),
            )
            // V46 之后键按创建者唯一：同租户里另一个人填同一个键不再算撞车
            assertOk(postJson("/api/admin/env-variables", mapOf("envKey" to shared, "envValue" to "mine-$suffix")))

            val rows = jdbc.queryForList(
                "SELECT id, creator FROM env_variable WHERE env_key = ? AND active = 1",
                shared,
            )
            assertEquals(2, rows.size, "the tenant-wide key rule is gone, so both live rows have to be stored")

            val mine = findInPage("/api/admin/env-variables/page", "keyword=$shared") { it["envKey"]?.asText() == shared }
            assertNotNull(mine)
            assertEquals("admin", mine["creator"].asText(), "the list is the caller's own, not the tenant's")
            assertEquals("mine-$suffix", mine["envValue"].asText())

            val theirs = rows.first { it["creator"] == "it_intruder" }["id"] as Long
            val foreign = getJson("/api/admin/env-variables/$theirs")["data"]
            assertTrue(foreign == null || foreign.isNull, "another user's row must not be readable by id: $foreign")
        } finally {
            jdbc.update("DELETE FROM env_variable WHERE env_key = ?", shared)
        }
    }

    // Below here the key itself needs proving: the API path above could also pass with the index
    // simply absent, so only SQL shows the replacement really enforces the rule it is meant to.
    private fun insertRow(
        tenantId: Long,
        key: String,
        active: Int,
        creator: String = "it",
    ): Int = jdbc.update(
        "INSERT INTO env_variable (tenant_id, env_key, env_value, `sensitive`, enabled, creator, active, create_time, update_time) " +
            "VALUES (?, ?, 'v', 0, 1, ?, ?, NOW(), NOW())",
        tenantId,
        key,
        creator,
        active,
    )

    @Test
    @Order(13)
    fun `deleted rows pile up while a live row still owns the key`() {
        val tenant = 930_001L
        val key = "IT_ENV_PILE_$suffix"
        try {
            insertRow(tenant, key, 1)
            assertThrows<DuplicateKeyException> { insertRow(tenant, key, 1) }

            val firstId = jdbc.queryForObject(
                "SELECT id FROM env_variable WHERE tenant_id = ? AND env_key = ?",
                Long::class.java,
                tenant,
                key,
            )!!
            // active_env_key turns NULL with active = 0, so the key stops covering that row and both
            // the re-create and its own delete become plain inserts again.
            jdbc.update("UPDATE env_variable SET active = 0 WHERE id = ?", firstId)
            assertEquals(1, insertRow(tenant, key, 1))
            val secondId = jdbc.queryForObject(
                "SELECT id FROM env_variable WHERE tenant_id = ? AND env_key = ? AND active = 1",
                Long::class.java,
                tenant,
                key,
            )!!
            jdbc.update("UPDATE env_variable SET active = 0 WHERE id = ?", secondId)

            assertEquals(
                2,
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM env_variable WHERE tenant_id = ? AND env_key = ? AND active = 0",
                    Int::class.java,
                    tenant,
                    key,
                ),
            )
        } finally {
            jdbc.update("DELETE FROM env_variable WHERE tenant_id = ?", tenant)
        }
    }

    @Test
    @Order(14)
    fun `another tenant keeps its own live row for the same key`() {
        val tenants = listOf(930_002L, 930_003L)
        val key = "IT_ENV_CROSS_$suffix"
        try {
            tenants.forEach { assertEquals(1, insertRow(it, key, 1)) }
            assertEquals(
                tenants.size,
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM env_variable WHERE env_key = ? AND active = 1",
                    Int::class.java,
                    key,
                ),
                "the key is scoped per tenant, so widening the tombstone rule must not narrow this",
            )
        } finally {
            jdbc.update("DELETE FROM env_variable WHERE tenant_id IN (?, ?)", tenants[0], tenants[1])
        }
    }

    @Test
    @Order(16)
    fun `the live key is scoped per creator inside one tenant`() {
        val tenant = 930_004L
        val key = "IT_ENV_PER_USER_$suffix"
        try {
            assertEquals(1, insertRow(tenant, key, 1, creator = "it_a"))
            // 同租户第二个创建者填同一个键：V46 放宽的正是这一条
            assertEquals(1, insertRow(tenant, key, 1, creator = "it_b"))
            // 同一个人再填一次还是要撞
            assertThrows<DuplicateKeyException> { insertRow(tenant, key, 1, creator = "it_a") }
            assertEquals(
                2,
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM env_variable WHERE tenant_id = ? AND env_key = ? AND active = 1",
                    Int::class.java,
                    tenant,
                    key,
                ),
            )
        } finally {
            jdbc.update("DELETE FROM env_variable WHERE tenant_id = ?", tenant)
        }
    }

    @Test
    @Order(17)
    fun `the refusal to delete names only the agents of the callers own tenant`() {
        // An agent of another tenant cannot bind this variable through the API - the save resolves the
        // pointer inside the agent's own tenant - so the foreign row is seeded directly. It stands for
        // a binding written before that guard existed, and the refusal has to stop reading its name out
        // to the caller.
        val foreignTenant = 930_005L
        val mine = "it_env_ref_mine_$suffix"
        val theirs = "it_env_ref_theirs_$suffix"
        val key = "IT_ENV_REF_$suffix"
        val cliId = jdbc.queryForList("SELECT id FROM cli ORDER BY id LIMIT 1").firstOrNull()?.get("id") as? Long ?: 0L

        assertOk(postJson("/api/admin/env-variables", mapOf("envKey" to key, "envValue" to "v-$suffix")))
        val created = findInPage("/api/admin/env-variables/page", "keyword=$key") { it["envKey"]?.asText() == key }
        assertNotNull(created, "the env variable this case binds should exist")
        val id = created["id"].asLong()
        val bindings = """[{"envKey":"$key","envVarId":$id}]"""
        try {
            insertSeededAgent(1L, mine)
            insertSeededAgent(foreignTenant, theirs)
            jdbc.update(
                "INSERT INTO agent_cli_binding (agent_id, cli_id, env_bindings) " +
                    "SELECT id, ?, ? FROM agent WHERE name IN (?, ?)",
                cliId,
                bindings,
                mine,
                theirs,
            )

            val node = assertErr(deleteJson("/api/admin/env-variables/$id"))
            val message = messageOf(node)

            assertTrue(message.contains(mine), "the refusal should still name the caller's own agent: $node")
            assertTrue(message.contains("1 agent(s)"), "the count must cover this tenant only: $message")
            assertTrue(!message.contains(theirs), "another tenant's agent name must not travel: $message")
        } finally {
            jdbc.update("DELETE FROM agent_cli_binding WHERE env_bindings = ?", bindings)
            jdbc.update("DELETE FROM agent WHERE name IN (?, ?)", mine, theirs)
            jdbc.update("DELETE FROM env_variable WHERE env_key = ?", key)
        }
    }

    /** Direct insert: agent creation through the API runs in the caller's tenant, and this case needs two. */
    private fun insertSeededAgent(
        tenantId: Long,
        name: String,
    ) {
        jdbc.update(
            "INSERT INTO agent (tenant_id, name, description, system_prompt, model_id, owner, status, is_public, creator, active) " +
                "VALUES (?, ?, 'IT reference guard', 'x', 1, 'admin', 1, 0, 'admin', 1)",
            tenantId,
            name,
        )
    }
}

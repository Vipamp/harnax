package com.agnetix.harnax.admin.it

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
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
    fun `duplicate key in same tenant fails on unique constraint`() {
        val body = mapOf("envKey" to envKey, "envValue" to "another-value")
        assertErr(postJson("/api/admin/env-variables", body))
    }

    @Test
    @Order(10)
    fun `other user cannot modify admin env variable (IDOR)`() {
        // Token of a non-existent regular user with same tenant: creator mismatch
        val otherToken = jwtUtil.generateToken(99999L, "it_intruder", 1L, 0)
        val node = parseBody(
            exchange(
                org.springframework.http.HttpMethod.PUT,
                "/api/admin/env-variables/update/${locateEnvId()}",
                mapOf("envValue" to "hacked"),
                otherToken,
            ),
        )
        assertErr(node)

        // Value unchanged
        val data = assertOk(getJson("/api/admin/env-variables/${locateEnvId()}"))
        assertEquals("updated-value-$suffix", data["envValue"].asText())
    }

    @Test
    @Order(11)
    fun `delete env variable then detail returns empty`() {
        assertOk(deleteJson("/api/admin/env-variables/${locateEnvId()}"))

        val node = getJson("/api/admin/env-variables/$envId")
        assertEquals(200, node["code"].asInt())
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

    // Below here the key itself needs proving: the API path above could also pass with the index
    // simply absent, so only SQL shows the replacement really enforces the rule it is meant to.
    private fun insertRow(
        tenantId: Long,
        key: String,
        active: Int,
    ): Int = jdbc.update(
        "INSERT INTO env_variable (tenant_id, env_key, env_value, `sensitive`, enabled, creator, active, create_time, update_time) " +
            "VALUES (?, ?, 'v', 0, 1, 'it', ?, NOW(), NOW())",
        tenantId,
        key,
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
}

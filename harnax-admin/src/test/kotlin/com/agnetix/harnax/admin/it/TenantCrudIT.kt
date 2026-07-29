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
 * Tenant CRUD regression: /api/admin/tenant
 *
 * Tenant creation requires an existing admin user id, so a dedicated user is
 * created up front and removed after the tenant lifecycle has been verified.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TenantCrudIT : BaseAdminIT() {

    private val suffix = Random.nextInt(100000, 999999)
    private val tenantName = "it_tenant_$suffix"
    private val ownerUsername = "it_tenant_owner_$suffix"
    private val memberUsername = "it_tenant_member_$suffix"

    private var tenantId: Long = -1
    private var ownerUserId: Long = -1
    private var memberUserId: Long = -1

    private fun createUser(name: String): Long {
        val body = mapOf(
            "username" to name,
            "password" to "abcdef123456",
            "nickname" to name,
            "email" to "$name@it.harnax.com",
            "phone" to "138${Random.nextLong(10000000, 99999999)}",
        )
        assertOk(postJson("/api/admin/users", body))
        val record = findInPage("/api/admin/users/page", "keyword=$name") {
            it["username"]?.asText() == name
        }
        assertNotNull(record, "created user $name should exist")
        return record["id"].asLong()
    }

    private fun ensureOwner(): Long {
        if (ownerUserId > 0) return ownerUserId
        ownerUserId = createUser(ownerUsername)
        return ownerUserId
    }

    @Test
    @Order(1)
    fun `create tenant succeeds and binds admin user`() {
        val body = mapOf("name" to tenantName, "adminUserId" to ensureOwner())
        val data = assertOk(postJson("/api/admin/tenant", body))
        tenantId = data["id"].asLong()
        assertTrue(tenantId > 0)
        assertEquals(tenantName, data["name"].asText())
        assertEquals(1, data["status"].asInt())
    }

    @Test
    @Order(2)
    fun `get detail returns created tenant`() {
        val data = assertOk(getJson("/api/admin/tenant/$tenantId"))
        assertEquals(tenantName, data["name"].asText())
    }

    @Test
    @Order(3)
    fun `tenant list contains created tenant`() {
        val record = findInPage("/api/admin/tenant", "name=$tenantName") {
            it["name"]?.asText() == tenantName
        }
        assertNotNull(record)
        assertEquals(tenantId, record["id"].asLong())
    }

    @Test
    @Order(4)
    fun `create tenant with duplicate name fails`() {
        assertErr(postJson("/api/admin/tenant", mapOf("name" to tenantName, "adminUserId" to ownerUserId)))
    }

    @Test
    @Order(5)
    fun `create tenant with nonexistent admin user fails`() {
        assertErr(postJson("/api/admin/tenant", mapOf("name" to "it_tenant_nouser_$suffix", "adminUserId" to 999999999L)))
    }

    @Test
    @Order(6)
    fun `non-admin user cannot create tenant`() {
        val intruderToken = jwtUtil.generateToken(99999L, "it_intruder_$suffix", 1L, 0)
        val response = exchange(
            HttpMethod.POST,
            "/api/admin/tenant",
            mapOf("name" to "it_tenant_intruder_$suffix", "adminUserId" to ownerUserId),
            intruderToken,
        )
        assertErr(parseBody(response))
    }

    @Test
    @Order(7)
    fun `tenant users contains bound admin`() {
        val data = assertOk(getJson("/api/admin/tenant/$tenantId/users"))
        val admin = data["records"].firstOrNull { it["userId"]?.asLong() == ownerUserId }
        assertNotNull(admin, "tenant admin should be listed in tenant users")
        assertEquals("admin", admin["role"].asText())
    }

    @Test
    @Order(8)
    fun `add user to tenant and update role`() {
        memberUserId = createUser(memberUsername)

        assertOk(postJson("/api/admin/tenant/$tenantId/users", mapOf("userId" to memberUserId, "role" to "member")))

        // Adding the same user twice fails
        assertErr(postJson("/api/admin/tenant/$tenantId/users", mapOf("userId" to memberUserId, "role" to "member")))

        assertOk(putJson("/api/admin/tenant/$tenantId/users/$memberUserId/role", mapOf("role" to "admin")))

        val data = assertOk(getJson("/api/admin/tenant/$tenantId/users"))
        val member = data["records"].firstOrNull { it["userId"]?.asLong() == memberUserId }
        assertNotNull(member)
        assertEquals("admin", member["role"].asText())
    }

    @Test
    @Order(9)
    fun `remove user from tenant`() {
        assertOk(deleteJson("/api/admin/tenant/$tenantId/users/$memberUserId"))

        val data = assertOk(getJson("/api/admin/tenant/$tenantId/users"))
        val member = data["records"].firstOrNull { it["userId"]?.asLong() == memberUserId }
        assertTrue(member == null, "removed user should not be listed in tenant users")
    }

    @Test
    @Order(10)
    fun `toggle tenant status`() {
        assertOk(putJson("/api/admin/tenant/$tenantId/status"))
        var data = assertOk(getJson("/api/admin/tenant/$tenantId"))
        assertEquals(0, data["status"].asInt())

        assertOk(putJson("/api/admin/tenant/$tenantId/status"))
        data = assertOk(getJson("/api/admin/tenant/$tenantId"))
        assertEquals(1, data["status"].asInt())
    }

    @Test
    @Order(11)
    fun `delete tenant then detail returns error`() {
        assertOk(deleteJson("/api/admin/tenant/$tenantId"))
        assertErr(getJson("/api/admin/tenant/$tenantId"))

        // Cleanup prerequisite users
        assertOk(deleteJson("/api/admin/users/$ownerUserId"))
        assertOk(deleteJson("/api/admin/users/$memberUserId"))
    }
}

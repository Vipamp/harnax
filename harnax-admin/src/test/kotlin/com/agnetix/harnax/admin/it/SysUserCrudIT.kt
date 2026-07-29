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
 * User management CRUD regression: /api/admin/users
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SysUserCrudIT : BaseAdminIT() {

    private val suffix = Random.nextInt(100000, 999999)
    private val username = "it_user_$suffix"
    private val email = "it_user_$suffix@it.harnax.com"
    private val phone = "138${Random.nextLong(10000000, 99999999)}"

    private var userId: Long = -1

    private fun createBody(
        name: String = username,
        mail: String = email,
        tel: String = phone,
    ): Map<String, Any?> = mapOf(
        "username" to name,
        "password" to "abcdef123456",
        "nickname" to "IT User $suffix",
        "email" to mail,
        "phone" to tel,
        "gender" to 1,
    )

    private fun locateUserId(): Long {
        if (userId > 0) return userId
        val record = findInPage("/api/admin/users/page", "keyword=$username") {
            it["username"]?.asText() == username
        }
        assertNotNull(record, "created user should be found in page result")
        userId = record["id"].asLong()
        return userId
    }

    @Test
    @Order(1)
    fun `create user succeeds`() {
        assertOk(postJson("/api/admin/users", createBody()))
    }

    @Test
    @Order(2)
    fun `page query finds created user`() {
        val id = locateUserId()
        assertTrue(id > 0)
    }

    @Test
    @Order(3)
    fun `get detail returns created user`() {
        val data = assertOk(getJson("/api/admin/users/${locateUserId()}"))
        assertEquals(username, data["username"].asText())
        assertEquals("IT User $suffix", data["nickname"].asText())
        assertEquals(0, data["isAdmin"].asInt())
    }

    @Test
    @Order(4)
    fun `update user changes nickname and verify`() {
        val body = mapOf(
            "nickname" to "IT User Updated $suffix",
            "email" to email,
            "phone" to phone,
        )
        assertOk(putJson("/api/admin/users/update/${locateUserId()}", body))

        val data = assertOk(getJson("/api/admin/users/${locateUserId()}"))
        assertEquals("IT User Updated $suffix", data["nickname"].asText())
    }

    @Test
    @Order(5)
    fun `toggle user status off and on`() {
        assertOk(putJson("/api/admin/users/toggle/${locateUserId()}?status=0"))
        var data = assertOk(getJson("/api/admin/users/${locateUserId()}"))
        assertEquals(0, data["status"].asInt())

        assertOk(putJson("/api/admin/users/toggle/${locateUserId()}?status=1"))
        data = assertOk(getJson("/api/admin/users/${locateUserId()}"))
        assertEquals(1, data["status"].asInt())
    }

    @Test
    @Order(6)
    fun `check username reports existence`() {
        val exists = assertOk(getJson("/api/admin/users/check/username?username=$username"))
        assertTrue(exists.asBoolean())
        val notExists = assertOk(getJson("/api/admin/users/check/username?username=it_no_such_user_$suffix"))
        assertTrue(!notExists.asBoolean())
    }

    @Test
    @Order(7)
    fun `create user with duplicate username fails`() {
        val body = createBody(mail = "dup_$email", tel = "139${Random.nextLong(10000000, 99999999)}")
        assertErr(postJson("/api/admin/users", body))
    }

    @Test
    @Order(8)
    fun `create user with invalid username pattern returns 400`() {
        val body = createBody(name = "bad name!!", mail = "bad_$email", tel = "137${Random.nextLong(10000000, 99999999)}")
        assertErr(postJson("/api/admin/users", body))
    }

    @Test
    @Order(9)
    fun `create user with invalid phone fails`() {
        val body = createBody(name = "it_user_badphone_$suffix", mail = "p_$email", tel = "12345")
        assertErr(postJson("/api/admin/users", body))
    }

    @Test
    @Order(10)
    fun `admin user cannot be disabled or deleted`() {
        assertErr(putJson("/api/admin/users/toggle/1?status=0"))
        assertErr(deleteJson("/api/admin/users/1"))
        // Admin still intact
        val data = assertOk(getJson("/api/admin/users/1"))
        assertEquals(1, data["isAdmin"].asInt())
    }

    @Test
    @Order(11)
    fun `delete user then detail returns empty`() {
        assertOk(deleteJson("/api/admin/users/${locateUserId()}"))

        val node = getJson("/api/admin/users/$userId")
        assertEquals(200, node["code"].asInt())
        assertTrue(node["data"] == null || node["data"].isNull, "deleted user should not be returned")

        val record = findInPage("/api/admin/users/page", "keyword=$username") {
            it["username"]?.asText() == username
        }
        assertTrue(record == null, "deleted user should not appear in page result")
    }
}

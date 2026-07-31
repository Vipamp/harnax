package com.agnetix.harnax.admin.it

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import kotlin.random.Random
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * User uniqueness check regression: /api/admin/users/check/{phone,email}
 *
 * SysUserCrudIT already exercises check/username; the phone and email check
 * endpoints were uncovered and are verified here with a dedicated user.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SysUserCheckIT : BaseAdminIT() {

    private val suffix = Random.nextInt(100000, 999999)
    private val username = "it_check_user_$suffix"
    private val email = "it_check_$suffix@it.harnax.com"
    private val phone = "138${Random.nextLong(10000000, 99999999)}"

    private var userId: Long = -1

    private fun ensureUser(): Long {
        if (userId > 0) return userId
        val body = mapOf(
            "username" to username,
            "password" to "abcdef123456",
            "nickname" to "IT Check User $suffix",
            "email" to email,
            "phone" to phone,
        )
        assertOk(postJson("/api/admin/users", body))
        val record = findInPage("/api/admin/users/page", "keyword=$username") {
            it["username"]?.asText() == username
        }
        assertNotNull(record, "prerequisite user should exist")
        userId = record["id"].asLong()
        return userId
    }

    @Test
    @Order(1)
    fun `check phone reports existence`() {
        ensureUser()
        val exists = assertOk(getJson("/api/admin/users/check/phone?phone=$phone"))
        assertTrue(exists.asBoolean(), "registered phone should be reported as existing")

        val notExists = assertOk(getJson("/api/admin/users/check/phone?phone=13000000000"))
        assertTrue(!notExists.asBoolean(), "unregistered phone should not be reported as existing")
    }

    @Test
    @Order(2)
    fun `check email reports existence`() {
        ensureUser()
        val exists = assertOk(getJson("/api/admin/users/check/email?email=$email"))
        assertTrue(exists.asBoolean(), "registered email should be reported as existing")

        val notExists = assertOk(getJson("/api/admin/users/check/email?email=it_no_such_$suffix@it.harnax.com"))
        assertTrue(!notExists.asBoolean(), "unregistered email should not be reported as existing")
    }

    @Test
    @Order(3)
    fun `checks no longer match after user is deleted`() {
        assertOk(deleteJson("/api/admin/users/${ensureUser()}"))

        val phoneExists = assertOk(getJson("/api/admin/users/check/phone?phone=$phone"))
        assertTrue(!phoneExists.asBoolean(), "deleted user's phone should be free again")

        val emailExists = assertOk(getJson("/api/admin/users/check/email?email=$email"))
        assertTrue(!emailExists.asBoolean(), "deleted user's email should be free again")
    }
}

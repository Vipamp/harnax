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
 * Mobile auth and user profile regression:
 * /api/admin/mp/auth, /api/admin/mp/user
 *
 * MP login does not require a captcha, so it is exercised end-to-end with the
 * admin account. Profile/password tests use a dedicated user whose token is
 * signed directly with JwtUtil.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MpAuthUserIT : BaseAdminIT() {

    /** SHA-256("admin123") — frontend sends SHA-256(password), DB stores BCrypt of it. */
    private val adminPasswordSha256 = "240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9"

    private val suffix = Random.nextInt(100000, 999999)
    private val username = "it_mp_user_$suffix"
    private val initialPassword = "a".repeat(64)
    private val newPassword = "b".repeat(64)

    private var userId: Long = -1

    private fun userToken(): String {
        if (userId < 0) {
            val body = mapOf(
                "username" to username,
                "password" to initialPassword,
                "nickname" to "IT MP User $suffix",
                "email" to "it_mp_$suffix@it.harnax.com",
                "phone" to "138${Random.nextLong(10000000, 99999999)}",
            )
            assertOk(postJson("/api/admin/users", body))
            val record = findInPage("/api/admin/users/page", "keyword=$username") {
                it["username"]?.asText() == username
            }
            assertNotNull(record, "prerequisite mp user should exist")
            userId = record["id"].asLong()
        }
        return jwtUtil.generateToken(userId, username, 1L, 0)
    }

    @Test
    @Order(1)
    fun `mp captcha endpoint is public and returns image`() {
        val node = parseBody(exchange(HttpMethod.GET, "/api/admin/mp/auth/captcha", token = null))
        assertEquals(200, node["code"].asInt())
        assertTrue(node["data"]["imageBase64"].asText().startsWith("data:image/png;base64,"))
    }

    @Test
    @Order(2)
    fun `mp login succeeds without captcha and returns router key`() {
        val body = mapOf("username" to "admin", "password" to adminPasswordSha256)
        val node = parseBody(exchange(HttpMethod.POST, "/api/admin/mp/auth/login", body, token = null))
        val data = assertOk(node)

        val accessToken = data["accessToken"].asText()
        assertTrue(jwtUtil.validateToken(accessToken), "mp accessToken should be a valid JWT")
        assertTrue(data["routerApiKey"].asText().startsWith("hnx_sk_live_"))
        assertTrue(data["routerUrl"].asText().isNotBlank())
        assertEquals("admin", data["userInfo"]["username"].asText())

        // Token works against an authenticated mp endpoint
        val profile = parseBody(exchange(HttpMethod.GET, "/api/admin/mp/user/profile", null, accessToken))
        assertEquals(200, profile["code"].asInt())
        assertEquals("admin", profile["data"]["username"].asText())
    }

    @Test
    @Order(3)
    fun `mp login with wrong password fails`() {
        val body = mapOf("username" to "admin", "password" to "0".repeat(64))
        assertErr(parseBody(exchange(HttpMethod.POST, "/api/admin/mp/auth/login", body, token = null)))
    }

    @Test
    @Order(4)
    fun `mp login with unknown user fails`() {
        val body = mapOf("username" to "it_no_such_user_$suffix", "password" to adminPasswordSha256)
        assertErr(parseBody(exchange(HttpMethod.POST, "/api/admin/mp/auth/login", body, token = null)))
    }

    @Test
    @Order(5)
    fun `mp profile requires authentication`() {
        val response = exchange(HttpMethod.GET, "/api/admin/mp/user/profile", token = null)
        assertEquals(401, response.statusCode.value())
    }

    @Test
    @Order(6)
    fun `mp profile returns created user info`() {
        val node = parseBody(exchange(HttpMethod.GET, "/api/admin/mp/user/profile", null, userToken()))
        val data = assertOk(node)
        assertEquals(userId, data["userId"].asLong())
        assertEquals(username, data["username"].asText())
        assertEquals("IT MP User $suffix", data["nickname"].asText())
    }

    @Test
    @Order(7)
    fun `change password with wrong old password fails`() {
        val body = mapOf("oldPassword" to "c".repeat(64), "newPassword" to newPassword)
        assertErr(parseBody(exchange(HttpMethod.PUT, "/api/admin/mp/user/password", body, userToken())))
    }

    @Test
    @Order(8)
    fun `change password succeeds and new password takes effect`() {
        val body = mapOf("oldPassword" to initialPassword, "newPassword" to newPassword)
        assertOk(parseBody(exchange(HttpMethod.PUT, "/api/admin/mp/user/password", body, userToken())))

        // Old password no longer accepted, new one is
        val oldAgain = mapOf("oldPassword" to initialPassword, "newPassword" to newPassword)
        assertErr(parseBody(exchange(HttpMethod.PUT, "/api/admin/mp/user/password", oldAgain, userToken())))

        val back = mapOf("oldPassword" to newPassword, "newPassword" to initialPassword)
        assertOk(parseBody(exchange(HttpMethod.PUT, "/api/admin/mp/user/password", back, userToken())))
    }

    @Test
    @Order(9)
    fun `mp logout is public and succeeds`() {
        assertOk(parseBody(exchange(HttpMethod.POST, "/api/admin/mp/auth/logout", token = null)))
    }

    @Test
    @Order(10)
    fun `cleanup delete mp user`() {
        userToken()
        assertOk(deleteJson("/api/admin/users/$userId"))
    }
}

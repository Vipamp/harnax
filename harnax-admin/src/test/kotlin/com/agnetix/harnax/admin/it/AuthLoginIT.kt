package com.agnetix.harnax.admin.it

import com.agnetix.harnax.admin.service.CaptchaService
import com.agnetix.harnax.mapper.ApiKeyMapper
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.aop.framework.AopProxyUtils
import org.springframework.beans.factory.annotation.Autowired
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Login flow regression: /api/admin/auth/login
 *
 * The captcha plaintext is not exposed by the API, so it is read from the
 * in-memory captcha store of CaptchaServiceImpl via reflection.
 * The admin PERMANENT api key required by login is created on startup by
 * PermanentKeyInitializer.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AuthLoginIT : BaseAdminIT() {

    @Autowired
    private lateinit var captchaService: CaptchaService

    @Autowired
    private lateinit var apiKeyMapper: ApiKeyMapper

    @Autowired
    private lateinit var dataSource: DataSource

    /** SHA-256("admin123") — the frontend sends SHA-256(password), DB stores BCrypt of it. */
    private val adminPasswordSha256 = "240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9"

    private fun captchaCodeFor(captchaKey: String): String {
        val target = AopProxyUtils.getSingletonTarget(captchaService) ?: captchaService
        val storeField = target.javaClass.getDeclaredField("captchaStore")
        storeField.isAccessible = true
        val store = storeField.get(target) as ConcurrentHashMap<*, *>
        val info = store[captchaKey] ?: error("captcha not found in store for key $captchaKey")
        val codeField = info.javaClass.getDeclaredField("code")
        codeField.isAccessible = true
        return codeField.get(info) as String
    }

    @Test
    @Order(1)
    fun `captcha endpoint returns key and image`() {
        val data = assertOk(getJson("/api/admin/auth/captcha"))
        assertTrue(data["captchaKey"].asText().isNotBlank())
        assertTrue(data["imageBase64"].asText().startsWith("data:image/png;base64,"))
    }

    @Test
    @Order(2)
    fun `login without captcha is rejected`() {
        val body = mapOf("username" to "admin", "password" to adminPasswordSha256)
        val node = postJson("/api/admin/auth/login", body)
        assertErr(node)
    }

    @Test
    @Order(3)
    fun `login with wrong password is rejected`() {
        val captcha = assertOk(getJson("/api/admin/auth/captcha"))
        val key = captcha["captchaKey"].asText()
        val body = mapOf(
            "username" to "admin",
            "password" to "0".repeat(64),
            "captcha" to captchaCodeFor(key),
            "captchaKey" to key,
        )
        assertErr(postJson("/api/admin/auth/login", body))
    }

    @Test
    @Order(4)
    fun `login with valid captcha and password succeeds`() {
        val captcha = assertOk(getJson("/api/admin/auth/captcha"))
        val key = captcha["captchaKey"].asText()
        val code = captchaCodeFor(key)

        val body = mapOf(
            "username" to "admin",
            "password" to adminPasswordSha256,
            "captcha" to code,
            "captchaKey" to key,
        )
        val data = assertOk(postJson("/api/admin/auth/login", body))

        val accessToken = data["accessToken"].asText()
        assertTrue(accessToken.isNotBlank())
        assertTrue(jwtUtil.validateToken(accessToken), "returned accessToken should be a valid JWT")
        assertEquals("Bearer", data["tokenType"].asText())

        val userInfo = data["userInfo"]
        assertNotNull(userInfo)
        assertEquals("admin", userInfo["username"].asText())
        assertEquals(1, userInfo["isAdmin"].asInt())

        // Permanent router api key is returned on login
        assertTrue(data["routerApiKey"].asText().startsWith("hnx_sk_live_"))

        // Token actually works against a protected endpoint
        val page = parseBody(
            exchange(
                org.springframework.http.HttpMethod.GET,
                "/api/admin/users/page?pageNum=1&pageSize=1",
                null,
                accessToken,
            ),
        )
        assertEquals(200, page["code"].asInt())
    }

    @Test
    @Order(5)
    fun `captcha is single use - reusing it fails`() {
        val captcha = assertOk(getJson("/api/admin/auth/captcha"))
        val key = captcha["captchaKey"].asText()
        val code = captchaCodeFor(key)

        val body = mapOf(
            "username" to "admin",
            "password" to adminPasswordSha256,
            "captcha" to code,
            "captchaKey" to key,
        )
        assertOk(postJson("/api/admin/auth/login", body))
        // Second attempt with the same captcha must fail (one-time use)
        assertErr(postJson("/api/admin/auth/login", body))
    }

    @Test
    @Order(6)
    fun `protected endpoint without token returns 401`() {
        val response = exchange(
            org.springframework.http.HttpMethod.GET,
            "/api/admin/users/page?pageNum=1&pageSize=1",
            null,
            token = null,
        )
        assertEquals(401, response.statusCode.value())
    }

    @Test
    @Order(7)
    fun `login self-heals when admin permanent key is missing`() {
        // Hard-delete admin's PERMANENT key to simulate lost data
        // (hard delete because uk_user_permanent unique index also covers soft-deleted rows)
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM api_key WHERE user_id = 1 AND key_type = 'PERMANENT'").use { it.executeUpdate() }
        }
        check(apiKeyMapper.selectPermanentKeyByUserId(1L) == null) { "admin permanent key should be gone before login" }

        // Login should still succeed: AuthServiceImpl recreates the key on the fly
        val captcha = assertOk(getJson("/api/admin/auth/captcha"))
        val key = captcha["captchaKey"].asText()
        val body = mapOf(
            "username" to "admin",
            "password" to adminPasswordSha256,
            "captcha" to captchaCodeFor(key),
            "captchaKey" to key,
        )
        val data = assertOk(postJson("/api/admin/auth/login", body))
        assertTrue(data["routerApiKey"].asText().startsWith("hnx_sk_live_"))

        // And the permanent key has been rebuilt in the database
        val rebuilt = apiKeyMapper.selectPermanentKeyByUserId(1L)
        assertNotNull(rebuilt, "permanent key should be recreated by login self-heal")
        assertEquals("PERMANENT", rebuilt.keyType)
        assertEquals(1, rebuilt.enabled)
    }
}

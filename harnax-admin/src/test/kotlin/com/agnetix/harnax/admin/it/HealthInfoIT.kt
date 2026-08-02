package com.agnetix.harnax.admin.it

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.http.HttpMethod
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Health / info / login-methods regression: /api/admin/health, /api/admin/info,
 * /api/admin/auth/login-methods
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HealthInfoIT : BaseAdminIT() {

    @Test
    fun `health endpoint returns OK`() {
        val data = assertOk(getJson("/api/admin/health"))
        assertEquals("OK", data.asText())
    }

    @Test
    fun `info endpoint returns system version information`() {
        val data = assertOk(getJson("/api/admin/info"))
        assertTrue(data.isObject, "info should return a SystemInfo object: $data")
    }

    @Test
    fun `login-methods is public and lists username method`() {
        // No token: endpoint is permitAll in SecurityConfig
        val node = parseBody(exchange(HttpMethod.GET, "/api/admin/auth/login-methods", token = null))
        assertEquals(200, node["code"].asInt())
        val methods = node["data"]["methods"]
        assertTrue(methods.isArray)
        val values = methods.map { it.asText() }
        assertTrue("username" in values, "username login method expected, got: $values")
    }
}

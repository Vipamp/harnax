package com.agnetix.harnax.admin.it

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.http.HttpMethod
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Token refresh regression: /api/admin/auth/refresh-token
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TokenRefreshIT : BaseAdminIT() {

    @Test
    fun `refresh token returns a new valid token inheriting tenant`() {
        val data = assertOk(postJson("/api/admin/auth/refresh-token"))

        val newToken = data["accessToken"].asText()
        assertTrue(newToken.isNotBlank())
        assertTrue(jwtUtil.validateToken(newToken), "refreshed token should be a valid JWT")
        assertEquals(1L, data["tenantId"].asLong(), "tenant id from original token should be inherited")
        assertTrue(data["expiresIn"].asLong() > 0)

        // The refreshed token works against a protected endpoint
        val page = parseBody(
            exchange(HttpMethod.GET, "/api/admin/users/page?pageNum=1&pageSize=1", null, newToken),
        )
        assertEquals(200, page["code"].asInt())
    }

    @Test
    fun `refresh token without authentication returns 401`() {
        val response = exchange(HttpMethod.POST, "/api/admin/auth/refresh-token", token = null)
        assertEquals(401, response.statusCode.value())
    }
}

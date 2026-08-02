package com.agnetix.harnax.admin.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * JwtUtil 单元测试
 * 覆盖 token 生成、解析、过期校验、签名错误、claims 提取等场景
 *
 * @author agnetix
 * @since 2026-06-28
 */
@DisplayName("JwtUtil JWT 工具测试")
class JwtUtilTest {

    companion object {
        // HMAC-SHA256 要求密钥至少 256 bit（32 字节）
        private const val TEST_SECRET = "harnax-test-secret-key-0123456789abcdef"
        private const val OTHER_SECRET = "another-test-secret-key-9876543210fedcba"
        private const val TEST_EXPIRATION = 7200000L
    }

    private lateinit var jwtUtil: JwtUtil

    /**
     * 通过反射注入 @Value 字段，构造固定 secret / expiration 的 JwtUtil
     */
    private fun createJwtUtil(secret: String = TEST_SECRET, expiration: Long = TEST_EXPIRATION): JwtUtil {
        val util = JwtUtil()
        val secretField = JwtUtil::class.java.getDeclaredField("secret")
        secretField.isAccessible = true
        secretField.set(util, secret)
        val expirationField = JwtUtil::class.java.getDeclaredField("expiration")
        expirationField.isAccessible = true
        expirationField.set(util, expiration)
        return util
    }

    @BeforeEach
    fun setUp() {
        jwtUtil = createJwtUtil()
    }

    @Nested
    @DisplayName("生成 Token 测试")
    inner class GenerateTokenTests {

        @Test
        @DisplayName("generateToken - 生成的token非空且为JWT三段式结构")
        fun `generateToken should return non-empty jwt with three parts`() {
            val token = jwtUtil.generateToken(1L, "admin")

            assertNotNull(token)
            assertTrue(token.isNotBlank())
            assertEquals(3, token.split(".").size, "JWT 应包含 header.payload.signature 三段")
        }

        @Test
        @DisplayName("generateToken - 不同用户生成的token不同")
        fun `generateToken should produce different tokens for different users`() {
            val token1 = jwtUtil.generateToken(1L, "admin")
            val token2 = jwtUtil.generateToken(2L, "user2")

            assertNotEquals(token1, token2)
        }

        @Test
        @DisplayName("generateToken - 携带tenantId生成token成功")
        fun `generateToken should include tenantId when provided`() {
            val token = jwtUtil.generateToken(1L, "admin", tenantId = 100L)

            assertNotNull(token)
            assertEquals(100L, jwtUtil.getTenantIdFromToken(token))
        }

        @Test
        @DisplayName("generateToken - 不携带tenantId生成token成功")
        fun `generateToken should work without tenantId`() {
            val token = jwtUtil.generateToken(1L, "admin")

            assertNotNull(token)
            assertNull(jwtUtil.getTenantIdFromToken(token))
        }
    }

    @Nested
    @DisplayName("校验 Token 测试")
    inner class ValidateTokenTests {

        @Test
        @DisplayName("validateToken - 有效token校验通过")
        fun `validateToken should return true for valid token`() {
            val token = jwtUtil.generateToken(1L, "admin")

            assertTrue(jwtUtil.validateToken(token))
        }

        @Test
        @DisplayName("validateToken - 过期token校验失败")
        fun `validateToken should return false for expired token`() {
            // 过期时间为负数，生成即过期
            val expiredJwtUtil = createJwtUtil(expiration = -1000L)
            val token = expiredJwtUtil.generateToken(1L, "admin")

            assertFalse(expiredJwtUtil.validateToken(token))
        }

        @Test
        @DisplayName("validateToken - 非法格式token校验失败")
        fun `validateToken should return false for malformed token`() {
            assertFalse(jwtUtil.validateToken("not-a-jwt-token"))
            assertFalse(jwtUtil.validateToken(""))
            assertFalse(jwtUtil.validateToken("a.b.c"))
        }

        @Test
        @DisplayName("validateToken - 其他密钥签名的token校验失败")
        fun `validateToken should return false for token signed with different secret`() {
            val otherJwtUtil = createJwtUtil(secret = OTHER_SECRET)
            val tokenSignedByOther = otherJwtUtil.generateToken(1L, "admin")

            assertFalse(jwtUtil.validateToken(tokenSignedByOther))
        }

        @Test
        @DisplayName("validateToken - 篡改payload后校验失败")
        fun `validateToken should return false for tampered token`() {
            val token = jwtUtil.generateToken(1L, "admin")
            val parts = token.split(".")
            val tampered = parts[0] + ".eyJ1c2VySWQiOjk5OX0." + parts[2]

            assertFalse(jwtUtil.validateToken(tampered))
        }
    }

    @Nested
    @DisplayName("提取 Claims 测试")
    inner class ClaimsExtractionTests {

        @Test
        @DisplayName("getUsernameFromToken - 正确提取用户名")
        fun `getUsernameFromToken should return username`() {
            val token = jwtUtil.generateToken(1L, "admin")

            assertEquals("admin", jwtUtil.getUsernameFromToken(token))
        }

        @Test
        @DisplayName("getUsernameFromToken - 签名错误token抛出异常")
        fun `getUsernameFromToken should throw exception for token with wrong signature`() {
            val otherJwtUtil = createJwtUtil(secret = OTHER_SECRET)
            val tokenSignedByOther = otherJwtUtil.generateToken(1L, "admin")

            assertThrows<Exception> {
                jwtUtil.getUsernameFromToken(tokenSignedByOther)
            }
        }

        @Test
        @DisplayName("getUsernameFromToken - 非法token抛出异常")
        fun `getUsernameFromToken should throw exception for malformed token`() {
            assertThrows<Exception> {
                jwtUtil.getUsernameFromToken("invalid-token")
            }
        }

        @Test
        @DisplayName("getUserIdFromToken - 正确提取用户ID")
        fun `getUserIdFromToken should return userId`() {
            val token = jwtUtil.generateToken(42L, "admin")

            assertEquals(42L, jwtUtil.getUserIdFromToken(token))
        }

        @Test
        @DisplayName("getUserIdFromToken - 非法token抛出异常")
        fun `getUserIdFromToken should throw exception for invalid token`() {
            assertThrows<Exception> {
                jwtUtil.getUserIdFromToken("invalid-token")
            }
        }

        @Test
        @DisplayName("getTenantIdFromToken - 正确提取租户ID")
        fun `getTenantIdFromToken should return tenantId`() {
            val token = jwtUtil.generateToken(1L, "admin", tenantId = 8L)

            assertEquals(8L, jwtUtil.getTenantIdFromToken(token))
        }

        @Test
        @DisplayName("getTenantIdFromToken - token未携带tenantId返回null")
        fun `getTenantIdFromToken should return null when tenantId absent`() {
            val token = jwtUtil.generateToken(1L, "admin")

            assertNull(jwtUtil.getTenantIdFromToken(token))
        }

        @Test
        @DisplayName("getTenantIdFromToken - 非法token返回null而不抛异常")
        fun `getTenantIdFromToken should return null for invalid token`() {
            assertNull(jwtUtil.getTenantIdFromToken("invalid-token"))
        }

        @Test
        @DisplayName("getIsAdminFromToken - 非法token返回null而不抛异常")
        fun `getIsAdminFromToken should return null for invalid token`() {
            assertNull(jwtUtil.getIsAdminFromToken("invalid-token"))
        }

        @Test
        @DisplayName("getIsAdminFromToken - isAdmin=1的token精确返回1")
        fun `getIsAdminFromToken should return 1 for admin token`() {
            val token = jwtUtil.generateToken(1L, "admin", isAdmin = 1)

            assertEquals(1, jwtUtil.getIsAdminFromToken(token))
        }

        @Test
        @DisplayName("getIsAdminFromToken - isAdmin=0的token精确返回0")
        fun `getIsAdminFromToken should return 0 for non-admin token`() {
            val token = jwtUtil.generateToken(1L, "user", isAdmin = 0)

            assertEquals(0, jwtUtil.getIsAdminFromToken(token))
        }

        @Test
        @DisplayName("getIsAdminFromToken - 默认参数（不传isAdmin）返回0")
        fun `getIsAdminFromToken should return 0 when isAdmin not specified`() {
            val token = jwtUtil.generateToken(1L, "user")

            assertEquals(0, jwtUtil.getIsAdminFromToken(token))
        }

        @Test
        @DisplayName("getUserIdFromToken - userId为Long最大值时正确提取")
        fun `getUserIdFromToken should return Long MAX_VALUE userId`() {
            val token = jwtUtil.generateToken(Long.MAX_VALUE, "admin")

            assertEquals(Long.MAX_VALUE, jwtUtil.getUserIdFromToken(token))
        }

        @Test
        @DisplayName("getUserIdFromToken - 普通小值userId仍正确提取（回归）")
        fun `getUserIdFromToken should return small userId`() {
            val token = jwtUtil.generateToken(42L, "admin")

            assertEquals(42L, jwtUtil.getUserIdFromToken(token))
        }
    }

    @Nested
    @DisplayName("过期时间配置测试")
    inner class ExpirationTimeTests {

        @Test
        @DisplayName("getExpirationTime - 返回注入的过期时间")
        fun `getExpirationTime should return configured expiration`() {
            assertEquals(TEST_EXPIRATION, jwtUtil.getExpirationTime())
        }

        @Test
        @DisplayName("getExpirationTime - 自定义过期时间生效")
        fun `getExpirationTime should reflect custom expiration`() {
            val customJwtUtil = createJwtUtil(expiration = 1000L)

            assertEquals(1000L, customJwtUtil.getExpirationTime())
        }
    }
}

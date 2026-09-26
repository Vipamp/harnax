package com.agnetix.harnax.admin.util

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.security.SecurityUtils
import com.agnetix.harnax.entity.SysUser
import com.agnetix.harnax.mapper.SysUserMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * TenantResolver unit tests.
 *
 * These pin the order of the chain every admin service now shares, and the one property that makes
 * converging the old `TenantContext.getTenantId() ?: 1` copies safe: a request only ever moves onto a
 * tenant its own token or its own account row names, and a request with nothing to resolve from - an
 * internal call, a scheduled run - keeps answering with the default exactly as before.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TenantResolver unit tests")
class TenantResolverTest {

    @Mock
    private lateinit var jwtUtil: JwtUtil

    @Mock
    private lateinit var sysUserMapper: SysUserMapper

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
        RequestContextHolder.resetRequestAttributes()
        SecurityContextHolder.clearContext()
        // The singleton is what backs SecurityUtils.getCurrentUser(); leaving it registered would leak
        // this class's fake account into every other test running in this JVM.
        val instanceField = SecurityUtils::class.java.getDeclaredField("instance")
        instanceField.isAccessible = true
        instanceField.set(null, null)
    }

    /** Puts an `Authorization: Bearer` header on the current request; null leaves the request header-less. */
    private fun requestWithToken(token: String?) {
        val request = MockHttpServletRequest()
        if (token != null) {
            request.addHeader("Authorization", "Bearer $token")
        }
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
    }

    /** Registers the [SecurityUtils] singleton with an account row carrying [userTenantId], as an authenticated request has. */
    private fun authenticatedUserInTenant(userTenantId: Long?) {
        SecurityUtils(sysUserMapper).init()
        `when`(sysUserMapper.selectByUsername(MEMBER)).thenReturn(
            SysUser().apply {
                id = 7L
                username = MEMBER
                tenantId = userTenantId
            },
        )
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(MEMBER, null, ArrayList())
    }

    /** Makes every fallback below step 1 explode, so a test can tell "won" from "happened to agree". */
    private fun makeFallbacksThrow() {
        `when`(jwtUtil.getTenantIdFromToken(anyString())).thenThrow(IllegalStateException("the token must not be read"))
        authenticatedUserInTenant(null)
        `when`(sysUserMapper.selectByUsername(MEMBER)).thenThrow(IllegalStateException("the account row must not be read"))
    }

    @Nested
    @DisplayName("Chain order tests")
    inner class ChainOrderTests {

        @Test
        @DisplayName("resolve - the verified X-Tenant-ID wins over the token claim and the account row")
        fun `resolve should prefer the tenant context`() {
            TenantContext.setTenantId(5L)
            requestWithToken(MEMBER_TOKEN)
            `when`(jwtUtil.getTenantIdFromToken(MEMBER_TOKEN)).thenReturn(9L)
            authenticatedUserInTenant(3L)

            assertEquals(5L, TenantResolver.resolve(jwtUtil))
        }

        @Test
        @DisplayName("resolve - with a header nothing below step 1 is read at all")
        fun `resolve should not consult the fallbacks when the header is there`() {
            TenantContext.setTenantId(5L)
            requestWithToken(MEMBER_TOKEN)
            makeFallbacksThrow()

            assertEquals(5L, TenantResolver.resolve(jwtUtil), "a workspace switch the interceptor verified must stand alone")
        }

        @Test
        @DisplayName("resolve - without a header the tenant the token claims is used")
        fun `resolve should fall back to the token claim`() {
            requestWithToken(MEMBER_TOKEN)
            `when`(jwtUtil.getTenantIdFromToken(MEMBER_TOKEN)).thenReturn(3L)
            authenticatedUserInTenant(9L)

            assertEquals(3L, TenantResolver.resolve(jwtUtil))
        }

        @Test
        @DisplayName("resolve - a header-less request from a tenant-3 account with no token resolves 3")
        fun `resolve should fall back to the user record`() {
            requestWithToken(null)
            authenticatedUserInTenant(3L)

            assertEquals(3L, TenantResolver.resolve(jwtUtil))
        }

        @Test
        @DisplayName("resolve - a claim of 0 is refused and the account row answers instead")
        fun `resolve should refuse a zero claim`() {
            requestWithToken(MEMBER_TOKEN)
            // Mockito hands an unstubbled `Long?` back as 0, and the old copies had to guard against it;
            // ids start at 1, so 0 reads as "no claim" here too.
            `when`(jwtUtil.getTenantIdFromToken(MEMBER_TOKEN)).thenReturn(0L)
            authenticatedUserInTenant(3L)

            assertEquals(3L, TenantResolver.resolve(jwtUtil))
        }
    }

    @Nested
    @DisplayName("Default tenant tests")
    inner class DefaultTenantTests {

        @Test
        @DisplayName("resolve - a request whose caller has no tenant anywhere keeps the default")
        fun `resolve should answer the default when nothing carries a tenant`() {
            requestWithToken(null)
            authenticatedUserInTenant(null)

            assertEquals(1L, TenantResolver.resolve(jwtUtil))
            assertEquals(1L, TenantResolver.DEFAULT_TENANT_ID, "the default is the tenant_id column default")
        }

        @Test
        @DisplayName("resolve - an internal call bearing a shared secret keeps the default tenant")
        fun `resolve should answer the default for an internal caller`() {
            // The shared secret does arrive as `Bearer <secret>`, so it reaches the reader, which answers
            // null for anything that is not a JWT - the behaviour the real JwtUtil has.
            requestWithToken(SHARED_SECRET)
            `when`(jwtUtil.getTenantIdFromToken(SHARED_SECRET)).thenReturn(null)

            assertEquals(1L, TenantResolver.resolve(jwtUtil))
        }

        @Test
        @DisplayName("resolve - a request outside any servlet context keeps the default tenant")
        fun `resolve should answer the default with no request at all`() {
            // A scheduled run or a runtime callback has no request to take a header or a token from.
            RequestContextHolder.resetRequestAttributes()

            assertEquals(1L, TenantResolver.resolve(jwtUtil))
        }
    }

    @Nested
    @DisplayName("Cannot-throw tests")
    inner class RobustnessTests {

        @Test
        @DisplayName("resolve - a reader that throws still answers the default tenant")
        fun `resolve should not propagate a token reader failure`() {
            requestWithToken(MEMBER_TOKEN)
            `when`(jwtUtil.getTenantIdFromToken(anyString())).thenThrow(IllegalStateException("reader blew up"))

            assertEquals(1L, TenantResolver.resolve(jwtUtil))
        }

        @Test
        @DisplayName("resolve - an account lookup that throws still answers the default tenant")
        fun `resolve should not propagate a user lookup failure`() {
            requestWithToken(null)
            authenticatedUserInTenant(3L)
            `when`(sysUserMapper.selectByUsername(MEMBER)).thenThrow(IllegalStateException("database is down"))

            assertEquals(1L, TenantResolver.resolve(jwtUtil))
        }
    }

    private companion object {
        const val MEMBER = "member"
        const val MEMBER_TOKEN = "member-token"

        /** The placeholder `application.yml` ships, which is what a sandbox CLI presents. */
        const val SHARED_SECRET = "change-me-in-production-min-32-chars!!"
    }
}

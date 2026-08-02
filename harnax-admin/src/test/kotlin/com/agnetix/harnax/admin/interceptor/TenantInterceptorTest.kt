package com.agnetix.harnax.admin.interceptor

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.i18n.MessageUtil
import com.agnetix.harnax.admin.security.SecurityUtils
import com.agnetix.harnax.entity.SysUser
import com.agnetix.harnax.entity.UserTenantEntity
import com.agnetix.harnax.mapper.SysUserMapper
import com.agnetix.harnax.mapper.UserTenantMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.quality.Strictness
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

/**
 * TenantInterceptor 单元测试
 * 使用 MockHttpServletRequest/MockHttpServletResponse 模拟请求
 * 覆盖 preHandle 设置租户上下文、无租户头放行、权限校验及 afterCompletion 清理 ThreadLocal
 *
 * @author agnetix
 * @since 2026-06-28
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TenantInterceptor 租户上下文拦截器测试")
class TenantInterceptorTest {

    @Mock
    private lateinit var userTenantMapper: UserTenantMapper

    @Mock
    private lateinit var messageUtil: MessageUtil

    @Mock
    private lateinit var sysUserMapper: SysUserMapper

    private lateinit var interceptor: TenantInterceptor

    private lateinit var currentUser: SysUser

    private val handler = Any()

    @BeforeEach
    fun setUp() {
        interceptor = TenantInterceptor(userTenantMapper, messageUtil)

        currentUser = SysUser().apply {
            id = 1L
            username = "testuser"
            isAdmin = 0
            status = 1
        }

        // SecurityUtils.getCurrentUser() 依赖静态 instance + SecurityContext
        // 通过 init() 注入持有 mock mapper 的实例
        val securityUtils = SecurityUtils(sysUserMapper)
        securityUtils.init()
        `when`(sysUserMapper.selectByUsername("testuser")).thenReturn(currentUser)

        // messageUtil 返回 key 本身，便于断言
        `when`(messageUtil.getMessage(anyString())).thenAnswer { it.arguments[0] as String }

        SecurityContextHolder.clearContext()
        TenantContext.clear()
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
        TenantContext.clear()
    }

    private fun login(username: String = "testuser") {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(username, null, emptyList())
    }

    private fun buildRequest(tenantIdHeader: String? = null): MockHttpServletRequest {
        val request = MockHttpServletRequest("GET", "/api/admin/agents")
        if (tenantIdHeader != null) {
            request.addHeader("X-Tenant-ID", tenantIdHeader)
        }
        return request
    }

    @Nested
    @DisplayName("preHandle 正常流程测试")
    inner class PreHandleSuccessTests {

        @Test
        @DisplayName("preHandle - 普通用户属于租户时设置租户上下文并放行")
        fun `preHandle should set tenant context for member user`() {
            // Given
            login()
            val request = buildRequest("100")
            val response = MockHttpServletResponse()
            val userTenant = UserTenantEntity().apply {
                userId = 1L
                tenantId = 100L
                role = "member"
                status = 1
            }
            `when`(userTenantMapper.selectByUserIdAndTenantId(1L, 100L)).thenReturn(userTenant)

            // When
            val result = interceptor.preHandle(request, response, handler)

            // Then
            assertTrue(result)
            assertEquals(100L, TenantContext.getTenantId())
            verify(userTenantMapper).selectByUserIdAndTenantId(1L, 100L)
        }

        @Test
        @DisplayName("preHandle - 全局管理员跳过租户归属校验直接设置上下文")
        fun `preHandle should skip verification for global admin`() {
            // Given
            login()
            currentUser.isAdmin = 1
            val request = buildRequest("200")
            val response = MockHttpServletResponse()

            // When
            val result = interceptor.preHandle(request, response, handler)

            // Then
            assertTrue(result)
            assertEquals(200L, TenantContext.getTenantId())
            // 管理员不需要查询用户-租户关联表
            verify(userTenantMapper, never()).selectByUserIdAndTenantId(any(), any())
        }
    }

    @Nested
    @DisplayName("无租户头请求测试")
    inner class NoTenantHeaderTests {

        @Test
        @DisplayName("preHandle - 无 X-Tenant-ID 头时直接放行且不设置上下文")
        fun `preHandle should pass through when no tenant header`() {
            // Given
            val request = buildRequest()
            val response = MockHttpServletResponse()

            // When
            val result = interceptor.preHandle(request, response, handler)

            // Then
            assertTrue(result)
            assertNull(TenantContext.getTenantId())
            verify(userTenantMapper, never()).selectByUserIdAndTenantId(any(), any())
        }

        @Test
        @DisplayName("preHandle - 空白租户头视为无租户头放行")
        fun `preHandle should pass through when tenant header is blank`() {
            // Given
            val request = buildRequest("   ")
            val response = MockHttpServletResponse()

            // When
            val result = interceptor.preHandle(request, response, handler)

            // Then
            assertTrue(result)
            assertNull(TenantContext.getTenantId())
        }
    }

    @Nested
    @DisplayName("异常流程测试")
    inner class PreHandleFailureTests {

        @Test
        @DisplayName("preHandle - 非法租户ID抛出 BizException")
        fun `preHandle should throw BizException for invalid tenant id`() {
            // Given
            login()
            val request = buildRequest("not-a-number")
            val response = MockHttpServletResponse()

            // When & Then
            val exception = assertThrows<BizException> {
                interceptor.preHandle(request, response, handler)
            }
            assertEquals("error.tenant.invalid_id", exception.message)
            assertNull(TenantContext.getTenantId())
        }

        @Test
        @DisplayName("preHandle - 未登录用户抛出 BizException")
        fun `preHandle should throw BizException when user not logged in`() {
            // Given - 未设置 SecurityContext
            val request = buildRequest("100")
            val response = MockHttpServletResponse()

            // When & Then
            val exception = assertThrows<BizException> {
                interceptor.preHandle(request, response, handler)
            }
            assertEquals("error.auth.not_logged_in", exception.message)
            assertNull(TenantContext.getTenantId())
        }

        @Test
        @DisplayName("preHandle - 用户不属于该租户抛出 BizException")
        fun `preHandle should throw BizException when user not in tenant`() {
            // Given
            login()
            val request = buildRequest("300")
            val response = MockHttpServletResponse()
            `when`(userTenantMapper.selectByUserIdAndTenantId(1L, 300L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                interceptor.preHandle(request, response, handler)
            }
            assertEquals("error.tenant.access_denied", exception.message)
            assertNull(TenantContext.getTenantId(), "校验失败不应设置租户上下文")
        }

        @Test
        @DisplayName("preHandle - 用户在租户中被禁用抛出 BizException")
        fun `preHandle should throw BizException when user disabled in tenant`() {
            // Given
            login()
            val request = buildRequest("100")
            val response = MockHttpServletResponse()
            val disabledUserTenant = UserTenantEntity().apply {
                userId = 1L
                tenantId = 100L
                role = "member"
                status = 0
            }
            `when`(userTenantMapper.selectByUserIdAndTenantId(1L, 100L)).thenReturn(disabledUserTenant)

            // When & Then
            val exception = assertThrows<BizException> {
                interceptor.preHandle(request, response, handler)
            }
            assertEquals("error.tenant.user_disabled", exception.message)
            assertNull(TenantContext.getTenantId())
        }
    }

    @Nested
    @DisplayName("afterCompletion 清理测试")
    inner class AfterCompletionTests {

        @Test
        @DisplayName("afterCompletion - 清理 ThreadLocal 中的租户上下文")
        fun `afterCompletion should clear tenant context`() {
            // Given
            TenantContext.setTenantId(100L)
            assertEquals(100L, TenantContext.getTenantId())

            // When
            interceptor.afterCompletion(buildRequest(), MockHttpServletResponse(), handler, null)

            // Then
            assertNull(TenantContext.getTenantId(), "afterCompletion 应清理 ThreadLocal 防止内存泄漏")
        }

        @Test
        @DisplayName("afterCompletion - 请求异常时同样清理租户上下文")
        fun `afterCompletion should clear tenant context even with exception`() {
            // Given
            TenantContext.setTenantId(100L)

            // When
            interceptor.afterCompletion(
                buildRequest(),
                MockHttpServletResponse(),
                handler,
                RuntimeException("request failed"),
            )

            // Then
            assertNull(TenantContext.getTenantId())
        }

        @Test
        @DisplayName("afterCompletion - 未设置租户上下文时清理不报错")
        fun `afterCompletion should not fail when context not set`() {
            // Given
            assertNull(TenantContext.getTenantId())

            // When & Then - 不抛异常
            interceptor.afterCompletion(buildRequest(), MockHttpServletResponse(), handler, null)
            assertNull(TenantContext.getTenantId())
        }
    }
}

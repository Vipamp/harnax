package com.agnetix.harnax.admin.security

import com.agnetix.harnax.entity.SysUser
import com.agnetix.harnax.mapper.SysUserMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

/**
 * SecurityUtils 单元测试
 * 测试从 SecurityContext 获取当前登录用户的各种场景
 *
 * @author agnetix
 * @since 2026-06-28
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SecurityUtils 安全工具测试")
class SecurityUtilsTest {

    @Mock
    private lateinit var sysUserMapper: SysUserMapper

    private lateinit var securityUtils: SecurityUtils

    @BeforeEach
    fun setUp() {
        securityUtils = SecurityUtils(sysUserMapper)
        // 模拟 @PostConstruct 生命周期，注册静态实例
        securityUtils.init()
        SecurityContextHolder.clearContext()
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
        // 重置静态实例，避免影响其他测试
        resetInstance()
    }

    private fun resetInstance() {
        val companionField = SecurityUtils::class.java.getDeclaredField("instance")
        companionField.isAccessible = true
        companionField.set(null, null)
    }

    private fun setAuthentication(principal: Any?) {
        val authentication = UsernamePasswordAuthenticationToken(principal, null, ArrayList())
        SecurityContextHolder.getContext().authentication = authentication
    }

    @Nested
    @DisplayName("获取当前用户测试")
    inner class GetCurrentUserTests {

        @Test
        @DisplayName("getCurrentUser - 已认证用户存在时返回用户")
        fun `getCurrentUser should return user when authenticated and user exists`() {
            val user = SysUser().apply {
                id = 1L
                username = "admin"
            }
            `when`(sysUserMapper.selectByUsername("admin")).thenReturn(user)
            setAuthentication("admin")

            val result = SecurityUtils.getCurrentUser()

            assertNotNull(result)
            assertEquals(1L, result?.id)
            assertEquals("admin", result?.username)
        }

        @Test
        @DisplayName("getCurrentUser - 用户在数据库中不存在时返回null")
        fun `getCurrentUser should return null when user not found in database`() {
            `when`(sysUserMapper.selectByUsername("ghost")).thenReturn(null)
            setAuthentication("ghost")

            assertNull(SecurityUtils.getCurrentUser())
        }

        @Test
        @DisplayName("getCurrentUser - 无认证信息时返回null")
        fun `getCurrentUser should return null when no authentication`() {
            SecurityContextHolder.clearContext()

            assertNull(SecurityUtils.getCurrentUser())
        }

        @Test
        @DisplayName("getCurrentUser - 静态实例未初始化时返回null")
        fun `getCurrentUser should return null when instance not initialized`() {
            setAuthentication("admin")
            // 重置静态实例，模拟 Spring 容器尚未初始化 SecurityUtils Bean
            resetInstance()

            assertNull(SecurityUtils.getCurrentUser())
        }
    }

    @Nested
    @DisplayName("初始化测试")
    inner class InitTests {

        @Test
        @DisplayName("init - 初始化后静态方法可访问Mapper")
        fun `init should register instance so static method can access mapper`() {
            val user = SysUser().apply {
                id = 2L
                username = "operator"
            }
            `when`(sysUserMapper.selectByUsername("operator")).thenReturn(user)

            // 先重置再手动初始化
            resetInstance()
            securityUtils.init()
            setAuthentication("operator")

            val result = SecurityUtils.getCurrentUser()

            assertNotNull(result)
            assertEquals("operator", result?.username)
        }

        @Test
        @DisplayName("init - 多次初始化以最后一个实例为准")
        fun `init should replace instance when called again`() {
            val otherMapper = org.mockito.Mockito.mock(SysUserMapper::class.java)
            val otherUser = SysUser().apply {
                id = 3L
                username = "admin"
            }
            `when`(otherMapper.selectByUsername("admin")).thenReturn(otherUser)

            val otherSecurityUtils = SecurityUtils(otherMapper)
            otherSecurityUtils.init()
            setAuthentication("admin")

            val result = SecurityUtils.getCurrentUser()

            assertNotNull(result)
            assertEquals(3L, result?.id)
        }
    }
}

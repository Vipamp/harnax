package com.agnetix.harnax.admin.runner

import com.agnetix.harnax.admin.dto.SysUserCreateRequest
import com.agnetix.harnax.admin.service.SysUserService
import com.agnetix.harnax.entity.SysUser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.quality.Strictness

/**
 * AdminUserInitializer 单元测试
 * 测试启动时 admin 用户自动初始化逻辑
 *
 * @author agnetix
 * @since 2026-06-28
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AdminUserInitializer 管理员用户初始化测试")
class AdminUserInitializerTest {

    companion object {
        // SHA-256("admin123")，主代码先做 SHA-256 再交由 service 做 BCrypt
        private const val ADMIN123_SHA256 = "240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9"
    }

    @Mock
    private lateinit var sysUserService: SysUserService

    @Captor
    private lateinit var requestCaptor: ArgumentCaptor<SysUserCreateRequest>

    @InjectMocks
    private lateinit var initializer: AdminUserInitializer

    @Nested
    @DisplayName("admin 不存在时的初始化测试")
    inner class CreateAdminTests {

        @Test
        @DisplayName("run - admin 不存在时创建用户并校验字段")
        fun `run should create admin user with expected fields when not exists`() {
            // Given
            `when`(sysUserService.getByUsername("admin")).thenReturn(null)
            `when`(sysUserService.createUser(any())).thenReturn(true)

            // When
            initializer.run()

            // Then
            verify(sysUserService, times(1)).createUser(requestCaptor.capture())
            val request = requestCaptor.value
            assertEquals("admin", request.username)
            assertEquals("System Administrator", request.nickname)
            assertEquals("admin@harnax.com", request.email)
            assertEquals("13800138000", request.phone)
            assertEquals(1, request.gender)
        }

        @Test
        @DisplayName("run - 传给 createUser 的密码是 admin123 的 SHA-256 值（后端再做 BCrypt）")
        fun `run should pass sha256 hashed password to createUser`() {
            // Given
            `when`(sysUserService.getByUsername("admin")).thenReturn(null)
            `when`(sysUserService.createUser(any())).thenReturn(true)

            // When
            initializer.run()

            // Then - 密码不是明文，而是固定的 SHA-256 十六进制串
            verify(sysUserService).createUser(requestCaptor.capture())
            val request = requestCaptor.value
            assertNotEquals("admin123", request.password, "不应传明文密码")
            assertEquals(ADMIN123_SHA256, request.password)
            // 64 位十六进制字符串
            assertEquals(64, request.password.length)
            assertTrue(request.password.matches(Regex("^[0-9a-f]{64}$")))
        }

        @Test
        @DisplayName("run - getByUsername 抛异常时视为不存在并继续创建")
        fun `run should treat getByUsername exception as user not exists`() {
            // Given
            `when`(sysUserService.getByUsername("admin")).thenThrow(RuntimeException("table not ready"))
            `when`(sysUserService.createUser(any())).thenReturn(true)

            // When - 不应抛出异常
            assertDoesNotThrow { initializer.run() }

            // Then
            verify(sysUserService, times(1)).createUser(any())
        }
    }

    @Nested
    @DisplayName("admin 已存在时跳过测试")
    inner class SkipExistingAdminTests {

        @Test
        @DisplayName("run - admin 已存在时跳过创建")
        fun `run should skip creation when admin already exists`() {
            // Given
            val existingAdmin = SysUser().apply {
                id = 1L
                username = "admin"
                isAdmin = 1
            }
            `when`(sysUserService.getByUsername("admin")).thenReturn(existingAdmin)

            // When
            initializer.run()

            // Then
            verify(sysUserService, times(1)).getByUsername("admin")
            verify(sysUserService, never()).createUser(any())
        }
    }

    @Nested
    @DisplayName("异常不中断启动测试")
    inner class ExceptionHandlingTests {

        @Test
        @DisplayName("run - createUser 抛异常被捕获，不中断应用启动")
        fun `run should not propagate exception when createUser fails`() {
            // Given
            `when`(sysUserService.getByUsername("admin")).thenReturn(null)
            `when`(sysUserService.createUser(any())).thenThrow(RuntimeException("DB insert failed"))

            // When & Then - 主代码 catch 了创建异常，不应向上传播
            assertDoesNotThrow { initializer.run() }
            verify(sysUserService, times(1)).createUser(any())
        }
    }
}

package com.agnetix.harnax.admin.init

import com.agnetix.harnax.admin.dto.ApiKeyCreatedResponse
import com.agnetix.harnax.admin.service.ApiKeyService
import com.agnetix.harnax.entity.ApiKeyEntity
import com.agnetix.harnax.entity.SysUser
import com.agnetix.harnax.mapper.ApiKeyMapper
import com.agnetix.harnax.mapper.SysUserMapper
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.quality.Strictness

/**
 * PermanentKeyInitializer 单元测试
 * 测试启动时系统Key初始化和存量用户永久Key补建逻辑
 *
 * @author agnetix
 * @since 2026-06-28
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PermanentKeyInitializerTest {

    @Mock
    private lateinit var sysUserMapper: SysUserMapper

    @Mock
    private lateinit var apiKeyService: ApiKeyService

    @Mock
    private lateinit var apiKeyMapper: ApiKeyMapper

    @InjectMocks
    private lateinit var initializer: PermanentKeyInitializer

    @Nested
    @DisplayName("系统Key初始化测试")
    inner class SystemKeyInitTests {

        @Test
        @DisplayName("run - 系统Key初始化成功")
        fun `run should call initSystemKeys successfully`() {
            // Given
            `when`(sysUserMapper.selectAllActive()).thenReturn(emptyList())

            // When
            initializer.run()

            // Then
            verify(apiKeyService, times(1)).initSystemKeys()
        }

        @Test
        @DisplayName("run - 系统Key初始化异常不影响永久Key初始化")
        fun `run should continue permanent key init even when system key init fails`() {
            // Given
            doThrow(RuntimeException("DB error")).`when`(apiKeyService).initSystemKeys()
            `when`(sysUserMapper.selectAllActive()).thenReturn(emptyList())

            // When
            initializer.run()

            // Then - should still attempt to query users
            verify(sysUserMapper, times(1)).selectAllActive()
        }
    }

    @Nested
    @DisplayName("存量用户永久Key补建测试")
    inner class ExistingUserKeyInitTests {

        @Test
        @DisplayName("run - 为没有永久Key的存量用户创建永久Key")
        fun `run should create permanent key for users without one`() {
            // Given
            val user1 = SysUser().apply {
                id = 1L
                username = "user1"
                tenantId = 10L
            }
            val user2 = SysUser().apply {
                id = 2L
                username = "user2"
                tenantId = 10L
            }
            `when`(sysUserMapper.selectAllActive()).thenReturn(listOf(user1, user2))
            `when`(apiKeyMapper.selectPermanentKeyByUserId(anyLong())).thenReturn(null)
            `when`(apiKeyService.createPermanentKeyForUser(anyLong(), anyString(), any()))
                .thenReturn(ApiKeyCreatedResponse(id = 1L, name = "permanent_user1", rawKey = "hnx_sk_live_xxx", keyPrefix = "hnx_sk_l...xxxx"))

            // When
            initializer.run()

            // Then
            verify(apiKeyService, times(1)).createPermanentKeyForUser(eq(1L), eq("user1"), eq(10L))
            verify(apiKeyService, times(1)).createPermanentKeyForUser(eq(2L), eq("user2"), eq(10L))
        }

        @Test
        @DisplayName("run - 已有永久Key的用户跳过")
        fun `run should skip users who already have permanent key`() {
            // Given
            val user1 = SysUser().apply {
                id = 1L
                username = "user1"
                tenantId = 10L
            }
            val user2 = SysUser().apply {
                id = 2L
                username = "user2"
                tenantId = 10L
            }
            val existingKey = ApiKeyEntity().apply {
                id = 100L
                keyType = "PERMANENT"
                userId = 1L
            }

            `when`(sysUserMapper.selectAllActive()).thenReturn(listOf(user1, user2))
            `when`(apiKeyMapper.selectPermanentKeyByUserId(1L)).thenReturn(existingKey)
            `when`(apiKeyMapper.selectPermanentKeyByUserId(2L)).thenReturn(null)
            `when`(apiKeyService.createPermanentKeyForUser(anyLong(), anyString(), any()))
                .thenReturn(ApiKeyCreatedResponse(id = 2L, name = "permanent_user2", rawKey = "hnx_sk_live_yyy", keyPrefix = "hnx_sk_l...yyyy"))

            // When
            initializer.run()

            // Then - user1 should be skipped, user2 should get a key
            verify(apiKeyService, never()).createPermanentKeyForUser(eq(1L), anyString(), any())
            verify(apiKeyService, times(1)).createPermanentKeyForUser(eq(2L), eq("user2"), eq(10L))
        }

        @Test
        @DisplayName("run - 单个用户创建Key失败不影响其他用户")
        fun `run should continue for other users when one user key creation fails`() {
            // Given
            val user1 = SysUser().apply {
                id = 1L
                username = "user1"
                tenantId = 10L
            }
            val user2 = SysUser().apply {
                id = 2L
                username = "user2"
                tenantId = 10L
            }
            `when`(sysUserMapper.selectAllActive()).thenReturn(listOf(user1, user2))
            `when`(apiKeyMapper.selectPermanentKeyByUserId(anyLong())).thenReturn(null)
            `when`(apiKeyService.createPermanentKeyForUser(eq(1L), eq("user1"), eq(10L)))
                .thenThrow(RuntimeException("Encryption failed"))
            `when`(apiKeyService.createPermanentKeyForUser(eq(2L), eq("user2"), eq(10L)))
                .thenReturn(ApiKeyCreatedResponse(id = 2L, name = "permanent_user2", rawKey = "hnx_sk_live_yyy", keyPrefix = "hnx_sk_l...yyyy"))

            // When
            initializer.run()

            // Then - user2 should still get a key even though user1 failed
            verify(apiKeyService, times(1)).createPermanentKeyForUser(eq(2L), eq("user2"), eq(10L))
        }

        @Test
        @DisplayName("run - 没有活跃用户时不做任何操作")
        fun `run should do nothing when no active users`() {
            // Given
            `when`(sysUserMapper.selectAllActive()).thenReturn(emptyList())

            // When
            initializer.run()

            // Then
            verify(apiKeyMapper, never()).selectPermanentKeyByUserId(anyLong())
            verify(apiKeyService, never()).createPermanentKeyForUser(anyLong(), anyString(), any())
        }

        @Test
        @DisplayName("run - selectAllActive 异常被捕获")
        fun `run should catch exception from selectAllActive gracefully`() {
            // Given
            `when`(sysUserMapper.selectAllActive()).thenThrow(RuntimeException("DB connection error"))

            // When - should not throw
            initializer.run()

            // Then
            verify(apiKeyService, times(1)).initSystemKeys()
            verify(apiKeyMapper, never()).selectPermanentKeyByUserId(anyLong())
        }
    }
}

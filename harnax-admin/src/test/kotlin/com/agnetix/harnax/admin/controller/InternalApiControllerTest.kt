package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.util.AesUtil
import com.agnetix.harnax.entity.ApiKeyEntity
import com.agnetix.harnax.mapper.ApiKeyMapper
import com.agnetix.harnax.mapper.ModelMapper
import com.agnetix.harnax.mapper.SessionMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness

/**
 * InternalApiController 单元测试
 * 测试 API Key 验证和系统 Key 获取接口
 *
 * @author agnetix
 * @since 2026-06-28
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InternalApiControllerTest {

    @Mock
    private lateinit var apiKeyMapper: ApiKeyMapper

    @Mock
    private lateinit var sessionMapper: SessionMapper

    @Mock
    private lateinit var modelMapper: ModelMapper

    @Mock
    private lateinit var aesUtil: AesUtil

    @InjectMocks
    private lateinit var controller: InternalApiController

    @Nested
    @DisplayName("API Key 验证接口")
    inner class ValidateApiKeyTests {

        @Test
        @DisplayName("validateApiKey - key存在时返回验证信息")
        fun `validateApiKey should return key info when found`() {
            // Given
            val entity = ApiKeyEntity().apply {
                id = 1L
                name = "test_key"
                keyHash = "abc123hash"
                keyPrefix = "hnx_sk_live_...abcd"
                scopes = "chat"
                tenantId = 10L
                rateLimit = 300
                enabled = 1
                expiresAt = null
                keyType = "PERMANENT"
            }
            `when`(apiKeyMapper.selectByKeyHash("abc123hash")).thenReturn(entity)

            // When
            val result = controller.validateApiKey(
                InternalApiController.ApiKeyValidateRequest(keyHash = "abc123hash"),
            )

            // Then
            assertTrue(result.isSuccess())
            assertNotNull(result.data)
            assertEquals("test_key", result.data?.name)
            assertEquals("chat", result.data?.scopes)
            assertEquals(10L, result.data?.tenantId)
            assertEquals(300, result.data?.rateLimit)
            assertTrue(result.data?.enabled == true)
        }

        @Test
        @DisplayName("validateApiKey - key不存在时返回null")
        fun `validateApiKey should return null when key not found`() {
            // Given
            `when`(apiKeyMapper.selectByKeyHash("nonexistent_hash")).thenReturn(null)

            // When
            val result = controller.validateApiKey(
                InternalApiController.ApiKeyValidateRequest(keyHash = "nonexistent_hash"),
            )

            // Then
            assertTrue(result.isSuccess())
            assertNull(result.data)
        }

        @Test
        @DisplayName("validateApiKey - 返回禁用的key信息")
        fun `validateApiKey should return disabled key info`() {
            // Given
            val entity = ApiKeyEntity().apply {
                id = 2L
                name = "disabled_key"
                keyHash = "def456hash"
                scopes = "chat"
                rateLimit = 60
                enabled = 0
                keyType = "TEMPORARY"
            }
            `when`(apiKeyMapper.selectByKeyHash("def456hash")).thenReturn(entity)

            // When
            val result = controller.validateApiKey(
                InternalApiController.ApiKeyValidateRequest(keyHash = "def456hash"),
            )

            // Then
            assertTrue(result.isSuccess())
            assertNotNull(result.data)
            assertFalse(result.data?.enabled == true)
        }
    }

    @Nested
    @DisplayName("系统Key获取接口")
    inner class GetSystemKeyTests {

        @Test
        @DisplayName("getSystemKey - 系统Key存在时解密并返回")
        fun `getSystemKey should return decrypted key when found`() {
            // Given
            val entity = ApiKeyEntity().apply {
                id = 3L
                name = "system_channel-service"
                keyType = "SYSTEM"
                serviceName = "channel-service"
                rawKeyEncrypted = "encrypted_system_key_base64"
                keyHash = "systemhash"
                keyPrefix = "hnx_sk_live_...ijkl"
                scopes = "chat"
                rateLimit = 600
                enabled = 1
            }
            `when`(apiKeyMapper.selectSystemKeyByServiceName("channel-service")).thenReturn(entity)
            `when`(aesUtil.decrypt("encrypted_system_key_base64")).thenReturn("hnx_sk_live_decrypted_system_key")

            // When
            val result = controller.getSystemKey(
                InternalApiController.SystemKeyRequest(serviceName = "channel-service"),
            )

            // Then
            assertTrue(result.isSuccess())
            assertNotNull(result.data)
            assertEquals("hnx_sk_live_decrypted_system_key", result.data?.rawKey)
            assertEquals("hnx_sk_live_...ijkl", result.data?.keyPrefix)
        }

        @Test
        @DisplayName("getSystemKey - 系统Key不存在时返回null")
        fun `getSystemKey should return null when key not found`() {
            // Given
            `when`(apiKeyMapper.selectSystemKeyByServiceName("unknown-service")).thenReturn(null)

            // When
            val result = controller.getSystemKey(
                InternalApiController.SystemKeyRequest(serviceName = "unknown-service"),
            )

            // Then
            assertTrue(result.isSuccess())
            assertNull(result.data)
        }

        @Test
        @DisplayName("getSystemKey - 解密失败时抛出异常")
        fun `getSystemKey should throw when decryption fails`() {
            // Given
            val entity = ApiKeyEntity().apply {
                id = 3L
                name = "system_channel-service"
                keyType = "SYSTEM"
                serviceName = "channel-service"
                rawKeyEncrypted = "corrupted_encrypted_data"
                keyHash = "systemhash"
                keyPrefix = "hnx_sk_live_...ijkl"
            }
            `when`(apiKeyMapper.selectSystemKeyByServiceName("channel-service")).thenReturn(entity)
            `when`(aesUtil.decrypt("corrupted_encrypted_data")).thenThrow(RuntimeException("AES decryption failed"))

            // When & Then
            org.junit.jupiter.api.assertThrows<RuntimeException> {
                controller.getSystemKey(
                    InternalApiController.SystemKeyRequest(serviceName = "channel-service"),
                )
            }
        }
    }
}

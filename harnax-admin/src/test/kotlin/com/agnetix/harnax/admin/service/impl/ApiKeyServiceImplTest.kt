package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.ApiKeyUpdateRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.util.AesUtil
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.entity.ApiKeyEntity
import com.agnetix.harnax.mapper.ApiKeyMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.isNull
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.never
import org.mockito.quality.Strictness
import java.time.LocalDateTime

/**
 * ApiKeyServiceImpl 单元测试
 * 覆盖永久Key、系统Key、临时Key CRUD保护等新功能
 *
 * @author agnetix
 * @since 2026-06-28
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiKeyServiceImplTest {

    @Mock
    private lateinit var apiKeyMapper: ApiKeyMapper

    @Mock
    private lateinit var jwtUtil: JwtUtil

    @Mock
    private lateinit var aesUtil: AesUtil

    @InjectMocks
    private lateinit var apiKeyService: ApiKeyServiceImpl

    private lateinit var testPermanentKey: ApiKeyEntity
    private lateinit var testTemporaryKey: ApiKeyEntity
    private lateinit var testSystemKey: ApiKeyEntity

    @BeforeEach
    fun setUp() {
        testPermanentKey = ApiKeyEntity().apply {
            id = 1L
            name = "permanent_testuser"
            keyType = "PERMANENT"
            userId = 100L
            rawKeyEncrypted = "encrypted_raw_key_base64"
            keyHash = "abc123hash"
            keyPrefix = "hnx_sk_live_...abcd"
            scopes = "chat"
            rateLimit = 300
            enabled = 1
            expiresAt = null
            creator = "system"
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }

        testTemporaryKey = ApiKeyEntity().apply {
            id = 2L
            name = "temp_key_1"
            keyType = "TEMPORARY"
            userId = null
            keyHash = "def456hash"
            keyPrefix = "hnx_sk_live_...efgh"
            scopes = "chat"
            rateLimit = 60
            enabled = 1
            expiresAt = LocalDateTime.now().plusDays(30)
            creator = "testuser"
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }

        testSystemKey = ApiKeyEntity().apply {
            id = 3L
            name = "system_channel-service"
            keyType = "SYSTEM"
            userId = null
            rawKeyEncrypted = "encrypted_system_key_base64"
            serviceName = "channel-service"
            keyHash = "ghi789hash"
            keyPrefix = "hnx_sk_live_...ijkl"
            scopes = "chat"
            rateLimit = 600
            enabled = 1
            expiresAt = null
            creator = "system"
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }
    }

    @Nested
    @DisplayName("永久Key创建测试")
    inner class CreatePermanentKeyTests {

        @Test
        @DisplayName("createPermanentKeyForUser - 成功创建永久Key")
        fun `createPermanentKeyForUser should create permanent key successfully`() {
            // Given
            `when`(aesUtil.encrypt(anyString())).thenReturn("encrypted_value")
            `when`(apiKeyMapper.insert(any<ApiKeyEntity>())).thenReturn(1)

            // When
            val result = apiKeyService.createPermanentKeyForUser(100L, "testuser", 1L)

            // Then
            assertNotNull(result)
            assertTrue(result.rawKey.startsWith("hnx_sk_live_"))
            assertEquals("permanent_testuser", result.name)
            verify(apiKeyMapper, times(1)).insert(
                argThat { entity ->
                    entity.keyType == "PERMANENT" &&
                        entity.userId == 100L &&
                        entity.scopes == "chat" &&
                        entity.expiresAt == null &&
                        entity.creator == "system"
                },
            )
        }

        @Test
        @DisplayName("createPermanentKeyForUser - 无tenantId也能创建")
        fun `createPermanentKeyForUser should work with null tenantId`() {
            `when`(aesUtil.encrypt(anyString())).thenReturn("encrypted_value")
            `when`(apiKeyMapper.insert(any<ApiKeyEntity>())).thenReturn(1)

            val result = apiKeyService.createPermanentKeyForUser(100L, "testuser", null)

            assertNotNull(result)
            verify(apiKeyMapper, times(1)).insert(
                argThat { entity -> entity.tenantId == null },
            )
        }

        @Test
        @DisplayName("generateRawKey产物 - 长度足够substring(0,12)且前缀格式正确（防御性）")
        fun `generated rawKey should be long enough for prefix substring and have expected format`() {
            // 防御性用例：keyPrefix 逻辑依赖 rawKey.substring(0, 12)，
            // 通过公开的 createPermanentKeyForUser 流程捕获内部 generateRawKey 的产物进行断言
            `when`(aesUtil.encrypt(anyString())).thenReturn("encrypted_value")
            `when`(apiKeyMapper.insert(any<ApiKeyEntity>())).thenReturn(1)

            val result = apiKeyService.createPermanentKeyForUser(100L, "testuser", 1L)

            val rawKey = result.rawKey
            assertTrue(rawKey.length >= 12, "rawKey 长度应至少为 12，以保证 substring(0, 12) 安全")
            assertTrue(rawKey.startsWith("hnx_sk_live_"), "rawKey 应以 hnx_sk_live_ 前缀开头")
            // 前缀恰好 12 字符，且前缀之后应有随机部分（32字节 Base64URL 无填充编码为 43 字符）
            assertEquals("hnx_sk_live_", rawKey.substring(0, 12))
            assertTrue(rawKey.length > 12, "rawKey 前缀之后应包含随机部分")
            // keyPrefix 格式：前12字符 + "..." + 末4字符
            assertEquals(rawKey.substring(0, 12) + "..." + rawKey.takeLast(4), result.keyPrefix)
        }
    }

    @Nested
    @DisplayName("获取永久Key原始值测试")
    inner class GetPermanentRawKeyTests {

        @Test
        @DisplayName("getPermanentRawKey - 成功解密返回原始Key")
        fun `getPermanentRawKey should return decrypted raw key`() {
            // Given
            `when`(apiKeyMapper.selectPermanentKeyByUserId(100L)).thenReturn(testPermanentKey)
            `when`(aesUtil.decrypt("encrypted_raw_key_base64")).thenReturn("hnx_sk_live_decrypted_key")

            // When
            val result = apiKeyService.getPermanentRawKey(100L)

            // Then
            assertEquals("hnx_sk_live_decrypted_key", result)
        }

        @Test
        @DisplayName("getPermanentRawKey - 用户不存在返回null")
        fun `getPermanentRawKey should return null when user has no key`() {
            `when`(apiKeyMapper.selectPermanentKeyByUserId(999L)).thenReturn(null)

            val result = apiKeyService.getPermanentRawKey(999L)

            assertNull(result)
        }

        @Test
        @DisplayName("getPermanentRawKey - Key被禁用抛出BizException")
        fun `getPermanentRawKey should throw BizException when key is disabled`() {
            val disabledKey = testPermanentKey.apply { enabled = 0 }
            `when`(apiKeyMapper.selectPermanentKeyByUserId(100L)).thenReturn(disabledKey)

            assertThrows<BizException> {
                apiKeyService.getPermanentRawKey(100L)
            }
        }

        @Test
        @DisplayName("getPermanentRawKey - rawKeyEncrypted为null返回null")
        fun `getPermanentRawKey should return null when encrypted field is null`() {
            val keyWithNullEncrypted = testPermanentKey.apply { rawKeyEncrypted = null }
            `when`(apiKeyMapper.selectPermanentKeyByUserId(100L)).thenReturn(keyWithNullEncrypted)

            val result = apiKeyService.getPermanentRawKey(100L)

            assertNull(result)
        }
    }

    @Nested
    @DisplayName("重置永久Key测试")
    inner class RegeneratePermanentKeyTests {

        @Test
        @DisplayName("regeneratePermanentKey - 成功重置并返回新Key")
        fun `regeneratePermanentKey should regenerate and return new key`() {
            // Given
            `when`(apiKeyMapper.selectPermanentKeyByUserId(100L)).thenReturn(testPermanentKey)
            `when`(aesUtil.encrypt(anyString())).thenReturn("new_encrypted_value")
            `when`(apiKeyMapper.updateById(any<ApiKeyEntity>())).thenReturn(1)

            // When
            val result = apiKeyService.regeneratePermanentKey(100L)

            // Then
            assertNotNull(result)
            assertTrue(result.rawKey.startsWith("hnx_sk_live_"))
            verify(apiKeyMapper, times(1)).updateById(
                argThat { entity ->
                    entity.rawKeyEncrypted == "new_encrypted_value"
                },
            )
        }

        @Test
        @DisplayName("regeneratePermanentKey - 用户不存在抛出异常")
        fun `regeneratePermanentKey should throw when user has no key`() {
            `when`(apiKeyMapper.selectPermanentKeyByUserId(999L)).thenReturn(null)

            assertThrows<RuntimeException> {
                apiKeyService.regeneratePermanentKey(999L)
            }
        }
    }

    @Nested
    @DisplayName("系统Key初始化测试")
    inner class InitSystemKeysTests {

        @Test
        @DisplayName("initSystemKeys - 首次启动创建channel-service系统Key")
        fun `initSystemKeys should create channel-service key when not exists`() {
            `when`(apiKeyMapper.selectSystemKeyByServiceName("channel-service")).thenReturn(null)
            `when`(aesUtil.encrypt(anyString())).thenReturn("encrypted_system_key")
            `when`(apiKeyMapper.insert(any<ApiKeyEntity>())).thenReturn(1)

            apiKeyService.initSystemKeys()

            verify(apiKeyMapper, times(1)).insert(
                argThat { entity ->
                    entity.keyType == "SYSTEM" &&
                        entity.serviceName == "channel-service" &&
                        entity.scopes == "chat" &&
                        entity.rateLimit == 600
                },
            )
        }

        @Test
        @DisplayName("initSystemKeys - 已存在系统Key时跳过")
        fun `initSystemKeys should skip when key already exists`() {
            // 两个系统服务各自判断，只 stub 一个的话另一个仍会建 Key
            `when`(apiKeyMapper.selectSystemKeyByServiceName("channel-service")).thenReturn(testSystemKey)
            `when`(apiKeyMapper.selectSystemKeyByServiceName("scheduler")).thenReturn(testSystemKey)

            apiKeyService.initSystemKeys()

            verify(apiKeyMapper, never()).insert(any<ApiKeyEntity>())
        }
    }

    @Nested
    @DisplayName("临时Key CRUD保护测试")
    inner class CrudProtectionTests {

        @Test
        @DisplayName("deleteApiKey - 永久Key不允许删除")
        fun `deleteApiKey should throw for PERMANENT key`() {
            `when`(apiKeyMapper.selectById(1L)).thenReturn(testPermanentKey)

            assertThrows<RuntimeException> {
                apiKeyService.deleteApiKey(1L)
            }
            verify(apiKeyMapper, never()).deleteById(anyLong())
        }

        @Test
        @DisplayName("deleteApiKey - 系统Key不允许删除")
        fun `deleteApiKey should throw for SYSTEM key`() {
            `when`(apiKeyMapper.selectById(3L)).thenReturn(testSystemKey)

            assertThrows<RuntimeException> {
                apiKeyService.deleteApiKey(3L)
            }
            verify(apiKeyMapper, never()).deleteById(anyLong())
        }

        @Test
        @DisplayName("deleteApiKey - 临时Key不存在保护类型异常")
        fun `deleteApiKey for TEMPORARY key should not throw protection error`() {
            `when`(apiKeyMapper.selectById(2L)).thenReturn(testTemporaryKey)
            // TEMPORARY key passes the keyType check, but will fail at checkAccess
            // (no SecurityContext in unit test). This verifies it doesn't throw the
            // "PERMANENT/SYSTEM cannot be deleted" error.
            try {
                apiKeyService.deleteApiKey(2L)
            } catch (e: RuntimeException) {
                // Should NOT be the protection error
                assertFalse(e.message?.contains("cannot be deleted") == true)
            }
        }

        @Test
        @DisplayName("updateApiKey - 永久Key不允许修改")
        fun `updateApiKey should throw for PERMANENT key`() {
            `when`(apiKeyMapper.selectById(1L)).thenReturn(testPermanentKey)

            assertThrows<RuntimeException> {
                apiKeyService.updateApiKey(1L, ApiKeyUpdateRequest())
            }
        }

        @Test
        @DisplayName("toggleEnabled - 永久Key不允许禁用")
        fun `toggleEnabled should throw for PERMANENT key`() {
            `when`(apiKeyMapper.selectById(1L)).thenReturn(testPermanentKey)

            assertThrows<RuntimeException> {
                apiKeyService.toggleEnabled(1L, 0)
            }
        }

        @Test
        @DisplayName("toggleEnabled - 系统Key不允许禁用")
        fun `toggleEnabled should throw for SYSTEM key`() {
            `when`(apiKeyMapper.selectById(3L)).thenReturn(testSystemKey)

            assertThrows<RuntimeException> {
                apiKeyService.toggleEnabled(3L, 0)
            }
        }
    }

    @Nested
    @DisplayName("列表查询测试")
    inner class PageQueryTests {

        @Test
        @DisplayName("page - 只查询临时Key")
        fun `page should only query TEMPORARY keys`() {
            `when`(
                apiKeyMapper.selectTemporaryKeys(
                    isNull(),
                    isNull(),
                    isNull(),
                    isNull(),
                ),
            ).thenReturn(listOf(testTemporaryKey))

            val result = apiKeyService.page(null, null, null, null, 1, 10)

            assertNotNull(result)
            verify(apiKeyMapper, times(1)).selectTemporaryKeys(isNull(), isNull(), isNull(), isNull())
            // 确保不调用旧的 selectApiKeyList
            verify(apiKeyMapper, never()).selectApiKeyList(isNull(), isNull(), isNull(), isNull())
        }
    }

    /**
     * 模拟 SecurityContext 的辅助方法
     * 注意: 由于 SecurityUtils.getCurrentUser() 依赖 Spring Security Context，
     * 在纯单元测试中无法完全模拟，因此用 LENIENT strictness 绕过
     */
}

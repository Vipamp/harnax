package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.ApiKeyCreateRequest
import com.agnetix.harnax.admin.dto.ApiKeyCreatedResponse
import com.agnetix.harnax.admin.dto.ApiKeyResponse
import com.agnetix.harnax.admin.dto.ApiKeyUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.security.SecurityUtils
import com.agnetix.harnax.admin.service.ApiKeyService
import com.agnetix.harnax.entity.ApiKeyEntity
import com.agnetix.harnax.entity.SysUser
import com.agnetix.harnax.mapper.ApiKeyMapper
import com.agnetix.harnax.mapper.SysUserMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.quality.Strictness
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

/**
 * ApiKeyController 单元测试
 * 直接实例化 Controller + Mock Service，断言 ResultVo
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiKeyControllerTest {

    @Mock
    private lateinit var apiKeyService: ApiKeyService

    @Mock
    private lateinit var apiKeyMapper: ApiKeyMapper

    @Mock
    private lateinit var sysUserMapper: SysUserMapper

    @InjectMocks
    private lateinit var controller: ApiKeyController

    private lateinit var adminUser: SysUser
    private lateinit var normalUser: SysUser
    private lateinit var testEntity: ApiKeyEntity
    private lateinit var testResponse: ApiKeyResponse

    @BeforeEach
    fun setUp() {
        // SecurityUtils 通过静态 instance 委托到 sysUserMapper
        SecurityUtils(sysUserMapper).init()

        adminUser = SysUser().apply {
            id = 1L
            username = "admin"
            isAdmin = 1
        }
        normalUser = SysUser().apply {
            id = 2L
            username = "zhangsan"
            isAdmin = 0
            tenantId = 10L
        }

        testEntity = ApiKeyEntity().apply {
            id = 1L
            name = "test_key"
            keyHash = "hash-abc"
            keyPrefix = "hnx_sk_live_...abcd"
            scopes = "api:chat"
            tenantId = 10L
            rateLimit = 60
            enabled = 1
        }

        testResponse = ApiKeyResponse(
            id = 1L,
            name = "test_key",
            keyPrefix = "hnx_sk_live_...abcd",
            scopes = "api:chat",
            tenantId = 10L,
            rateLimit = 60,
            enabled = 1,
        )
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
        TenantContext.clear()
    }

    private fun mockLoggedInUser(user: SysUser) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(user.username, null, emptyList())
        `when`(sysUserMapper.selectByUsername(user.username)).thenReturn(user)
    }

    @Nested
    @DisplayName("GET /api/admin/api-keys/page")
    inner class PageEndpoint {

        @Test
        @DisplayName("page - 管理员查询所有 Key")
        fun `page should query all keys when admin`() {
            mockLoggedInUser(adminUser)
            val page = Page<ApiKeyEntity>(total = 1L, pageNum = 1L, pageSize = 10L, records = listOf(testEntity))
            `when`(apiKeyService.page(null, null, null, null, 1, 10)).thenReturn(page)
            `when`(apiKeyService.convertToResponse(testEntity)).thenReturn(testResponse)

            val result = controller.page(1, 10, null, null)

            assertTrue(result.isSuccess())
            assertEquals(1L, result.data?.total)
            assertEquals("test_key", result.data?.records?.get(0)?.name)
            verify(apiKeyService).page(null, null, null, null, 1, 10)
        }

        @Test
        @DisplayName("page - 普通用户仅查询自己创建的 Key")
        fun `page should scope to creator and tenant for normal user`() {
            mockLoggedInUser(normalUser)
            val page = Page<ApiKeyEntity>(total = 0L, pageNum = 1L, pageSize = 10L, records = emptyList())
            `when`(apiKeyService.page(null, null, "zhangsan", 10L, 1, 10)).thenReturn(page)

            val result = controller.page(1, 10, null, null)

            assertTrue(result.isSuccess())
            verify(apiKeyService).page(null, null, "zhangsan", 10L, 1, 10)
        }

        @Test
        @DisplayName("page - 传递过滤条件")
        fun `page should pass filters correctly`() {
            mockLoggedInUser(adminUser)
            val page = Page<ApiKeyEntity>(total = 0L, pageNum = 1L, pageSize = 10L, records = emptyList())
            `when`(apiKeyService.page("test", 1, null, null, 1, 10)).thenReturn(page)

            val result = controller.page(1, 10, "test", 1)

            assertTrue(result.isSuccess())
            verify(apiKeyService).page("test", 1, null, null, 1, 10)
        }

        @Test
        @DisplayName("page - service 抛异常返回 error")
        fun `page should return error when service throws`() {
            mockLoggedInUser(adminUser)
            `when`(apiKeyService.page(null, null, null, null, 1, 10)).thenThrow(RuntimeException("DB error"))

            val result = controller.page(1, 10, null, null)

            assertFalse(result.isSuccess())
            assertEquals(500, result.code)
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/api-keys/{id}")
    inner class GetByIdEndpoint {

        @Test
        @DisplayName("get - 返回 Key 详情")
        fun `get should return key details`() {
            `when`(apiKeyService.getApiKey(1L)).thenReturn(testEntity)
            `when`(apiKeyService.convertToResponse(testEntity)).thenReturn(testResponse)

            val result = controller.get(1L)

            assertTrue(result.isSuccess())
            assertEquals("test_key", result.data?.name)
            assertEquals("hnx_sk_live_...abcd", result.data?.keyPrefix)
        }

        @Test
        @DisplayName("get - 不存在时 data 为 null")
        fun `get should return null data when not found`() {
            `when`(apiKeyService.getApiKey(999L)).thenReturn(null)

            val result = controller.get(999L)

            assertTrue(result.isSuccess())
            assertNull(result.data)
        }

        @Test
        @DisplayName("get - service 抛异常返回 error")
        fun `get should return error when service throws`() {
            `when`(apiKeyService.getApiKey(1L)).thenThrow(RuntimeException("DB error"))

            val result = controller.get(1L)

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("POST /api/admin/api-keys")
    inner class CreateEndpoint {

        @Test
        @DisplayName("create - 创建成功返回 rawKey")
        fun `create should return created key with raw key`() {
            val request = ApiKeyCreateRequest(name = "new_key", scopes = "api:chat")
            val created = ApiKeyCreatedResponse(
                id = 2L,
                name = "new_key",
                rawKey = "hnx_sk_live_raw_key_once",
                keyPrefix = "hnx_sk_live_...wxyz",
            )
            `when`(apiKeyService.createApiKey(any())).thenReturn(created)

            val result = controller.create(request)

            assertTrue(result.isSuccess())
            assertEquals("new_key", result.data?.name)
            assertEquals("hnx_sk_live_raw_key_once", result.data?.rawKey)
        }

        @Test
        @DisplayName("create - 名称重复时返回 error")
        fun `create should return error on duplicate name`() {
            val request = ApiKeyCreateRequest(name = "test_key", scopes = "api:chat")
            `when`(apiKeyService.createApiKey(any())).thenThrow(BizException("API Key name already exists"))

            val result = controller.create(request)

            assertFalse(result.isSuccess())
            assertEquals("API Key name already exists", result.message)
        }
    }

    @Nested
    @DisplayName("PUT /api/admin/api-keys/update/{id}")
    inner class UpdateEndpoint {

        @Test
        @DisplayName("update - 更新成功")
        fun `update should return success`() {
            val request = ApiKeyUpdateRequest(rateLimit = 300)
            `when`(apiKeyService.updateApiKey(any(), any())).thenReturn(true)

            val result = controller.update(1L, request)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("update - service 返回 false 时返回 error")
        fun `update should return error when service returns false`() {
            val request = ApiKeyUpdateRequest(rateLimit = 300)
            `when`(apiKeyService.updateApiKey(any(), any())).thenReturn(false)

            val result = controller.update(1L, request)

            assertFalse(result.isSuccess())
            assertEquals("Failed to update API Key", result.message)
        }

        @Test
        @DisplayName("update - Key 不存在时返回 error")
        fun `update should return error when not found`() {
            val request = ApiKeyUpdateRequest(rateLimit = 300)
            `when`(apiKeyService.updateApiKey(any(), any())).thenThrow(BizException("API Key not found"))

            val result = controller.update(999L, request)

            assertFalse(result.isSuccess())
            assertEquals("API Key not found", result.message)
        }
    }

    @Nested
    @DisplayName("PUT /api/admin/api-keys/toggle/{id}")
    inner class ToggleEndpoint {

        @Test
        @DisplayName("toggle - 切换状态成功")
        fun `toggle should return success`() {
            `when`(apiKeyService.toggleEnabled(1L, 0)).thenReturn(true)

            val result = controller.toggle(1L, 0)

            assertTrue(result.isSuccess())
            verify(apiKeyService).toggleEnabled(1L, 0)
        }

        @Test
        @DisplayName("toggle - service 返回 false 时返回 error")
        fun `toggle should return error when service returns false`() {
            `when`(apiKeyService.toggleEnabled(1L, 1)).thenReturn(false)

            val result = controller.toggle(1L, 1)

            assertFalse(result.isSuccess())
            assertEquals("Failed to toggle API Key", result.message)
        }

        @Test
        @DisplayName("toggle - service 抛异常返回 error")
        fun `toggle should return error when service throws`() {
            `when`(apiKeyService.toggleEnabled(999L, 1)).thenThrow(BizException("API Key not found"))

            val result = controller.toggle(999L, 1)

            assertFalse(result.isSuccess())
            assertEquals("API Key not found", result.message)
        }
    }

    @Nested
    @DisplayName("DELETE /api/admin/api-keys/{id}")
    inner class DeleteEndpoint {

        @Test
        @DisplayName("delete - 删除成功")
        fun `delete should return success`() {
            `when`(apiKeyService.deleteApiKey(1L)).thenReturn(true)

            val result = controller.delete(1L)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("delete - service 返回 false 时返回 error")
        fun `delete should return error when service returns false`() {
            `when`(apiKeyService.deleteApiKey(1L)).thenReturn(false)

            val result = controller.delete(1L)

            assertFalse(result.isSuccess())
            assertEquals("Failed to delete API Key", result.message)
        }

        @Test
        @DisplayName("delete - Key 不存在时返回 error")
        fun `delete should return error when not found`() {
            `when`(apiKeyService.deleteApiKey(999L)).thenThrow(BizException("API Key not found"))

            val result = controller.delete(999L)

            assertFalse(result.isSuccess())
            assertEquals("API Key not found", result.message)
        }
    }

    @Nested
    @DisplayName("POST /api/admin/api-keys/{id}/regenerate")
    inner class RegenerateEndpoint {

        @Test
        @DisplayName("regenerate - 重新生成成功")
        fun `regenerate should return new raw key`() {
            val regenerated = ApiKeyCreatedResponse(
                id = 1L,
                name = "test_key",
                rawKey = "hnx_sk_live_new_raw_key",
                keyPrefix = "hnx_sk_live_...efgh",
            )
            `when`(apiKeyService.regenerateApiKey(1L)).thenReturn(regenerated)

            val result = controller.regenerate(1L)

            assertTrue(result.isSuccess())
            assertEquals("hnx_sk_live_new_raw_key", result.data?.rawKey)
        }

        @Test
        @DisplayName("regenerate - Key 不存在时返回 error")
        fun `regenerate should return error when not found`() {
            `when`(apiKeyService.regenerateApiKey(999L)).thenThrow(BizException("API Key not found"))

            val result = controller.regenerate(999L)

            assertFalse(result.isSuccess())
            assertEquals("API Key not found", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/api-keys/my-permanent-key")
    inner class MyPermanentKeyEndpoint {

        @Test
        @DisplayName("getMyPermanentKey - 已登录时返回永久 Key")
        fun `getMyPermanentKey should return permanent key when logged in`() {
            mockLoggedInUser(normalUser)
            `when`(apiKeyMapper.selectPermanentKeyByUserId(2L)).thenReturn(testEntity)
            `when`(apiKeyService.convertToResponse(testEntity)).thenReturn(testResponse)

            val result = controller.getMyPermanentKey()

            assertTrue(result.isSuccess())
            assertEquals("test_key", result.data?.name)
        }

        @Test
        @DisplayName("getMyPermanentKey - 永久 Key 不存在时 data 为 null")
        fun `getMyPermanentKey should return null data when key not found`() {
            mockLoggedInUser(normalUser)
            `when`(apiKeyMapper.selectPermanentKeyByUserId(2L)).thenReturn(null)

            val result = controller.getMyPermanentKey()

            assertTrue(result.isSuccess())
            assertNull(result.data)
        }

        @Test
        @DisplayName("getMyPermanentKey - 未登录时返回 error")
        fun `getMyPermanentKey should return error when not authenticated`() {
            // 不设置 SecurityContext，getCurrentUser 返回 null
            val result = controller.getMyPermanentKey()

            assertFalse(result.isSuccess())
            assertEquals("Not authenticated", result.message)
        }
    }

    @Nested
    @DisplayName("POST /api/admin/api-keys/regenerate-permanent")
    inner class RegeneratePermanentEndpoint {

        @Test
        @DisplayName("regenerateMyPermanentKey - 重新生成成功")
        fun `regenerateMyPermanentKey should return new raw key`() {
            mockLoggedInUser(normalUser)
            val regenerated = ApiKeyCreatedResponse(
                id = 1L,
                name = "permanent_zhangsan",
                rawKey = "hnx_sk_live_new_permanent_key",
                keyPrefix = "hnx_sk_live_...ijkl",
            )
            `when`(apiKeyService.regeneratePermanentKey(2L)).thenReturn(regenerated)

            val result = controller.regenerateMyPermanentKey()

            assertTrue(result.isSuccess())
            assertEquals("hnx_sk_live_new_permanent_key", result.data?.rawKey)
        }

        @Test
        @DisplayName("regenerateMyPermanentKey - 未登录时返回 error")
        fun `regenerateMyPermanentKey should return error when not authenticated`() {
            val result = controller.regenerateMyPermanentKey()

            assertFalse(result.isSuccess())
            assertEquals("Not authenticated", result.message)
        }

        @Test
        @DisplayName("regenerateMyPermanentKey - service 抛异常返回 error")
        fun `regenerateMyPermanentKey should return error when service throws`() {
            mockLoggedInUser(normalUser)
            `when`(apiKeyService.regeneratePermanentKey(2L)).thenThrow(BizException("Permanent key not found"))

            val result = controller.regenerateMyPermanentKey()

            assertFalse(result.isSuccess())
            assertEquals("Permanent key not found", result.message)
        }
    }
}

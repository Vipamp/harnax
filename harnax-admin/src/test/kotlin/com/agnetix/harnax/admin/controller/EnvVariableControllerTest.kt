package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.EnvVariableCreateRequest
import com.agnetix.harnax.admin.dto.EnvVariableResponse
import com.agnetix.harnax.admin.dto.EnvVariableUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.EnvVariableService
import com.agnetix.harnax.entity.EnvVariable
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
import java.time.LocalDateTime

/**
 * EnvVariableController 单元测试
 * 直接实例化 Controller + Mock Service,直调方法断言 ResultVo
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnvVariableControllerTest {

    @Mock
    private lateinit var envVariableService: EnvVariableService

    @InjectMocks
    private lateinit var controller: EnvVariableController

    private lateinit var testEnv: EnvVariable
    private lateinit var testResponse: EnvVariableResponse

    @BeforeEach
    fun setUp() {
        testEnv = EnvVariable().apply {
            id = 1L
            tenantId = 1L
            envKey = "API_KEY"
            envValue = "sk-xxxx"
            description = "Test API key"
            sensitive = 1
            enabled = 1
            creator = "admin"
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }
        testResponse = EnvVariableResponse(
            id = 1L,
            envKey = "API_KEY",
            envValue = "sk-****",
            description = "Test API key",
            sensitive = 1,
            enabled = 1,
            creator = "admin",
        )
    }

    @Nested
    @DisplayName("GET /api/admin/env-variables/page")
    inner class PageEndpoint {

        @Test
        @DisplayName("page - 返回分页结果")
        fun `page should return paginated results`() {
            val page = Page(total = 1L, pageNum = 1L, pageSize = 10L, records = listOf(testEnv))
            `when`(envVariableService.page(null, 1, 10)).thenReturn(page)
            `when`(envVariableService.convertToResponse(testEnv)).thenReturn(testResponse)

            val result = controller.page(1, 10, null)

            assertTrue(result.isSuccess())
            assertEquals(1L, result.data?.total)
            assertEquals("API_KEY", result.data?.records?.get(0)?.envKey)
        }

        @Test
        @DisplayName("page - pageNum/pageSize 为 null 时使用默认值 1/10")
        fun `page should use default pagination when null`() {
            val page = Page<EnvVariable>(total = 0L, pageNum = 1L, pageSize = 10L, records = emptyList())
            `when`(envVariableService.page(null, 1, 10)).thenReturn(page)

            val result = controller.page(null, null, null)

            assertTrue(result.isSuccess())
            verify(envVariableService).page(null, 1, 10)
        }

        @Test
        @DisplayName("page - 透传 keyword 筛选")
        fun `page should pass keyword filter`() {
            val page = Page<EnvVariable>(total = 0L, pageNum = 1L, pageSize = 10L, records = emptyList())
            `when`(envVariableService.page("API", 2, 20)).thenReturn(page)

            val result = controller.page(2, 20, "API")

            assertTrue(result.isSuccess())
            verify(envVariableService).page("API", 2, 20)
        }

        @Test
        @DisplayName("page - service 抛异常时返回错误")
        fun `page should return error on service exception`() {
            `when`(envVariableService.page(null, 1, 10)).thenThrow(RuntimeException("DB error"))

            val result = controller.page(1, 10, null)

            assertFalse(result.isSuccess())
            assertEquals("Failed to get env variable list", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/env-variables/list")
    inner class ListForAgentConfigEndpoint {

        @Test
        @DisplayName("listForAgentConfig - 返回启用的变量列表")
        fun `listForAgentConfig should return enabled variables`() {
            val list = listOf<Map<String, Any?>>(
                mapOf("id" to 1L, "envKey" to "API_KEY", "envValue" to "sk-xxxx"),
                mapOf("id" to 2L, "envKey" to "TOKEN", "envValue" to "abc"),
            )
            `when`(envVariableService.listForAgentConfig()).thenReturn(list)

            val result = controller.listForAgentConfig()

            assertTrue(result.isSuccess())
            assertEquals(2, result.data?.size)
            assertEquals("API_KEY", result.data?.get(0)?.get("envKey"))
        }

        @Test
        @DisplayName("listForAgentConfig - 空列表")
        fun `listForAgentConfig should return empty list when no variables`() {
            `when`(envVariableService.listForAgentConfig()).thenReturn(emptyList())

            val result = controller.listForAgentConfig()

            assertTrue(result.isSuccess())
            assertTrue(result.data?.isEmpty() == true)
        }

        @Test
        @DisplayName("listForAgentConfig - service 抛异常时返回错误")
        fun `listForAgentConfig should return error on service exception`() {
            `when`(envVariableService.listForAgentConfig()).thenThrow(RuntimeException("DB error"))

            val result = controller.listForAgentConfig()

            assertFalse(result.isSuccess())
            assertEquals("Failed to list env variables for agent config", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/env-variables/{id}")
    inner class GetByIdEndpoint {

        @Test
        @DisplayName("getById - 存在时返回详情")
        fun `getById should return env variable when found`() {
            `when`(envVariableService.getEnvVariable(1L)).thenReturn(testEnv)
            `when`(envVariableService.convertToResponse(testEnv)).thenReturn(testResponse)

            val result = controller.getById(1L)

            assertTrue(result.isSuccess())
            assertNotNull(result.data)
            assertEquals("API_KEY", result.data?.envKey)
        }

        @Test
        @DisplayName("getById - 不存在时 data 为 null")
        fun `getById should return null data when not found`() {
            `when`(envVariableService.getEnvVariable(999L)).thenReturn(null)

            val result = controller.getById(999L)

            assertTrue(result.isSuccess())
            assertNull(result.data)
        }

        @Test
        @DisplayName("getById - service 抛异常时返回错误")
        fun `getById should return error on service exception`() {
            `when`(envVariableService.getEnvVariable(1L)).thenThrow(RuntimeException("DB error"))

            val result = controller.getById(1L)

            assertFalse(result.isSuccess())
            assertEquals("Failed to get env variable details", result.message)
        }
    }

    @Nested
    @DisplayName("POST /api/admin/env-variables")
    inner class CreateEndpoint {

        private val request = EnvVariableCreateRequest(
            envKey = "NEW_KEY",
            envValue = "new-value",
            description = "New env",
            sensitive = 0,
            enabled = 1,
        )

        @Test
        @DisplayName("create - 创建成功")
        fun `create should return success`() {
            `when`(envVariableService.createEnvVariable(any())).thenReturn(true)

            val result = controller.create(request)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("create - 创建失败时返回错误")
        fun `create should return error when service returns false`() {
            `when`(envVariableService.createEnvVariable(any())).thenReturn(false)

            val result = controller.create(request)

            assertFalse(result.isSuccess())
            assertEquals("Failed to create env variable", result.message)
        }

        @Test
        @DisplayName("create - service 抛异常时返回错误")
        fun `create should return error on service exception`() {
            `when`(envVariableService.createEnvVariable(any()))
                .thenThrow(BizException("Env key already exists"))

            val result = controller.create(request)

            assertFalse(result.isSuccess())
            assertEquals("Failed to create env variable", result.message)
        }
    }

    @Nested
    @DisplayName("PUT /api/admin/env-variables/update/{id}")
    inner class UpdateEndpoint {

        private val request = EnvVariableUpdateRequest(envValue = "updated-value")

        @Test
        @DisplayName("update - 更新成功")
        fun `update should return success`() {
            `when`(envVariableService.updateEnvVariable(any(), any())).thenReturn(true)

            val result = controller.update(1L, request)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("update - 更新失败时返回错误")
        fun `update should return error when service returns false`() {
            `when`(envVariableService.updateEnvVariable(any(), any())).thenReturn(false)

            val result = controller.update(999L, request)

            assertFalse(result.isSuccess())
            assertEquals("Failed to update env variable", result.message)
        }

        @Test
        @DisplayName("update - service 抛异常时返回错误")
        fun `update should return error on service exception`() {
            `when`(envVariableService.updateEnvVariable(any(), any()))
                .thenThrow(BizException("Env variable not found"))

            val result = controller.update(999L, request)

            assertFalse(result.isSuccess())
            assertEquals("Failed to update env variable", result.message)
        }
    }

    @Nested
    @DisplayName("DELETE /api/admin/env-variables/{id}")
    inner class DeleteEndpoint {

        @Test
        @DisplayName("delete - 删除成功")
        fun `delete should return success`() {
            `when`(envVariableService.deleteEnvVariable(1L)).thenReturn(true)

            val result = controller.delete(1L)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("delete - 删除失败时返回错误")
        fun `delete should return error when service returns false`() {
            `when`(envVariableService.deleteEnvVariable(999L)).thenReturn(false)

            val result = controller.delete(999L)

            assertFalse(result.isSuccess())
            assertEquals("Failed to delete env variable", result.message)
        }

        @Test
        @DisplayName("delete - service 抛异常时返回错误")
        fun `delete should return error on service exception`() {
            `when`(envVariableService.deleteEnvVariable(1L)).thenThrow(RuntimeException("DB error"))

            val result = controller.delete(1L)

            assertFalse(result.isSuccess())
            assertEquals("Failed to delete env variable", result.message)
        }
    }

    @Nested
    @DisplayName("PUT /api/admin/env-variables/{id}/toggle")
    inner class ToggleEndpoint {

        @Test
        @DisplayName("toggleEnabled - 切换成功")
        fun `toggleEnabled should return success`() {
            `when`(envVariableService.toggleEnabled(1L, 1)).thenReturn(true)

            val result = controller.toggleEnabled(1L, 1)

            assertTrue(result.isSuccess())
            verify(envVariableService).toggleEnabled(1L, 1)
        }

        @Test
        @DisplayName("toggleEnabled - 切换失败时返回错误")
        fun `toggleEnabled should return error when service returns false`() {
            `when`(envVariableService.toggleEnabled(1L, 0)).thenReturn(false)

            val result = controller.toggleEnabled(1L, 0)

            assertFalse(result.isSuccess())
            assertEquals("Failed to toggle env variable", result.message)
        }

        @Test
        @DisplayName("toggleEnabled - service 抛异常时返回错误")
        fun `toggleEnabled should return error on service exception`() {
            `when`(envVariableService.toggleEnabled(999L, 1)).thenThrow(RuntimeException("DB error"))

            val result = controller.toggleEnabled(999L, 1)

            assertFalse(result.isSuccess())
            assertEquals("Failed to toggle env variable", result.message)
        }
    }
}

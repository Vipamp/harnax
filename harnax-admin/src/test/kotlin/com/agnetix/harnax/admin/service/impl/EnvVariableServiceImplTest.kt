package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.EnvVariableCreateRequest
import com.agnetix.harnax.admin.dto.EnvVariableUpdateRequest
import com.agnetix.harnax.admin.util.AesUtil
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.entity.EnvVariable
import com.agnetix.harnax.mapper.EnvVariableMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.quality.Strictness
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.LocalDateTime

/**
 * EnvVariableServiceImpl Unit Tests
 * Uses Mockito to simulate Mapper and utility layer dependencies
 * Covers normal flows, exception flows, and boundary conditions
 *
 * @author agnetix
 * @since 2026-05-17
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnvVariableServiceImplTest {

    @Mock
    private lateinit var envVariableMapper: EnvVariableMapper

    @Mock
    private lateinit var jwtUtil: JwtUtil

    @Mock
    private lateinit var aesUtil: AesUtil

    private lateinit var testEnvVariable: EnvVariable

    @BeforeEach
    fun setUp() {
        testEnvVariable = EnvVariable().apply {
            id = 1L
            tenantId = 1L
            envKey = "API_KEY"
            envValue = "sk-plain-value"
            description = "Test env variable"
            sensitive = 0
            enabled = 1
            creator = "admin"
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }

        // Mock HttpServletRequest for UserContextUtil
        val mockRequest = MockHttpServletRequest()
        mockRequest.addHeader("Authorization", "Bearer mock-token")
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(mockRequest))

        // Mock JwtUtil
        `when`(jwtUtil.validateToken(anyString())).thenReturn(true)
        `when`(jwtUtil.getUsernameFromToken(anyString())).thenReturn("admin")
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
        RequestContextHolder.resetRequestAttributes()
    }

    private fun createService(): EnvVariableServiceImpl = EnvVariableServiceImpl(
        envVariableMapper = envVariableMapper,
        jwtUtil = jwtUtil,
        aesUtil = aesUtil,
    )

    @Nested
    @DisplayName("Page Query Tests")
    inner class PageQueryTests {

        @Test
        @DisplayName("page - Normal pagination query")
        fun `page should return paginated results`() {
            // Given
            `when`(envVariableMapper.selectEnvVariableList(null, "admin")).thenReturn(listOf(testEnvVariable))

            // When
            val page = createService().page(null, 1, 10)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 0)
            verify(envVariableMapper).selectEnvVariableList(null, "admin")
        }

        @Test
        @DisplayName("page - Filter by keyword")
        fun `page should filter by keyword`() {
            // Given
            `when`(envVariableMapper.selectEnvVariableList("API", "admin")).thenReturn(listOf(testEnvVariable))

            // When
            val page = createService().page("API", 1, 10)

            // Then
            assertNotNull(page)
            verify(envVariableMapper).selectEnvVariableList("API", "admin")
        }

        @Test
        @DisplayName("page - Bound invalid pageNum and pageSize")
        fun `page should bound invalid pageNum and pageSize`() {
            // Given - pageNum < 1 is coerced to 1 and pageSize is coerced into 1..1000
            `when`(envVariableMapper.selectEnvVariableList(null, "admin")).thenReturn(emptyList())

            // When
            val page = createService().page(null, 0, 10000)

            // Then
            assertNotNull(page)
            verify(envVariableMapper).selectEnvVariableList(null, "admin")
        }
    }

    @Nested
    @DisplayName("Get Env Variable Tests")
    inner class GetEnvVariableTests {

        @Test
        @DisplayName("getEnvVariable - Query by ID successfully")
        fun `getEnvVariable should return env variable by id`() {
            // Given
            `when`(envVariableMapper.selectById(1L)).thenReturn(testEnvVariable)

            // When
            val result = createService().getEnvVariable(1L)

            // Then
            assertNotNull(result)
            assertEquals("API_KEY", result?.envKey)
            verify(envVariableMapper).selectById(1L)
        }

        @Test
        @DisplayName("getEnvVariable - Return null when not exists")
        fun `getEnvVariable should return null when not exists`() {
            // Given
            `when`(envVariableMapper.selectById(999L)).thenReturn(null)

            // When
            val result = createService().getEnvVariable(999L)

            // Then
            assertNull(result)
            verify(envVariableMapper).selectById(999L)
        }
    }

    @Nested
    @DisplayName("Create Env Variable Tests")
    inner class CreateEnvVariableTests {

        @Test
        @DisplayName("createEnvVariable - Create non-sensitive variable successfully")
        fun `createEnvVariable should create non-sensitive variable successfully`() {
            // Given
            val request = EnvVariableCreateRequest(
                envKey = "NEW_KEY",
                envValue = "new-value",
                description = "New variable",
                sensitive = 0,
                enabled = 1,
            )

            `when`(envVariableMapper.insert(any())).thenReturn(1)

            // When
            val result = createService().createEnvVariable(request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<EnvVariable>()
            verify(envVariableMapper).insert(captor.capture())
            val saved = captor.firstValue
            assertEquals("NEW_KEY", saved.envKey)
            assertEquals("new-value", saved.envValue)
            assertEquals(0, saved.sensitive)
            assertEquals("admin", saved.creator)
            // Non-sensitive value is not encrypted
            verify(aesUtil, never()).encrypt(anyString())
        }

        @Test
        @DisplayName("createEnvVariable - Encrypt value when sensitive")
        fun `createEnvVariable should encrypt value when sensitive`() {
            // Given
            val request = EnvVariableCreateRequest(
                envKey = "SECRET_KEY",
                envValue = "raw-secret",
                sensitive = 1,
            )

            `when`(aesUtil.encrypt("raw-secret")).thenReturn("encrypted-secret")
            `when`(envVariableMapper.insert(any())).thenReturn(1)

            // When
            val result = createService().createEnvVariable(request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<EnvVariable>()
            verify(envVariableMapper).insert(captor.capture())
            assertEquals("encrypted-secret", captor.firstValue.envValue)
            verify(aesUtil).encrypt("raw-secret")
        }

        @Test
        @DisplayName("createEnvVariable - Set default sensitive and enabled when not provided")
        fun `createEnvVariable should set default sensitive and enabled when not provided`() {
            // Given
            val request = EnvVariableCreateRequest(
                envKey = "DEFAULT_KEY",
                envValue = "default-value",
            )

            `when`(envVariableMapper.insert(any())).thenReturn(1)

            // When
            val result = createService().createEnvVariable(request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<EnvVariable>()
            verify(envVariableMapper).insert(captor.capture())
            assertEquals(0, captor.firstValue.sensitive)
            assertEquals(1, captor.firstValue.enabled)
        }

        @Test
        @DisplayName("createEnvVariable - Set tenantId from TenantContext")
        fun `createEnvVariable should set tenantId from TenantContext`() {
            // Given
            TenantContext.setTenantId(7L)
            val request = EnvVariableCreateRequest(
                envKey = "TENANT_KEY",
                envValue = "tenant-value",
            )

            `when`(envVariableMapper.insert(any())).thenReturn(1)

            // When
            val result = createService().createEnvVariable(request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<EnvVariable>()
            verify(envVariableMapper).insert(captor.capture())
            assertEquals(7L, captor.firstValue.tenantId)
        }

        @Test
        @DisplayName("createEnvVariable - Throw RuntimeException when envKey missing")
        fun `createEnvVariable should throw RuntimeException when envKey missing`() {
            // Given - envKey is null, request.envKey!! throws NPE which is wrapped
            val request = EnvVariableCreateRequest(
                envKey = null,
                envValue = "value",
            )

            // When & Then
            val exception = assertThrows<RuntimeException> {
                createService().createEnvVariable(request)
            }
            assertEquals("Failed to create env variable", exception.message)
            verify(envVariableMapper, never()).insert(any())
        }

        @Test
        @DisplayName("createEnvVariable - Throw RuntimeException when insert fails")
        fun `createEnvVariable should throw RuntimeException when insert fails`() {
            // Given
            val request = EnvVariableCreateRequest(
                envKey = "FAIL_KEY",
                envValue = "fail-value",
            )

            `when`(envVariableMapper.insert(any())).thenThrow(RuntimeException("db error"))

            // When & Then
            val exception = assertThrows<RuntimeException> {
                createService().createEnvVariable(request)
            }
            assertEquals("Failed to create env variable", exception.message)
        }
    }

    @Nested
    @DisplayName("Update Env Variable Tests")
    inner class UpdateEnvVariableTests {

        @Test
        @DisplayName("updateEnvVariable - Update non-sensitive value successfully")
        fun `updateEnvVariable should update non-sensitive value successfully`() {
            // Given
            TenantContext.setTenantId(1L)
            val request = EnvVariableUpdateRequest(
                envKey = "API_KEY_V2",
                envValue = "updated-value",
                description = "Updated description",
            )

            `when`(envVariableMapper.selectById(1L)).thenReturn(testEnvVariable)
            `when`(envVariableMapper.updateById(any())).thenReturn(1)

            // When
            val result = createService().updateEnvVariable(1L, request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<EnvVariable>()
            verify(envVariableMapper).updateById(captor.capture())
            val updated = captor.firstValue
            assertEquals("API_KEY_V2", updated.envKey)
            assertEquals("updated-value", updated.envValue)
            assertEquals("Updated description", updated.description)
            verify(aesUtil, never()).encrypt(anyString())
        }

        @Test
        @DisplayName("updateEnvVariable - Encrypt new value when target sensitive is 1")
        fun `updateEnvVariable should encrypt new value when target sensitive is 1`() {
            // Given
            TenantContext.setTenantId(1L)
            val request = EnvVariableUpdateRequest(
                envValue = "new-secret",
                sensitive = 1,
            )

            `when`(envVariableMapper.selectById(1L)).thenReturn(testEnvVariable)
            `when`(aesUtil.encrypt("new-secret")).thenReturn("encrypted-new-secret")
            `when`(envVariableMapper.updateById(any())).thenReturn(1)

            // When
            val result = createService().updateEnvVariable(1L, request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<EnvVariable>()
            verify(envVariableMapper).updateById(captor.capture())
            assertEquals("encrypted-new-secret", captor.firstValue.envValue)
            assertEquals(1, captor.firstValue.sensitive)
            verify(aesUtil).encrypt("new-secret")
        }

        @Test
        @DisplayName("updateEnvVariable - Keep current value when envValue not provided")
        fun `updateEnvVariable should keep current value when envValue not provided`() {
            // Given
            TenantContext.setTenantId(1L)
            val request = EnvVariableUpdateRequest(
                description = "Only description updated",
            )

            `when`(envVariableMapper.selectById(1L)).thenReturn(testEnvVariable)
            `when`(envVariableMapper.updateById(any())).thenReturn(1)

            // When
            val result = createService().updateEnvVariable(1L, request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<EnvVariable>()
            verify(envVariableMapper).updateById(captor.capture())
            assertEquals("sk-plain-value", captor.firstValue.envValue)
        }

        @Test
        @DisplayName("updateEnvVariable - Throw RuntimeException when not found")
        fun `updateEnvVariable should throw RuntimeException when not found`() {
            // Given
            val request = EnvVariableUpdateRequest(description = "Update")

            `when`(envVariableMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<RuntimeException> {
                createService().updateEnvVariable(999L, request)
            }
            // Service wraps the exception
            assertEquals("Failed to update env variable", exception.message)
            verify(envVariableMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("updateEnvVariable - Throw RuntimeException when creator mismatch")
        fun `updateEnvVariable should throw RuntimeException when creator mismatch`() {
            // Given
            TenantContext.setTenantId(1L)
            val otherUserEnv = EnvVariable().apply {
                id = 1L
                tenantId = 1L
                envKey = "OTHER_KEY"
                envValue = "other-value"
                creator = "otheruser"
            }
            val request = EnvVariableUpdateRequest(description = "IDOR attempt")

            `when`(envVariableMapper.selectById(1L)).thenReturn(otherUserEnv)

            // When & Then
            val exception = assertThrows<RuntimeException> {
                createService().updateEnvVariable(1L, request)
            }
            assertEquals("Failed to update env variable", exception.message)
            verify(envVariableMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("updateEnvVariable - Throw RuntimeException when tenant mismatch")
        fun `updateEnvVariable should throw RuntimeException when tenant mismatch`() {
            // Given
            TenantContext.setTenantId(2L)
            val request = EnvVariableUpdateRequest(description = "Cross tenant attempt")

            `when`(envVariableMapper.selectById(1L)).thenReturn(testEnvVariable) // tenantId = 1L

            // When & Then
            val exception = assertThrows<RuntimeException> {
                createService().updateEnvVariable(1L, request)
            }
            assertEquals("Failed to update env variable", exception.message)
            verify(envVariableMapper, never()).updateById(any())
        }
    }

    @Nested
    @DisplayName("Delete Env Variable Tests")
    inner class DeleteEnvVariableTests {

        @Test
        @DisplayName("deleteEnvVariable - Delete env variable successfully")
        fun `deleteEnvVariable should delete env variable successfully`() {
            // Given
            TenantContext.setTenantId(1L)
            `when`(envVariableMapper.selectById(1L)).thenReturn(testEnvVariable)
            `when`(envVariableMapper.deleteById(1L)).thenReturn(1)

            // When
            val result = createService().deleteEnvVariable(1L)

            // Then
            assertTrue(result)
            verify(envVariableMapper).deleteById(1L)
        }

        @Test
        @DisplayName("deleteEnvVariable - Throw RuntimeException when not found")
        fun `deleteEnvVariable should throw RuntimeException when not found`() {
            // Given
            `when`(envVariableMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<RuntimeException> {
                createService().deleteEnvVariable(999L)
            }
            assertEquals("Env variable not found", exception.message)
            verify(envVariableMapper, never()).deleteById(anyLong())
        }

        @Test
        @DisplayName("deleteEnvVariable - Throw RuntimeException when creator mismatch")
        fun `deleteEnvVariable should throw RuntimeException when creator mismatch`() {
            // Given
            TenantContext.setTenantId(1L)
            val otherUserEnv = EnvVariable().apply {
                id = 1L
                tenantId = 1L
                creator = "otheruser"
            }

            `when`(envVariableMapper.selectById(1L)).thenReturn(otherUserEnv)

            // When & Then
            val exception = assertThrows<RuntimeException> {
                createService().deleteEnvVariable(1L)
            }
            assertEquals("No permission to delete this env variable", exception.message)
            verify(envVariableMapper, never()).deleteById(anyLong())
        }

        @Test
        @DisplayName("deleteEnvVariable - Return false when delete affects no rows")
        fun `deleteEnvVariable should return false when delete affects no rows`() {
            // Given
            TenantContext.setTenantId(1L)
            `when`(envVariableMapper.selectById(1L)).thenReturn(testEnvVariable)
            `when`(envVariableMapper.deleteById(1L)).thenReturn(0)

            // When
            val result = createService().deleteEnvVariable(1L)

            // Then
            assertFalse(result)
        }
    }

    @Nested
    @DisplayName("Toggle Enabled Tests")
    inner class ToggleEnabledTests {

        @Test
        @DisplayName("toggleEnabled - Disable env variable successfully")
        fun `toggleEnabled should disable env variable successfully`() {
            // Given
            TenantContext.setTenantId(1L)
            `when`(envVariableMapper.selectById(1L)).thenReturn(testEnvVariable)
            `when`(envVariableMapper.toggleEnabled(1L, 0)).thenReturn(1)

            // When
            val result = createService().toggleEnabled(1L, 0)

            // Then
            assertTrue(result)
            verify(envVariableMapper).toggleEnabled(1L, 0)
        }

        @Test
        @DisplayName("toggleEnabled - Enable env variable successfully")
        fun `toggleEnabled should enable env variable successfully`() {
            // Given
            TenantContext.setTenantId(1L)
            `when`(envVariableMapper.selectById(1L)).thenReturn(testEnvVariable)
            `when`(envVariableMapper.toggleEnabled(1L, 1)).thenReturn(1)

            // When
            val result = createService().toggleEnabled(1L, 1)

            // Then
            assertTrue(result)
            verify(envVariableMapper).toggleEnabled(1L, 1)
        }

        @Test
        @DisplayName("toggleEnabled - Throw RuntimeException when not found")
        fun `toggleEnabled should throw RuntimeException when not found`() {
            // Given
            `when`(envVariableMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<RuntimeException> {
                createService().toggleEnabled(999L, 1)
            }
            assertEquals("Env variable not found", exception.message)
            verify(envVariableMapper, never()).toggleEnabled(anyLong(), anyInt())
        }

        @Test
        @DisplayName("toggleEnabled - Throw RuntimeException when no permission")
        fun `toggleEnabled should throw RuntimeException when no permission`() {
            // Given
            TenantContext.setTenantId(1L)
            val otherUserEnv = EnvVariable().apply {
                id = 1L
                tenantId = 1L
                creator = "otheruser"
            }

            `when`(envVariableMapper.selectById(1L)).thenReturn(otherUserEnv)

            // When & Then
            val exception = assertThrows<RuntimeException> {
                createService().toggleEnabled(1L, 0)
            }
            assertEquals("No permission to modify this env variable", exception.message)
            verify(envVariableMapper, never()).toggleEnabled(anyLong(), anyInt())
        }
    }

    @Nested
    @DisplayName("Convert To Response Tests")
    inner class ConvertToResponseTests {

        @Test
        @DisplayName("convertToResponse - Return plain value for non-sensitive variable")
        fun `convertToResponse should return plain value for non-sensitive variable`() {
            // When
            val result = createService().convertToResponse(testEnvVariable)

            // Then
            assertNotNull(result)
            assertEquals(1L, result.id)
            assertEquals("API_KEY", result.envKey)
            assertEquals("sk-plain-value", result.envValue)
            assertEquals(0, result.sensitive)
        }

        @Test
        @DisplayName("convertToResponse - Mask decrypted value for sensitive variable")
        fun `convertToResponse should mask decrypted value for sensitive variable`() {
            // Given
            val sensitiveEnv = EnvVariable().apply {
                id = 2L
                envKey = "SECRET"
                envValue = "encrypted-payload"
                sensitive = 1
            }

            `when`(aesUtil.decrypt("encrypted-payload")).thenReturn("sk-1234567890abcdef")

            // When
            val result = createService().convertToResponse(sensitiveEnv)

            // Then
            assertNotNull(result)
            // Long value: first 3 chars + **** + last 2 chars
            assertEquals("sk-****ef", result.envValue)
            verify(aesUtil).decrypt("encrypted-payload")
        }

        @Test
        @DisplayName("convertToResponse - Return mask placeholder when decryption fails")
        fun `convertToResponse should return mask placeholder when decryption fails`() {
            // Given
            val sensitiveEnv = EnvVariable().apply {
                id = 2L
                envKey = "SECRET"
                envValue = "bad-payload"
                sensitive = 1
            }

            `when`(aesUtil.decrypt("bad-payload")).thenThrow(RuntimeException("decrypt error"))

            // When
            val result = createService().convertToResponse(sensitiveEnv)

            // Then
            assertNotNull(result)
            assertEquals("******", result.envValue)
        }

        @Test
        @DisplayName("convertToResponse - Fully mask short sensitive value")
        fun `convertToResponse should fully mask short sensitive value`() {
            // Given
            val sensitiveEnv = EnvVariable().apply {
                id = 3L
                envKey = "SHORT_SECRET"
                envValue = "encrypted-short"
                sensitive = 1
            }

            `when`(aesUtil.decrypt("encrypted-short")).thenReturn("abcd")

            // When
            val result = createService().convertToResponse(sensitiveEnv)

            // Then
            assertEquals("******", result.envValue)
        }
    }

    @Nested
    @DisplayName("List For Agent Config Tests")
    inner class ListForAgentConfigTests {

        @Test
        @DisplayName("listForAgentConfig - Return only enabled variables")
        fun `listForAgentConfig should return only enabled variables`() {
            // Given
            val disabledEnv = EnvVariable().apply {
                id = 2L
                envKey = "DISABLED_KEY"
                envValue = "disabled-value"
                enabled = 0
                creator = "admin"
            }

            `when`(envVariableMapper.selectEnvVariableList(null, "admin"))
                .thenReturn(listOf(testEnvVariable, disabledEnv))

            // When
            val result = createService().listForAgentConfig()

            // Then
            assertEquals(1, result.size)
            assertEquals("API_KEY", result[0]["envKey"])
            assertEquals("sk-plain-value", result[0]["displayValue"])
            assertEquals(false, result[0]["sensitive"])
        }

        @Test
        @DisplayName("listForAgentConfig - Mask sensitive variable value")
        fun `listForAgentConfig should mask sensitive variable value`() {
            // Given
            val sensitiveEnv = EnvVariable().apply {
                id = 2L
                envKey = "SECRET_KEY"
                envValue = "encrypted-payload"
                sensitive = 1
                enabled = 1
                creator = "admin"
            }

            `when`(envVariableMapper.selectEnvVariableList(null, "admin")).thenReturn(listOf(sensitiveEnv))
            `when`(aesUtil.decrypt("encrypted-payload")).thenReturn("sk-1234567890abcdef")

            // When
            val result = createService().listForAgentConfig()

            // Then
            assertEquals(1, result.size)
            assertEquals("sk-****ef", result[0]["displayValue"])
            assertEquals(true, result[0]["sensitive"])
        }

        @Test
        @DisplayName("listForAgentConfig - Return mask placeholder when decryption fails")
        fun `listForAgentConfig should return mask placeholder when decryption fails`() {
            // Given
            val sensitiveEnv = EnvVariable().apply {
                id = 2L
                envKey = "SECRET_KEY"
                envValue = "bad-payload"
                sensitive = 1
                enabled = 1
                creator = "admin"
            }

            `when`(envVariableMapper.selectEnvVariableList(null, "admin")).thenReturn(listOf(sensitiveEnv))
            `when`(aesUtil.decrypt("bad-payload")).thenThrow(RuntimeException("decrypt error"))

            // When
            val result = createService().listForAgentConfig()

            // Then
            assertEquals(1, result.size)
            assertEquals("******", result[0]["displayValue"])
        }

        @Test
        @DisplayName("listForAgentConfig - Return empty list when no variables")
        fun `listForAgentConfig should return empty list when no variables`() {
            // Given
            `when`(envVariableMapper.selectEnvVariableList(null, "admin")).thenReturn(emptyList())

            // When
            val result = createService().listForAgentConfig()

            // Then
            assertTrue(result.isEmpty())
        }
    }

    @Nested
    @DisplayName("Get Decrypted Value Tests")
    inner class GetDecryptedValueTests {

        @Test
        @DisplayName("getDecryptedValue - Return decrypted value for sensitive variable")
        fun `getDecryptedValue should return decrypted value for sensitive variable`() {
            // Given
            val sensitiveEnv = EnvVariable().apply {
                id = 2L
                envKey = "SECRET"
                envValue = "encrypted-payload"
                sensitive = 1
            }

            `when`(envVariableMapper.selectById(2L)).thenReturn(sensitiveEnv)
            `when`(aesUtil.decrypt("encrypted-payload")).thenReturn("real-secret")

            // When
            val result = createService().getDecryptedValue(2L)

            // Then
            assertEquals("real-secret", result)
            verify(aesUtil).decrypt("encrypted-payload")
        }

        @Test
        @DisplayName("getDecryptedValue - Return plain value for non-sensitive variable")
        fun `getDecryptedValue should return plain value for non-sensitive variable`() {
            // Given
            `when`(envVariableMapper.selectById(1L)).thenReturn(testEnvVariable)

            // When
            val result = createService().getDecryptedValue(1L)

            // Then
            assertEquals("sk-plain-value", result)
            verify(aesUtil, never()).decrypt(anyString())
        }

        @Test
        @DisplayName("getDecryptedValue - Return null when variable not found")
        fun `getDecryptedValue should return null when variable not found`() {
            // Given
            `when`(envVariableMapper.selectById(999L)).thenReturn(null)

            // When
            val result = createService().getDecryptedValue(999L)

            // Then
            assertNull(result)
        }

        @Test
        @DisplayName("getDecryptedValue - Return null when decryption fails")
        fun `getDecryptedValue should return null when decryption fails`() {
            // Given
            val sensitiveEnv = EnvVariable().apply {
                id = 2L
                envKey = "SECRET"
                envValue = "bad-payload"
                sensitive = 1
            }

            `when`(envVariableMapper.selectById(2L)).thenReturn(sensitiveEnv)
            `when`(aesUtil.decrypt("bad-payload")).thenThrow(RuntimeException("decrypt error"))

            // When
            val result = createService().getDecryptedValue(2L)

            // Then
            assertNull(result)
        }
    }
}

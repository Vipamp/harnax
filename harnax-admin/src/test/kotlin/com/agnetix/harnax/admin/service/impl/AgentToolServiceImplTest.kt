package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.AgentToolCreateRequest
import com.agnetix.harnax.admin.dto.AgentToolUpdateRequest
import com.agnetix.harnax.admin.dto.ToolEnvParamEntry
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.SecretFieldEncryptor
import com.agnetix.harnax.entity.AgentTool
import com.agnetix.harnax.entity.AgentToolEnvParam
import com.agnetix.harnax.mapper.AgentToolEnvParamMapper
import com.agnetix.harnax.mapper.AgentToolMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.*
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.Spy
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.quality.Strictness
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime

/**
 * AgentToolServiceImpl Unit Tests
 * Uses Mockito to simulate Mapper layer dependencies
 *
 * @author agnetix
 * @since 2026-05-16
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentToolServiceImplTest {

    @Mock
    private lateinit var agentToolMapper: AgentToolMapper

    @Mock
    private lateinit var agentToolEnvParamMapper: AgentToolEnvParamMapper

    @Mock
    private lateinit var jwtUtil: JwtUtil

    @Spy
    private var objectMapper: ObjectMapper = ObjectMapper()

    @Mock
    private lateinit var secretFieldEncryptor: SecretFieldEncryptor

    @InjectMocks
    private lateinit var agentToolService: AgentToolServiceImpl

    private lateinit var testAgentTool: AgentTool

    @BeforeEach
    fun setUp() {
        // Initialize test data
        testAgentTool = AgentTool().apply {
            id = 1L
            name = "time-tool-box"
            displayName = "时间工具"
            type = "BUILTIN"
            beanName = "time-tool-box"
            needConfirm = 0
            status = 1
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

        // Default mock: no env param entries
        `when`(agentToolEnvParamMapper.selectByToolId(anyLong())).thenReturn(emptyList())
    }

    @Nested
    @DisplayName("Page Query Tests")
    inner class PageQueryTests {

        @Test
        @DisplayName("page - Normal pagination query")
        fun `page should return paginated results`() {
            // Given
            val agentTools = listOf(testAgentTool)
            `when`(agentToolMapper.selectAgentToolList(null, null, null, "admin")).thenReturn(agentTools)

            // When
            val page = agentToolService.page(null, null, null, 1, 10)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 0)
            verify(agentToolMapper).selectAgentToolList(null, null, null, "admin")
        }

        @Test
        @DisplayName("page - Filter by keyword")
        fun `page should filter by keyword`() {
            // Given
            val filteredTools = listOf(testAgentTool)
            `when`(agentToolMapper.selectAgentToolList("time", null, null, "admin")).thenReturn(filteredTools)

            // When
            val page = agentToolService.page("time", null, null, 1, 10)

            // Then
            assertNotNull(page)
            verify(agentToolMapper).selectAgentToolList("time", null, null, "admin")
        }

        @Test
        @DisplayName("page - Filter by type")
        fun `page should filter by type`() {
            // Given
            val filteredTools = listOf(testAgentTool)
            `when`(agentToolMapper.selectAgentToolList(null, null, "BUILTIN", "admin")).thenReturn(filteredTools)

            // When
            val page = agentToolService.page(null, null, "BUILTIN", 1, 10)

            // Then
            assertNotNull(page)
            verify(agentToolMapper).selectAgentToolList(null, null, "BUILTIN", "admin")
        }
    }

    @Nested
    @DisplayName("Get Agent Tool Tests")
    inner class GetAgentToolTests {

        @Test
        @DisplayName("getAgentTool - Query by ID successfully")
        fun `getAgentTool should return tool by id`() {
            // Given
            `when`(agentToolMapper.selectById(1L)).thenReturn(testAgentTool)

            // When
            val result = agentToolService.getAgentTool(1L)

            // Then
            assertNotNull(result)
            assertEquals("time-tool-box", result?.name)
            verify(agentToolMapper).selectById(1L)
        }

        @Test
        @DisplayName("getAgentTool - Return null when not exists")
        fun `getAgentTool should return null when not exists`() {
            // Given
            `when`(agentToolMapper.selectById(999L)).thenReturn(null)

            // When
            val result = agentToolService.getAgentTool(999L)

            // Then
            assertNull(result)
            verify(agentToolMapper).selectById(999L)
        }
    }

    @Nested
    @DisplayName("Create Agent Tool Tests")
    inner class CreateAgentToolTests {

        @Test
        @DisplayName("createAgentTool - Create BUILTIN type successfully")
        fun `createAgentTool should create BUILTIN type successfully`() {
            // Given
            val request = AgentToolCreateRequest(
                name = "time-tool-box",
                displayName = "时间工具",
                type = "BUILTIN",
                beanName = "time-tool-box",
            )

            `when`(agentToolMapper.insert(any())).thenReturn(1)

            // When
            val result = agentToolService.createAgentTool(request)

            // Then
            assertTrue(result)
            verify(agentToolMapper).insert(any())
        }

        @Test
        @DisplayName("createAgentTool - Create HTTP type successfully")
        fun `createAgentTool should create HTTP type successfully`() {
            // Given
            val request = AgentToolCreateRequest(
                name = "http-tool",
                displayName = "HTTP工具",
                type = "HTTP",
                httpUrl = "http://localhost:8080/api/tool",
                inputSchema = """{"type":"object","properties":{"query":{"type":"string"}}}""",
            )

            `when`(agentToolMapper.insert(any())).thenReturn(1)

            // When
            val result = agentToolService.createAgentTool(request)

            // Then
            assertTrue(result)
            verify(agentToolMapper).insert(any())
        }

        @Test
        @DisplayName("createAgentTool - Create with needConfirm=true")
        fun `createAgentTool should create with needConfirm=true`() {
            // Given
            val request = AgentToolCreateRequest(
                name = "dangerous-tool",
                displayName = "危险工具",
                type = "BUILTIN",
                beanName = "dangerous-tool",
                needConfirm = true,
            )

            `when`(agentToolMapper.insert(any())).thenReturn(1)

            // When
            val result = agentToolService.createAgentTool(request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<AgentTool>()
            verify(agentToolMapper).insert(captor.capture())
            assertEquals(1, captor.firstValue.needConfirm)
        }

        @Test
        @DisplayName("createAgentTool - Create with env param entries calls batchInsert")
        fun `createAgentTool should save env param entries`() {
            // Given
            val envEntries = listOf(
                ToolEnvParamEntry(envParamName = "API_KEY", required = true, secret = false, defaultValue = "key-123"),
                ToolEnvParamEntry(envParamName = "SECRET", required = false, secret = true, defaultValue = "sk-xxx"),
            )
            val request = AgentToolCreateRequest(
                name = "env-tool",
                type = "BUILTIN",
                beanName = "env-tool",
                envParams = envEntries,
            )

            `when`(agentToolMapper.insert(any())).thenReturn(1)
            `when`(agentToolEnvParamMapper.batchInsert(any())).thenReturn(2)
            `when`(secretFieldEncryptor.encrypt("sk-xxx")).thenReturn("encrypted-sk-xxx")

            // When
            val result = agentToolService.createAgentTool(request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<List<AgentToolEnvParam>>()
            verify(agentToolEnvParamMapper).batchInsert(captor.capture())
            val insertedEnvs = captor.firstValue
            assertEquals(2, insertedEnvs.size)
            assertEquals("API_KEY", insertedEnvs[0].envParamName)
            assertEquals(1, insertedEnvs[0].required) // required=true → 1
            assertEquals(0, insertedEnvs[0].secret)
            assertEquals("key-123", insertedEnvs[0].defaultValue)
            assertEquals("SECRET", insertedEnvs[1].envParamName)
            assertEquals(1, insertedEnvs[1].secret) // secret=true → 1
            assertEquals("encrypted-sk-xxx", insertedEnvs[1].defaultValue) // encrypted
        }

        @Test
        @DisplayName("createAgentTool - Create without env param entries skips batchInsert")
        fun `createAgentTool should skip env param save when no envParams`() {
            // Given
            val request = AgentToolCreateRequest(
                name = "no-env-tool",
                type = "BUILTIN",
                beanName = "no-env-tool",
            )

            `when`(agentToolMapper.insert(any())).thenReturn(1)

            // When
            val result = agentToolService.createAgentTool(request)

            // Then
            assertTrue(result)
            verify(agentToolEnvParamMapper, never()).batchInsert(any())
        }

        @Test
        @DisplayName("createAgentTool - Secret env param encrypts defaultValue")
        fun `createAgentTool should encrypt secret env param defaultValue`() {
            // Given
            val envEntries = listOf(
                ToolEnvParamEntry(envParamName = "TOKEN", required = true, secret = true, defaultValue = "my-secret-token"),
            )
            val request = AgentToolCreateRequest(
                name = "secret-tool",
                type = "BUILTIN",
                envParams = envEntries,
            )

            `when`(agentToolMapper.insert(any())).thenReturn(1)
            `when`(agentToolEnvParamMapper.batchInsert(any())).thenReturn(1)
            `when`(secretFieldEncryptor.encrypt("my-secret-token")).thenReturn("enc-my-secret-token")

            // When
            agentToolService.createAgentTool(request)

            // Then
            verify(secretFieldEncryptor).encrypt("my-secret-token")
            val captor = argumentCaptor<List<AgentToolEnvParam>>()
            verify(agentToolEnvParamMapper).batchInsert(captor.capture())
            assertEquals("enc-my-secret-token", captor.firstValue[0].defaultValue)
        }

        @Test
        @DisplayName("createAgentTool - Non-secret env param keeps defaultValue as plain text")
        fun `createAgentTool should not encrypt non-secret env param defaultValue`() {
            // Given
            val envEntries = listOf(
                ToolEnvParamEntry(envParamName = "HOST", required = false, secret = false, defaultValue = "localhost"),
            )
            val request = AgentToolCreateRequest(
                name = "plain-tool",
                type = "BUILTIN",
                envParams = envEntries,
            )

            `when`(agentToolMapper.insert(any())).thenReturn(1)
            `when`(agentToolEnvParamMapper.batchInsert(any())).thenReturn(1)

            // When
            agentToolService.createAgentTool(request)

            // Then
            verify(secretFieldEncryptor, never()).encrypt(anyString())
            val captor = argumentCaptor<List<AgentToolEnvParam>>()
            verify(agentToolEnvParamMapper).batchInsert(captor.capture())
            assertEquals("localhost", captor.firstValue[0].defaultValue)
        }
    }

    @Nested
    @DisplayName("Update Agent Tool Tests")
    inner class UpdateAgentToolTests {

        @Test
        @DisplayName("updateAgentTool - Update partial fields successfully")
        fun `updateAgentTool should update partial fields successfully`() {
            // Given
            val request = AgentToolUpdateRequest(
                displayName = "更新后的工具",
                description = "Updated description",
            )

            `when`(agentToolMapper.selectById(1L)).thenReturn(testAgentTool)
            `when`(agentToolMapper.updateById(any())).thenReturn(1)

            // When
            val result = agentToolService.updateAgentTool(1L, request)

            // Then
            assertTrue(result)
            verify(agentToolMapper).selectById(1L)
            verify(agentToolMapper).updateById(any())
        }

        @Test
        @DisplayName("updateAgentTool - Throw RuntimeException when tool not found")
        fun `updateAgentTool should throw RuntimeException when tool not found`() {
            // Given
            val request = AgentToolUpdateRequest(name = "Test")

            `when`(agentToolMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<RuntimeException> {
                agentToolService.updateAgentTool(999L, request)
            }
            assertTrue(exception.message!!.contains("Agent tool not found"))
            verify(agentToolMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("updateAgentTool - Update with envParams replaces env param entries")
        fun `updateAgentTool should replace env param entries when envParams provided`() {
            // Given
            val envEntries = listOf(
                ToolEnvParamEntry(envParamName = "NEW_KEY", required = false, secret = false, defaultValue = "new-value"),
            )
            val request = AgentToolUpdateRequest(envParams = envEntries)

            `when`(agentToolMapper.selectById(1L)).thenReturn(testAgentTool)
            `when`(agentToolMapper.updateById(any())).thenReturn(1)
            `when`(agentToolEnvParamMapper.deleteByToolId(1L)).thenReturn(1)
            `when`(agentToolEnvParamMapper.batchInsert(any())).thenReturn(1)

            // When
            val result = agentToolService.updateAgentTool(1L, request)

            // Then
            assertTrue(result)
            verify(agentToolEnvParamMapper).deleteByToolId(1L)
            verify(agentToolEnvParamMapper).batchInsert(any())
        }

        @Test
        @DisplayName("updateAgentTool - Update with empty envParams clears all env param entries")
        fun `updateAgentTool should clear envParams when empty list provided`() {
            // Given
            val request = AgentToolUpdateRequest(envParams = emptyList())

            `when`(agentToolMapper.selectById(1L)).thenReturn(testAgentTool)
            `when`(agentToolMapper.updateById(any())).thenReturn(1)
            `when`(agentToolEnvParamMapper.deleteByToolId(1L)).thenReturn(2)

            // When
            val result = agentToolService.updateAgentTool(1L, request)

            // Then
            assertTrue(result)
            verify(agentToolEnvParamMapper).deleteByToolId(1L)
            verify(agentToolEnvParamMapper, never()).batchInsert(any())
        }

        @Test
        @DisplayName("updateAgentTool - Update without envParams field preserves existing env params")
        fun `updateAgentTool should not touch envParams when envParams is null`() {
            // Given
            val request = AgentToolUpdateRequest(displayName = "New Name")

            `when`(agentToolMapper.selectById(1L)).thenReturn(testAgentTool)
            `when`(agentToolMapper.updateById(any())).thenReturn(1)

            // When
            val result = agentToolService.updateAgentTool(1L, request)

            // Then
            assertTrue(result)
            verify(agentToolEnvParamMapper, never()).deleteByToolId(anyLong())
            verify(agentToolEnvParamMapper, never()).batchInsert(any())
        }
    }

    @Nested
    @DisplayName("Toggle Status Tests")
    inner class ToggleStatusTests {

        @Test
        @DisplayName("toggleAgentToolStatus - Disable tool successfully")
        fun `toggleAgentToolStatus should disable tool successfully`() {
            // Given
            `when`(agentToolMapper.selectById(1L)).thenReturn(testAgentTool)
            `when`(agentToolMapper.updateStatus(1L, 0)).thenReturn(1)

            // When
            val result = agentToolService.toggleAgentToolStatus(1L, 0)

            // Then
            assertTrue(result)
            verify(agentToolMapper).selectById(1L)
            verify(agentToolMapper).updateStatus(1L, 0)
        }

        @Test
        @DisplayName("toggleAgentToolStatus - Enable tool successfully")
        fun `toggleAgentToolStatus should enable tool successfully`() {
            // Given
            `when`(agentToolMapper.selectById(1L)).thenReturn(testAgentTool)
            `when`(agentToolMapper.updateStatus(1L, 1)).thenReturn(1)

            // When
            val result = agentToolService.toggleAgentToolStatus(1L, 1)

            // Then
            assertTrue(result)
            verify(agentToolMapper).updateStatus(1L, 1)
        }

        @Test
        @DisplayName("toggleAgentToolStatus - Throw RuntimeException when tool not found")
        fun `toggleAgentToolStatus should throw when tool not found`() {
            // Given
            `when`(agentToolMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<RuntimeException> {
                agentToolService.toggleAgentToolStatus(999L, 0)
            }
            assertTrue(exception.message!!.contains("Agent tool not found"))
            verify(agentToolMapper, never()).updateStatus(anyLong(), anyInt())
        }
    }

    @Nested
    @DisplayName("Delete Agent Tool Tests")
    inner class DeleteAgentToolTests {

        @Test
        @DisplayName("deleteAgentTool - Logically delete and cascade delete env params")
        fun `deleteAgentTool should delete tool and cascade env param entries`() {
            // Given
            `when`(agentToolEnvParamMapper.deleteByToolId(1L)).thenReturn(2)
            `when`(agentToolMapper.deleteById(1L)).thenReturn(1)

            // When
            val result = agentToolService.deleteAgentTool(1L)

            // Then
            assertTrue(result)
            verify(agentToolEnvParamMapper).deleteByToolId(1L)
            verify(agentToolMapper).deleteById(1L)
        }

        @Test
        @DisplayName("deleteAgentTool - Return false when not found")
        fun `deleteAgentTool should return false when not found`() {
            // Given
            `when`(agentToolEnvParamMapper.deleteByToolId(999L)).thenReturn(0)
            `when`(agentToolMapper.deleteById(999L)).thenReturn(0)

            // When
            val result = agentToolService.deleteAgentTool(999L)

            // Then
            assertFalse(result)
            verify(agentToolEnvParamMapper).deleteByToolId(999L)
            verify(agentToolMapper).deleteById(999L)
        }
    }

    @Nested
    @DisplayName("Convert To Response Tests")
    inner class ConvertToResponseTests {

        @Test
        @DisplayName("convertToResponse - Convert entity to response with env param entries")
        fun `convertToResponse should include env param entries`() {
            // Given
            val envEntities = listOf(
                AgentToolEnvParam().apply {
                    id = 10L
                    toolId = 1L
                    envParamName = "API_KEY"
                    required = 1
                    secret = 0
                    defaultValue = "key-123"
                    createTime = LocalDateTime.now()
                    updateTime = LocalDateTime.now()
                },
                AgentToolEnvParam().apply {
                    id = 11L
                    toolId = 1L
                    envParamName = "SECRET_TOKEN"
                    required = 0
                    secret = 1
                    defaultValue = "encrypted-value"
                    createTime = LocalDateTime.now()
                    updateTime = LocalDateTime.now()
                },
            )
            `when`(agentToolEnvParamMapper.selectByToolId(1L)).thenReturn(envEntities)
            `when`(secretFieldEncryptor.decrypt("encrypted-value")).thenReturn("my-secret-token-value")

            // When
            val result = agentToolService.convertToResponse(testAgentTool)

            // Then
            assertNotNull(result)
            assertEquals(testAgentTool.id, result.id)
            assertEquals(testAgentTool.name, result.name)
            assertEquals(testAgentTool.type, result.type)
            assertNotNull(result.envParams)
            assertEquals(2, result.envParams!!.size)
            // Non-secret env param: plain value
            assertEquals("API_KEY", result.envParams!![0].envParamName)
            assertTrue(result.envParams!![0].required)
            assertFalse(result.envParams!![0].secret)
            assertEquals("key-123", result.envParams!![0].defaultValue)
            // Secret env param: masked value
            assertEquals("SECRET_TOKEN", result.envParams!![1].envParamName)
            assertTrue(result.envParams!![1].secret)
            assertEquals("my-****alue", result.envParams!![1].defaultValue) // masked: first 3 + **** + last 4
        }

        @Test
        @DisplayName("convertToResponse - Empty env param entries when no env params configured")
        fun `convertToResponse should return empty envParams when none configured`() {
            // Given
            `when`(agentToolEnvParamMapper.selectByToolId(1L)).thenReturn(emptyList())

            // When
            val result = agentToolService.convertToResponse(testAgentTool)

            // Then
            assertNotNull(result)
            assertTrue(result.envParams.isNullOrEmpty())
        }

        @Test
        @DisplayName("convertToResponse - Secret env param with short value returns full mask")
        fun `convertToResponse should fully mask short secret values`() {
            // Given
            val envEntities = listOf(
                AgentToolEnvParam().apply {
                    id = 20L
                    toolId = 1L
                    envParamName = "SHORT"
                    required = 0
                    secret = 1
                    defaultValue = "enc-short"
                    createTime = LocalDateTime.now()
                    updateTime = LocalDateTime.now()
                },
            )
            `when`(agentToolEnvParamMapper.selectByToolId(1L)).thenReturn(envEntities)
            `when`(secretFieldEncryptor.decrypt("enc-short")).thenReturn("abc") // ≤7 chars

            // When
            val result = agentToolService.convertToResponse(testAgentTool)

            // Then
            assertEquals("******", result.envParams!![0].defaultValue)
        }
    }

    @Nested
    @DisplayName("Get Available Tools Tests")
    inner class GetAvailableToolsTests {

        @Test
        @DisplayName("getAvailableTools - Return all enabled tools")
        fun `getAvailableTools should return all enabled tools`() {
            // Given
            val enabledTools = listOf(testAgentTool)
            `when`(agentToolMapper.selectAllEnabled()).thenReturn(enabledTools)

            // When
            val result = agentToolService.getAvailableTools()

            // Then
            assertNotNull(result)
            assertEquals(1, result.size)
            assertEquals("time-tool-box", result[0].name)
            verify(agentToolMapper).selectAllEnabled()
        }
    }

    @Nested
    @DisplayName("Get Builtin Tools Tests")
    inner class GetBuiltinToolsTests {

        @Test
        @DisplayName("getBuiltinTools - Return builtin tools only")
        fun `getBuiltinTools should return builtin tools`() {
            // Given
            `when`(agentToolMapper.selectBuiltinToolList()).thenReturn(listOf(testAgentTool))

            // When
            val result = agentToolService.getBuiltinTools()

            // Then
            assertEquals(1, result.size)
            assertEquals("BUILTIN", result[0].type)
        }
    }

    @Nested
    @DisplayName("Get Required Env Param Keys Tests")
    inner class GetRequiredEnvParamKeysTests {

        @Test
        @DisplayName("getRequiredEnvParamKeys - Parse JSON array correctly")
        fun `getRequiredEnvParamKeys should parse requiredEnvParamKeys JSON`() {
            // Given
            testAgentTool.requiredEnvParamKeys = """["API_KEY","SECRET"]"""
            `when`(agentToolMapper.selectById(1L)).thenReturn(testAgentTool)
            // objectMapper is @Spy so real JSON parsing works

            // When
            val result = agentToolService.getRequiredEnvParamKeys(1L)

            // Then
            assertEquals(2, result.size)
            assertEquals("API_KEY", result[0])
        }

        @Test
        @DisplayName("getRequiredEnvParamKeys - Return empty when null")
        fun `getRequiredEnvParamKeys should return empty when no keys`() {
            // Given
            testAgentTool.requiredEnvParamKeys = null
            `when`(agentToolMapper.selectById(1L)).thenReturn(testAgentTool)

            // When
            val result = agentToolService.getRequiredEnvParamKeys(1L)

            // Then
            assertTrue(result.isEmpty())
        }

        @Test
        @DisplayName("getRequiredEnvParamKeys - Throw when tool not found")
        fun `getRequiredEnvParamKeys should throw when tool not found`() {
            // Given
            `when`(agentToolMapper.selectById(999L)).thenReturn(null)

            // When & Then
            assertThrows<RuntimeException> {
                agentToolService.getRequiredEnvParamKeys(999L)
            }
        }
    }
}

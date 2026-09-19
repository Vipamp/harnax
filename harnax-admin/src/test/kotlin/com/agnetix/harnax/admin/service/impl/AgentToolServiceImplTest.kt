package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.util.SecretFieldEncryptor
import com.agnetix.harnax.entity.AgentTool
import com.agnetix.harnax.entity.AgentToolEnvParam
import com.agnetix.harnax.mapper.AgentToolEnvParamMapper
import com.agnetix.harnax.mapper.AgentToolMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.Spy
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
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

    @Spy
    private var objectMapper: ObjectMapper = ObjectMapper()

    @Mock
    private lateinit var secretFieldEncryptor: SecretFieldEncryptor

    @InjectMocks
    private lateinit var agentToolService: AgentToolServiceImpl

    private lateinit var testAgentTool: AgentTool

    @BeforeEach
    fun setUp() {
        testAgentTool = AgentTool().apply {
            id = 1L
            name = "getDate"
            displayName = "时间工具"
            beanName = "time-tool-box"
            methodName = "getDate"
            needConfirm = 0
            status = 1
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }

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
            `when`(agentToolMapper.selectAgentToolList(null, null)).thenReturn(listOf(testAgentTool))

            // When
            val page = agentToolService.page(null, null, 1, 10)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 0)
            verify(agentToolMapper).selectAgentToolList(null, null)
        }

        @Test
        @DisplayName("page - Filter by keyword")
        fun `page should filter by keyword`() {
            // Given
            `when`(agentToolMapper.selectAgentToolList("time", null)).thenReturn(listOf(testAgentTool))

            // When
            val page = agentToolService.page("time", null, 1, 10)

            // Then
            assertNotNull(page)
            verify(agentToolMapper).selectAgentToolList("time", null)
        }

        @Test
        @DisplayName("page - Filter by status")
        fun `page should filter by status`() {
            // Given
            `when`(agentToolMapper.selectAgentToolList(null, 0)).thenReturn(emptyList())

            // When
            val page = agentToolService.page(null, 0, 1, 10)

            // Then
            assertNotNull(page)
            verify(agentToolMapper).selectAgentToolList(null, 0)
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
            assertEquals("getDate", result?.name)
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

        @Test
        @DisplayName("convertToResponse - Undecryptable secret never leaks")
        fun `convertToResponse should mask when decryption fails`() {
            // Given
            val envEntities = listOf(
                AgentToolEnvParam().apply {
                    id = 30L
                    toolId = 1L
                    envParamName = "BROKEN"
                    required = 0
                    secret = 1
                    defaultValue = "not-a-ciphertext"
                    createTime = LocalDateTime.now()
                    updateTime = LocalDateTime.now()
                },
            )
            `when`(agentToolEnvParamMapper.selectByToolId(1L)).thenReturn(envEntities)
            `when`(secretFieldEncryptor.decrypt("not-a-ciphertext")).thenThrow(RuntimeException("bad ciphertext"))

            // When
            val result = agentToolService.convertToResponse(testAgentTool)

            // Then
            assertEquals("******", result.envParams!![0].defaultValue)
        }

        @Test
        @DisplayName("convertToResponse - Parse requiredEnvParamKeys into a list")
        fun `convertToResponse should parse requiredEnvParamKeys`() {
            // Given
            testAgentTool.requiredEnvParamKeys = """["API_KEY"]"""

            // When
            val result = agentToolService.convertToResponse(testAgentTool)

            // Then
            assertEquals(listOf("API_KEY"), result.requiredEnvParamKeys)
        }
    }

    @Nested
    @DisplayName("Get Available Tools Tests")
    inner class GetAvailableToolsTests {

        @Test
        @DisplayName("getAvailableTools - Delegate to the non-mandatory query")
        fun `getAvailableTools should return all enabled tools`() {
            // Given
            `when`(agentToolMapper.selectAvailableTools()).thenReturn(listOf(testAgentTool))

            // When
            val result = agentToolService.getAvailableTools()

            // Then
            assertEquals(1, result.size)
            assertEquals("getDate", result[0].name)
            verify(agentToolMapper).selectAvailableTools()
        }
    }

    @Nested
    @DisplayName("Get Builtin Tools Tests")
    inner class GetBuiltinToolsTests {

        @Test
        @DisplayName("getBuiltinTools - Return every code-owned tool")
        fun `getBuiltinTools should return tools`() {
            // Given
            `when`(agentToolMapper.selectBuiltinToolList()).thenReturn(listOf(testAgentTool))

            // When
            val result = agentToolService.getBuiltinTools()

            // Then
            assertEquals(1, result.size)
            assertEquals("time-tool-box", result[0].beanName)
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
        @DisplayName("getRequiredEnvParamKeys - Return empty on malformed JSON")
        fun `getRequiredEnvParamKeys should return empty on malformed JSON`() {
            // Given
            testAgentTool.requiredEnvParamKeys = "not-json"
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

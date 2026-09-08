package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.agent.service.client.AgentSpecContextHolder
import com.agnetix.harnax.entity.AgentTool
import com.agnetix.harnax.entity.dto.AgentSpecInfoResponse
import com.agnetix.harnax.entity.dto.ToolDetailDto
import com.agnetix.harnax.mapper.AgentToolMapper
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
import org.mockito.kotlin.never
import org.mockito.quality.Strictness
import java.time.LocalDateTime

/**
 * ToolConfigAdaptorImpl Unit Tests
 * Uses Mockito to simulate Mapper layer dependencies
 *
 * @author agnetix
 * @since 2026-05-16
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ToolConfigAdaptorImplTest {

    @Mock
    private lateinit var agentToolMapper: AgentToolMapper

    @Mock
    private lateinit var specContextHolder: AgentSpecContextHolder

    @InjectMocks
    private lateinit var toolConfigAdaptor: ToolConfigAdaptorImpl

    private lateinit var testAgentTool: AgentTool

    @BeforeEach
    fun setUp() {
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
    }

    @Nested
    @DisplayName("Context-first path tests")
    inner class ContextFirstTests {

        private fun stubContext(toolDetails: List<ToolDetailDto>) {
            val specInfo = AgentSpecInfoResponse(
                agentId = 1L,
                agentName = "Test",
                description = "",
                systemPrompt = "",
                modelId = 1L,
                toolDetails = toolDetails,
            )
            `when`(specContextHolder.get()).thenReturn(specInfo)
        }

        @Test
        @DisplayName("getToolConfig - Load from context when DTO found")
        fun `getToolConfig should load from context when tool DTO found`() {
            val dto = ToolDetailDto(
                id = 1L, name = "ctx-tool", displayName = "Context Tool",
                displayNameZh = null, description = "From context",
                type = "BUILTIN", beanName = "ctx-tool-bean",
                methodName = "run", httpUrl = null, httpMethod = "POST",
                httpHeaders = null, envParams = null, inputSchema = null,
                outputSchema = null, readOnly = 0, needConfirm = 0,
                requiredEnvParamKeys = null, timeoutSeconds = 30,
                bindingNeedConfirm = false,
            )
            stubContext(listOf(dto))

            val result = toolConfigAdaptor.getToolConfig(1L)

            assertNotNull(result)
            assertEquals("ctx-tool", result?.name)
            assertEquals("Context Tool", result?.displayName)
            verify(agentToolMapper, never()).selectById(1L)
        }

        @Test
        @DisplayName("getToolConfig - Fallback to DB when context has no matching tool")
        fun `getToolConfig should fallback to DB when context has no matching tool`() {
            stubContext(emptyList())
            `when`(agentToolMapper.selectById(999L)).thenReturn(testAgentTool)

            val result = toolConfigAdaptor.getToolConfig(999L)

            assertNotNull(result)
            verify(agentToolMapper).selectById(999L)
        }

        @Test
        @DisplayName("getToolConfig - Fallback to DB when context is null")
        fun `getToolConfig should fallback to DB when context is null`() {
            `when`(specContextHolder.get()).thenReturn(null)
            `when`(agentToolMapper.selectById(1L)).thenReturn(testAgentTool)

            val result = toolConfigAdaptor.getToolConfig(1L)

            assertNotNull(result)
            verify(agentToolMapper).selectById(1L)
        }
    }

    @Nested
    @DisplayName("Get Tool Config Tests")
    inner class GetToolConfigTests {

        @Test
        @DisplayName("getToolConfig - Return AgentTool entity when found")
        fun `getToolConfig should return AgentTool when found`() {
            // Given
            `when`(agentToolMapper.selectById(1L)).thenReturn(testAgentTool)

            // When
            val result = toolConfigAdaptor.getToolConfig(1L)

            // Then
            assertNotNull(result)
            assertEquals(1L, result?.id)
            assertEquals("time-tool-box", result?.name)
            verify(agentToolMapper).selectById(1L)
        }

        @Test
        @DisplayName("getToolConfig - Return null when toolId <= 0")
        fun `getToolConfig should return null when toolId is zero or negative`() {
            // When
            val resultZero = toolConfigAdaptor.getToolConfig(0L)
            val resultNegative = toolConfigAdaptor.getToolConfig(-1L)

            // Then
            assertNull(resultZero)
            assertNull(resultNegative)
            verify(agentToolMapper, never()).selectById(0L)
            verify(agentToolMapper, never()).selectById(-1L)
        }

        @Test
        @DisplayName("getToolConfig - Return null when tool not found in DB")
        fun `getToolConfig should return null when tool not found`() {
            // Given
            `when`(agentToolMapper.selectById(999L)).thenReturn(null)

            // When
            val result = toolConfigAdaptor.getToolConfig(999L)

            // Then
            assertNull(result)
            verify(agentToolMapper).selectById(999L)
        }
    }
}

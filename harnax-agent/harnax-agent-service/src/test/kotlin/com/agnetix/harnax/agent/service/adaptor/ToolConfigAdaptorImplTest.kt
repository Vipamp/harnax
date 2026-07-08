package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.entity.AgentTool
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

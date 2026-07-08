package com.agnetix.harnax.agent.provider.tool

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.mock
import org.mockito.quality.Strictness
import org.springframework.context.ApplicationContext

/**
 * ToolRegistry Unit Tests
 * Uses Mockito to simulate Spring ApplicationContext for ToolBox bean discovery
 *
 * @author agnetix
 * @since 2026-05-16
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ToolRegistryTest {

    @Mock
    private lateinit var applicationContext: ApplicationContext

    private lateinit var toolRegistry: ToolRegistry

    private lateinit var mockToolBoxA: ToolBox
    private lateinit var mockToolBoxB: ToolBox

    @BeforeEach
    fun setUp() {
        // Create mock ToolBox instances
        mockToolBoxA = mock<ToolBox>()
        `when`(mockToolBoxA.name()).thenReturn("tool-a")

        mockToolBoxB = mock<ToolBox>()
        `when`(mockToolBoxB.name()).thenReturn("tool-b")

        // Initialize ToolRegistry and inject ApplicationContext via reflection
        toolRegistry = ToolRegistry()
        val field = ToolRegistry::class.java.getDeclaredField("applicationContext")
        field.isAccessible = true
        field.set(toolRegistry, applicationContext)
    }

    @Nested
    @DisplayName("Init Tests")
    inner class InitTests {

        @Test
        @DisplayName("init - Discover and register ToolBox beans from ApplicationContext")
        fun `init should discover and register ToolBox beans`() {
            // Given
            val beans = mapOf("toolA" to mockToolBoxA, "toolB" to mockToolBoxB)
            `when`(applicationContext.getBeansOfType(ToolBox::class.java)).thenReturn(beans)

            // When
            toolRegistry.init()

            // Then
            assertTrue(toolRegistry.contains("toolA"))
            assertTrue(toolRegistry.contains("toolB"))
            assertEquals(2, toolRegistry.getAllToolBoxes().size)
        }
    }

    @Nested
    @DisplayName("Get ToolBox Tests")
    inner class GetToolBoxTests {

        @BeforeEach
        fun registerBeans() {
            val beans = mapOf("toolA" to mockToolBoxA, "toolB" to mockToolBoxB)
            `when`(applicationContext.getBeansOfType(ToolBox::class.java)).thenReturn(beans)
            toolRegistry.init()
        }

        @Test
        @DisplayName("getToolBox - Return registered bean by name")
        fun `getToolBox should return registered bean by name`() {
            // When
            val result = toolRegistry.getToolBox("toolA")

            // Then
            assertNotNull(result)
            assertSame(mockToolBoxA, result)
        }

        @Test
        @DisplayName("getToolBox - Return null for unknown name")
        fun `getToolBox should return null for unknown name`() {
            // When
            val result = toolRegistry.getToolBox("nonExistent")

            // Then
            assertNull(result)
        }
    }

    @Nested
    @DisplayName("Get All ToolBoxes Tests")
    inner class GetAllToolBoxesTests {

        @BeforeEach
        fun registerBeans() {
            val beans = mapOf("toolA" to mockToolBoxA, "toolB" to mockToolBoxB)
            `when`(applicationContext.getBeansOfType(ToolBox::class.java)).thenReturn(beans)
            toolRegistry.init()
        }

        @Test
        @DisplayName("getAllToolBoxes - Return all registered beans")
        fun `getAllToolBoxes should return all registered beans`() {
            // When
            val result = toolRegistry.getAllToolBoxes()

            // Then
            assertNotNull(result)
            assertEquals(2, result.size)
            assertTrue(result.contains(mockToolBoxA))
            assertTrue(result.contains(mockToolBoxB))
        }
    }

    @Nested
    @DisplayName("Get ToolBox Names Tests")
    inner class GetToolBoxNamesTests {

        @BeforeEach
        fun registerBeans() {
            val beans = mapOf("toolA" to mockToolBoxA, "toolB" to mockToolBoxB)
            `when`(applicationContext.getBeansOfType(ToolBox::class.java)).thenReturn(beans)
            toolRegistry.init()
        }

        @Test
        @DisplayName("getToolBoxNames - Return all bean names")
        fun `getToolBoxNames should return all bean names`() {
            // When
            val result = toolRegistry.getToolBoxNames()

            // Then
            assertNotNull(result)
            assertEquals(2, result.size)
            assertTrue(result.contains("toolA"))
            assertTrue(result.contains("toolB"))
        }
    }

    @Nested
    @DisplayName("Contains Tests")
    inner class ContainsTests {

        @BeforeEach
        fun registerBeans() {
            val beans = mapOf("toolA" to mockToolBoxA, "toolB" to mockToolBoxB)
            `when`(applicationContext.getBeansOfType(ToolBox::class.java)).thenReturn(beans)
            toolRegistry.init()
        }

        @Test
        @DisplayName("contains - Return true for registered bean")
        fun `contains should return true for registered bean`() {
            // When & Then
            assertTrue(toolRegistry.contains("toolA"))
            assertTrue(toolRegistry.contains("toolB"))
        }

        @Test
        @DisplayName("contains - Return false for unknown bean")
        fun `contains should return false for unknown bean`() {
            // When & Then
            assertFalse(toolRegistry.contains("nonExistent"))
            assertFalse(toolRegistry.contains(""))
        }
    }
}

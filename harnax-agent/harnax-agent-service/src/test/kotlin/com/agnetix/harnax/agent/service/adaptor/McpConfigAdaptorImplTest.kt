package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.agent.service.client.AgentSpecContextHolder
import com.agnetix.harnax.entity.dto.AgentSpecInfoResponse
import com.agnetix.harnax.entity.dto.McpDetailDto
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class McpConfigAdaptorImplTest {

    private lateinit var specContextHolder: AgentSpecContextHolder
    private lateinit var adaptor: McpConfigAdaptorImpl

    @BeforeEach
    fun setUp() {
        specContextHolder = mock(AgentSpecContextHolder::class.java)
        adaptor = McpConfigAdaptorImpl(specContextHolder)
    }

    private fun stubContext(mcpDetails: List<McpDetailDto>) {
        val specInfo = AgentSpecInfoResponse(
            agentId = 1L,
            agentName = "Test",
            description = "",
            systemPrompt = "",
            modelId = 1L,
            mcpDetails = mcpDetails,
        )
        `when`(specContextHolder.get()).thenReturn(specInfo)
    }

    @Nested
    @DisplayName("Context is the only source")
    inner class ContextTests {

        @Test
        fun `getConfig should load from context when MCP DTO found`() {
            val dto = McpDetailDto(
                id = 1L,
                name = "ctx-mcp",
                description = "From context",
                type = "stdio",
                command = "npx -y @mcp/ctx",
            )
            stubContext(listOf(dto))

            val result = adaptor.getConfig(1L)

            assertNotNull(result)
            assertEquals("ctx-mcp", result!!.name)
            assertEquals("npx -y @mcp/ctx", result.command)
        }

        @Test
        fun `getConfig should convert all DTO fields correctly`() {
            val dto = McpDetailDto(
                id = 5L,
                name = "full-mcp",
                description = "Full config",
                type = "sse",
                command = "",
                url = "http://localhost:3000",
                headers = """{"Authorization":"Bearer xxx"}""",
                envParams = """{"KEY":"val"}""",
                status = 0,
            )
            stubContext(listOf(dto))

            val result = adaptor.getConfig(5L)

            assertNotNull(result)
            assertEquals(5L, result!!.id)
            assertEquals("full-mcp", result.name)
            assertEquals("Full config", result.description)
            assertEquals("sse", result.type)
            assertEquals("", result.command)
            assertEquals("http://localhost:3000", result.url)
            assertEquals("""{"Authorization":"Bearer xxx"}""", result.headers)
            assertEquals("""{"KEY":"val"}""", result.envParams)
            assertEquals(0, result.status)
        }

        @Test
        fun `getConfig should return null when the spec carries no such MCP`() {
            // There is no database fallback any more: a row read here would carry ciphertext that this
            // service has no key to open, and the client would be handed garbage headers.
            stubContext(listOf(McpDetailDto(id = 1L, name = "other", type = "stdio", command = "x")))

            assertNull(adaptor.getConfig(999L))
        }

        @Test
        fun `getConfig should return null when there is no spec in the context`() {
            `when`(specContextHolder.get()).thenReturn(null)

            assertNull(adaptor.getConfig(1L))
        }
    }

    @Nested
    @DisplayName("Basic validation")
    inner class BasicValidation {

        @Test
        fun `getConfig should return null for invalid mcpId zero`() {
            val result = adaptor.getConfig(0)
            assertNull(result)
        }

        @Test
        fun `getConfig should return null for negative mcpId`() {
            val result = adaptor.getConfig(-1)
            assertNull(result)
        }
    }
}

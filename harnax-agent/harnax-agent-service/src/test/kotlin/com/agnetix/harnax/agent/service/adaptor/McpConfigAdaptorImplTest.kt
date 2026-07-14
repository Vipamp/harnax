package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.agent.service.client.AgentSpecContextHolder
import com.agnetix.harnax.entity.McpServer
import com.agnetix.harnax.entity.dto.AgentSpecInfoResponse
import com.agnetix.harnax.entity.dto.McpDetailDto
import com.agnetix.harnax.mapper.McpServerMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class McpConfigAdaptorImplTest {

    private lateinit var specContextHolder: AgentSpecContextHolder
    private lateinit var mcpServerMapper: McpServerMapper
    private lateinit var adaptor: McpConfigAdaptorImpl

    private lateinit var testMcpServer: McpServer

    @BeforeEach
    fun setUp() {
        specContextHolder = mock(AgentSpecContextHolder::class.java)
        mcpServerMapper = mock(McpServerMapper::class.java)
        adaptor = McpConfigAdaptorImpl(specContextHolder, mcpServerMapper)

        testMcpServer = McpServer().apply {
            id = 1L
            name = "test-mcp"
            description = "A test MCP server"
            type = "stdio"
            command = "npx -y @mcp/test"
            url = ""
        }
    }

    private fun stubContext(mcpDetails: List<McpDetailDto>) {
        val specInfo = AgentSpecInfoResponse(
            agentId = 1L, agentName = "Test", description = "", systemPrompt = "",
            modelId = 1L, mcpDetails = mcpDetails,
        )
        `when`(specContextHolder.get()).thenReturn(specInfo)
    }

    @Nested
    @DisplayName("Context-first path")
    inner class ContextFirstTests {

        @Test
        fun `getConfig should load from context when MCP DTO found`() {
            val dto = McpDetailDto(
                id = 1L, name = "ctx-mcp", description = "From context",
                type = "stdio", command = "npx -y @mcp/ctx",
            )
            stubContext(listOf(dto))

            val result = adaptor.getConfig(1L)

            assertNotNull(result)
            assertEquals("ctx-mcp", result!!.name)
            assertEquals("npx -y @mcp/ctx", result.command)
            verify(mcpServerMapper, never()).selectById(1L)
        }

        @Test
        fun `getConfig should convert all DTO fields correctly`() {
            val dto = McpDetailDto(
                id = 5L, name = "full-mcp", description = "Full config",
                type = "sse", command = "", url = "http://localhost:3000",
                headers = """{"Authorization":"Bearer xxx"}""",
                envParams = """{"KEY":"val"}""",
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
        }

        @Test
        fun `getConfig should fallback to DB when context has no matching MCP`() {
            stubContext(emptyList())
            `when`(mcpServerMapper.selectById(999L)).thenReturn(testMcpServer)

            val result = adaptor.getConfig(999L)

            assertNotNull(result)
            verify(mcpServerMapper).selectById(999L)
        }

        @Test
        fun `getConfig should fallback to DB when context is null`() {
            `when`(specContextHolder.get()).thenReturn(null)
            `when`(mcpServerMapper.selectById(1L)).thenReturn(testMcpServer)

            val result = adaptor.getConfig(1L)

            assertNotNull(result)
            verify(mcpServerMapper).selectById(1L)
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

        @Test
        fun `getConfig should return null when MCP not found in DB`() {
            `when`(specContextHolder.get()).thenReturn(null)
            `when`(mcpServerMapper.selectById(999L)).thenReturn(null)

            val result = adaptor.getConfig(999L)

            assertNull(result)
            verify(mcpServerMapper).selectById(999L)
        }
    }
}

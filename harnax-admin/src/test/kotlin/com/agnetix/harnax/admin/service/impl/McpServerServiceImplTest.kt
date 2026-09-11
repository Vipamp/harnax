package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.McpOAuthConfig
import com.agnetix.harnax.admin.dto.McpServerCreateRequest
import com.agnetix.harnax.admin.dto.McpServerUpdateRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.SecretFieldEncryptor
import com.agnetix.harnax.entity.McpAuthTypes
import com.agnetix.harnax.entity.McpServer
import com.agnetix.harnax.mapper.AgentMcpBindingMapper
import com.agnetix.harnax.mapper.McpServerMapper
import com.agnetix.harnax.mapper.McpUserCredentialMapper
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
 * McpServerServiceImpl Unit Tests
 * Uses Mockito to simulate Mapper layer dependencies
 *
 * @author agnetix
 * @since 2026-05-16
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpServerServiceImplTest {

    @Mock
    private lateinit var mcpServerMapper: McpServerMapper

    @Mock
    private lateinit var jwtUtil: JwtUtil

    @Mock
    private lateinit var secretFieldEncryptor: SecretFieldEncryptor

    @Mock
    private lateinit var objectMapper: ObjectMapper

    @Mock
    private lateinit var agentMcpBindingMapper: AgentMcpBindingMapper

    // @InjectMocks resolves the biggest constructor: an undeclared parameter arrives as null and
    // Kotlin's non-null argument check fails the whole class, so this list must follow the service.
    @Mock
    private lateinit var mcpUserCredentialMapper: McpUserCredentialMapper

    @InjectMocks
    private lateinit var mcpServerService: McpServerServiceImpl

    private lateinit var testMcpServer: McpServer

    @BeforeEach
    fun setUp() {
        // Initialize test data
        testMcpServer = McpServer().apply {
            id = 1L
            tenantId = 1L
            name = "Weather MCP"
            description = "Weather query service"
            type = "streamablehttp"
            command = ""
            url = "http://localhost:8081/weather"
            status = 1
            isPublic = 1
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

    @Nested
    @DisplayName("Page Query Tests")
    inner class PageQueryTests {

        @Test
        @DisplayName("page - Normal pagination query")
        fun `page should return paginated results`() {
            // Given
            val mcpServers = listOf(testMcpServer)
            // With no request-scoped tenant the service falls back to the default tenant
            `when`(mcpServerMapper.selectMcpServerList(null, null, null, "admin", 1L)).thenReturn(mcpServers)

            // When
            val page = mcpServerService.page(null, null, null, 1, 10)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 0)
            verify(mcpServerMapper).selectMcpServerList(null, null, null, "admin", 1L)
        }

        @Test
        @DisplayName("page - Filter by keyword")
        fun `page should filter by keyword`() {
            // Given
            val filteredServers = listOf(testMcpServer)
            `when`(mcpServerMapper.selectMcpServerList("Weather", null, null, "admin", 1L)).thenReturn(filteredServers)

            // When
            val page = mcpServerService.page("Weather", null, null, 1, 10)

            // Then
            assertNotNull(page)
            verify(mcpServerMapper).selectMcpServerList("Weather", null, null, "admin", 1L)
        }

        @Test
        @DisplayName("page - Push the current tenant into the query")
        fun `page should filter by tenant`() {
            // Given - 不带 tenantId 就等于把别的租户标为公开的 MCP 也列出来
            TenantContext.setTenantId(7L)
            try {
                `when`(mcpServerMapper.selectMcpServerList(null, null, null, "admin", 7L)).thenReturn(listOf(testMcpServer))

                // When
                val page = mcpServerService.page(null, null, null, 1, 10)

                // Then
                assertNotNull(page)
                verify(mcpServerMapper).selectMcpServerList(null, null, null, "admin", 7L)
            } finally {
                TenantContext.clear()
            }
        }
    }

    @Nested
    @DisplayName("Get MCP Server Tests")
    inner class GetMcpServerTests {

        @Test
        @DisplayName("getMcpServer - Query by ID successfully")
        fun `getMcpServer should return mcp server by id`() {
            // Given
            `when`(mcpServerMapper.selectById(1L)).thenReturn(testMcpServer)

            // When
            val result = mcpServerService.getMcpServer(1L)

            // Then
            assertNotNull(result)
            assertEquals("Weather MCP", result?.name)
            verify(mcpServerMapper).selectById(1L)
        }

        @Test
        @DisplayName("getMcpServer - Return null when not exists")
        fun `getMcpServer should return null when not exists`() {
            // Given
            `when`(mcpServerMapper.selectById(999L)).thenReturn(null)

            // When
            val result = mcpServerService.getMcpServer(999L)

            // Then
            assertNull(result)
            verify(mcpServerMapper).selectById(999L)
        }

        @Test
        @DisplayName("getMcpServer - Return null for another tenant's row")
        fun `getMcpServer should return null for another tenant row`() {
            // Given - selectById 的 SQL 不带租户条件，租户拦截器又是空转的，只能在这一层挡住
            `when`(mcpServerMapper.selectById(1L)).thenReturn(serverOfTenant(2L))

            assertNull(mcpServerService.getMcpServer(1L))
        }

        @Test
        @DisplayName("deleteMcpServer - Refuse another tenant's row even with the right id")
        fun `deleteMcpServer should refuse another tenant row`() {
            // Given - id 是自增的，猜到数字就能删掉别人租户的服务
            `when`(mcpServerMapper.selectById(1L)).thenReturn(serverOfTenant(2L))

            val exception = assertThrows<BizException> { mcpServerService.deleteMcpServer(1L) }

            assertEquals("MCP server not found", exception.message)
            verify(mcpServerMapper, never()).deleteById(anyLong())
        }

        private fun serverOfTenant(tenantId: Long) = McpServer().apply {
            id = 1L
            this.tenantId = tenantId
            name = "Other Tenant MCP"
            type = "streamablehttp"
            url = "http://localhost:8080"
            status = 1
            isPublic = 1
            creator = "other"
            active = 1
        }
    }

    @Nested
    @DisplayName("Create MCP Server Tests")
    inner class CreateMcpServerTests {

        @Test
        @DisplayName("createMcpServer - Create streamablehttp type successfully")
        fun `createMcpServer should create streamablehttp type successfully`() {
            // Given
            val request = McpServerCreateRequest(
                name = "New MCP",
                description = "New MCP server",
                type = "streamablehttp",
                url = "http://localhost:8080/mcp",
            )

            `when`(mcpServerMapper.selectByName("New MCP", 1L)).thenReturn(null)
            `when`(mcpServerMapper.insert(any())).thenReturn(1)

            // When
            val result = mcpServerService.createMcpServer(request)

            // Then
            assertTrue(result)
            verify(mcpServerMapper).selectByName("New MCP", 1L)
            verify(mcpServerMapper).insert(any())
        }

        @Test
        @DisplayName("createMcpServer - Persist the public flag carried by the request")
        fun `createMcpServer should apply request isPublic`() {
            // Given - the create form has a public switch that the DTO used to drop
            val request = McpServerCreateRequest(
                name = "Private MCP",
                type = "streamablehttp",
                url = "http://localhost:8080/mcp",
                isPublic = 0,
            )

            `when`(mcpServerMapper.selectByName("Private MCP", 1L)).thenReturn(null)
            `when`(mcpServerMapper.insert(any())).thenReturn(1)

            // When
            mcpServerService.createMcpServer(request)

            // Then
            val captor = argumentCaptor<McpServer>()
            verify(mcpServerMapper).insert(captor.capture())
            assertEquals(0, captor.firstValue.isPublic)
        }

        @Test
        @DisplayName("createMcpServer - Default to public when the request omits isPublic")
        fun `createMcpServer should default to public without isPublic`() {
            // Given
            val request = McpServerCreateRequest(
                name = "No Flag MCP",
                type = "streamablehttp",
                url = "http://localhost:8080/mcp",
            )

            `when`(mcpServerMapper.selectByName("No Flag MCP", 1L)).thenReturn(null)
            `when`(mcpServerMapper.insert(any())).thenReturn(1)

            // When
            mcpServerService.createMcpServer(request)

            // Then
            val captor = argumentCaptor<McpServer>()
            verify(mcpServerMapper).insert(captor.capture())
            assertEquals(1, captor.firstValue.isPublic)
        }

        @Test
        @DisplayName("createMcpServer - Create stdio type successfully")
        fun `createMcpServer should create stdio type successfully`() {
            // Given
            val request = McpServerCreateRequest(
                name = "Stdio MCP",
                type = "stdio",
                command = "python app.py",
            )

            `when`(mcpServerMapper.selectByName("Stdio MCP", 1L)).thenReturn(null)
            `when`(mcpServerMapper.insert(any())).thenReturn(1)

            // When
            val result = mcpServerService.createMcpServer(request)

            // Then
            assertTrue(result)
            verify(mcpServerMapper).insert(any())
        }

        @Test
        @DisplayName("createMcpServer - Throw BizException when name exists")
        fun `createMcpServer should throw BizException when name exists`() {
            // Given
            val request = McpServerCreateRequest(
                name = "Existing MCP",
                type = "streamablehttp",
                url = "http://localhost:8080",
            )

            `when`(mcpServerMapper.selectByName("Existing MCP", 1L)).thenReturn(testMcpServer)

            // When & Then
            val exception = assertThrows<BizException> {
                mcpServerService.createMcpServer(request)
            }
            assertEquals("MCP name already exists", exception.message)
            verify(mcpServerMapper, never()).insert(any())
        }

        @Test
        @DisplayName("createMcpServer - Throw BizException when stdio type without command")
        fun `createMcpServer should throw BizException when stdio type without command`() {
            // Given
            val request = McpServerCreateRequest(
                name = "Invalid Stdio MCP",
                type = "stdio",
            )

            `when`(mcpServerMapper.selectByName("Invalid Stdio MCP", 1L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                mcpServerService.createMcpServer(request)
            }
            assertEquals("MCP server of stdio type, command cannot be empty", exception.message)
            verify(mcpServerMapper, never()).insert(any())
        }

        @Test
        @DisplayName("createMcpServer - Throw BizException when sse type without url")
        fun `createMcpServer should throw BizException when sse type without url`() {
            // Given
            val request = McpServerCreateRequest(
                name = "Invalid SSE MCP",
                type = "sse",
            )

            `when`(mcpServerMapper.selectByName("Invalid SSE MCP", 1L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                mcpServerService.createMcpServer(request)
            }
            assertEquals("MCP server of sse type, url cannot be empty", exception.message)
            verify(mcpServerMapper, never()).insert(any())
        }

        @Test
        @DisplayName("createMcpServer - Throw BizException when unsupported type")
        fun `createMcpServer should throw BizException when unsupported type`() {
            // Given
            val request = McpServerCreateRequest(
                name = "Invalid Type MCP",
                type = "invalid",
            )

            `when`(mcpServerMapper.selectByName("Invalid Type MCP", 1L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                mcpServerService.createMcpServer(request)
            }
            assertEquals("Unsupported MCP type: invalid, only supports stdio/sse/streamablehttp", exception.message)
            verify(mcpServerMapper, never()).insert(any())
        }
    }

    @Nested
    @DisplayName("Update MCP Server Tests")
    inner class UpdateMcpServerTests {

        @Test
        @DisplayName("updateMcpServer - Update partial fields successfully")
        fun `updateMcpServer should update partial fields successfully`() {
            // Given
            val request = McpServerUpdateRequest(
                name = "Updated MCP",
                description = "Updated description",
                type = "streamablehttp",
                url = "http://localhost:8080/updated",
            )

            `when`(mcpServerMapper.selectById(1L)).thenReturn(testMcpServer)
            `when`(mcpServerMapper.selectByName("Updated MCP", 1L)).thenReturn(null)
            `when`(mcpServerMapper.updateById(any())).thenReturn(1)

            // When
            val result = mcpServerService.updateMcpServer(1L, request)

            // Then
            assertTrue(result)
            verify(mcpServerMapper).selectById(1L)
            verify(mcpServerMapper).updateById(any())
        }

        @Test
        @DisplayName("updateMcpServer - Persist the status carried by the request")
        fun `updateMcpServer should apply request status`() {
            // Given - the edit form has an enable/disable switch that used to be discarded
            val request = McpServerUpdateRequest(
                name = "Updated MCP",
                type = "streamablehttp",
                url = "http://localhost:8080/updated",
                status = 0,
            )

            `when`(mcpServerMapper.selectById(1L)).thenReturn(testMcpServer)
            `when`(mcpServerMapper.selectByName("Updated MCP", 1L)).thenReturn(null)
            `when`(mcpServerMapper.updateById(any())).thenReturn(1)

            // When
            mcpServerService.updateMcpServer(1L, request)

            // Then
            val captor = argumentCaptor<McpServer>()
            verify(mcpServerMapper).updateById(captor.capture())
            assertEquals(0, captor.firstValue.status)
        }

        @Test
        @DisplayName("updateMcpServer - Leave status and isPublic alone when the request omits them")
        fun `updateMcpServer should keep status and isPublic when omitted`() {
            // Given - both fields defaulted to 0 before, so an update silently made the server private
            val request = McpServerUpdateRequest(
                name = "Weather MCP",
                type = "streamablehttp",
                url = "http://localhost:8080/updated",
            )

            `when`(mcpServerMapper.selectById(1L)).thenReturn(testMcpServer)
            `when`(mcpServerMapper.updateById(any())).thenReturn(1)

            // When
            mcpServerService.updateMcpServer(1L, request)

            // Then
            val captor = argumentCaptor<McpServer>()
            verify(mcpServerMapper).updateById(captor.capture())
            assertEquals(1, captor.firstValue.status)
            assertEquals(1, captor.firstValue.isPublic)
        }

        @Test
        @DisplayName("updateMcpServer - Throw BizException when MCP not found")
        fun `updateMcpServer should throw BizException when mcp not found`() {
            // Given
            val request = McpServerUpdateRequest(name = "Test")

            `when`(mcpServerMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                mcpServerService.updateMcpServer(999L, request)
            }
            assertEquals("MCP server not found", exception.message)
            verify(mcpServerMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("updateMcpServer - Throw BizException when name exists")
        fun `updateMcpServer should throw BizException when name exists`() {
            // Given
            val existingMcp = McpServer().apply {
                id = 2L
                name = "Existing Name"
                type = "streamablehttp"
                url = "http://localhost:8080"
            }

            val request = McpServerUpdateRequest(name = "Existing Name")

            `when`(mcpServerMapper.selectById(1L)).thenReturn(testMcpServer)
            `when`(mcpServerMapper.selectByName("Existing Name", 1L)).thenReturn(existingMcp)

            // When & Then
            val exception = assertThrows<BizException> {
                mcpServerService.updateMcpServer(1L, request)
            }
            assertEquals("MCP name already exists", exception.message)
            verify(mcpServerMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("updateMcpServer - Throw BizException when stdio type without command")
        fun `updateMcpServer should throw BizException when stdio type without command`() {
            // Given
            val request = McpServerUpdateRequest(
                type = "stdio",
                command = "",
            )

            `when`(mcpServerMapper.selectById(1L)).thenReturn(testMcpServer)
            `when`(mcpServerMapper.updateById(any())).thenReturn(1)

            // When & Then
            val exception = assertThrows<BizException> {
                mcpServerService.updateMcpServer(1L, request)
            }
            assertEquals("MCP server of stdio type, command cannot be empty", exception.message)
        }

        @Test
        @DisplayName("updateMcpServer - 换成 stdio 时清掉网络型字段")
        fun `updateMcpServer should clear the http-only fields when switching to stdio`() {
            val stored = McpServer().apply {
                id = 1L
                tenantId = 1L
                name = "Weather MCP"
                type = "streamablehttp"
                url = "http://localhost:8081/weather"
                headers = """[{"key":"Authorization","value":"enc:xxx","secret":true}]"""
                command = ""
                status = 1
                active = 1
                creator = "admin"
            }
            `when`(mcpServerMapper.selectById(1L)).thenReturn(stored)
            `when`(mcpServerMapper.updateById(any())).thenReturn(1)

            mcpServerService.updateMcpServer(
                1L,
                McpServerUpdateRequest(type = "stdio", command = "npx -y @mcp/weather"),
            )

            // `updateById` writes every column, so an abandoned url would sit on the row forever:
            // the runtime dispatches on `type` and never looks at it, and nobody can tell that this
            // server is really two configurations glued together.
            val captor = argumentCaptor<McpServer>()
            verify(mcpServerMapper).updateById(captor.capture())
            assertEquals("stdio", captor.firstValue.type)
            assertEquals("", captor.firstValue.url)
            assertNull(captor.firstValue.headers)
        }

        @Test
        @DisplayName("updateMcpServer - 换成网络型时清掉 stdio 字段")
        fun `updateMcpServer should clear the stdio-only fields when switching to an http type`() {
            val stored = McpServer().apply {
                id = 1L
                tenantId = 1L
                name = "Weather MCP"
                type = "stdio"
                url = ""
                command = "npx -y @mcp/weather"
                envParams = """[{"envParamName":"API_KEY","value":"enc:xxx","secret":true}]"""
                status = 1
                active = 1
                creator = "admin"
            }
            `when`(mcpServerMapper.selectById(1L)).thenReturn(stored)
            `when`(mcpServerMapper.updateById(any())).thenReturn(1)

            mcpServerService.updateMcpServer(
                1L,
                McpServerUpdateRequest(type = "sse", url = "http://localhost:3000/sse"),
            )

            val captor = argumentCaptor<McpServer>()
            verify(mcpServerMapper).updateById(captor.capture())
            assertEquals("sse", captor.firstValue.type)
            assertEquals("", captor.firstValue.command)
            assertNull(captor.firstValue.envParams)
        }
    }

    @Nested
    @DisplayName("Toggle Status Tests")
    inner class ToggleStatusTests {

        @Test
        @DisplayName("toggleMcpServerStatus - Disable MCP server successfully")
        fun `toggleMcpServerStatus should disable mcp server successfully`() {
            // Given
            `when`(mcpServerMapper.selectById(1L)).thenReturn(testMcpServer)
            `when`(mcpServerMapper.updateStatus(1L, 0)).thenReturn(1)

            // When
            val result = mcpServerService.toggleMcpServerStatus(1L, 0)

            // Then
            assertTrue(result)
            verify(mcpServerMapper).selectById(1L)
            verify(mcpServerMapper).updateStatus(1L, 0)
        }

        @Test
        @DisplayName("toggleMcpServerStatus - Enable MCP server successfully")
        fun `toggleMcpServerStatus should enable mcp server successfully`() {
            // Given
            `when`(mcpServerMapper.selectById(1L)).thenReturn(testMcpServer)
            `when`(mcpServerMapper.updateStatus(1L, 1)).thenReturn(1)

            // When
            val result = mcpServerService.toggleMcpServerStatus(1L, 1)

            // Then
            assertTrue(result)
            verify(mcpServerMapper).updateStatus(1L, 1)
        }

        @Test
        @DisplayName("toggleMcpServerStatus - Throw BizException when MCP not found")
        fun `toggleMcpServerStatus should throw BizException when mcp not found`() {
            // Given
            `when`(mcpServerMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                mcpServerService.toggleMcpServerStatus(999L, 0)
            }
            assertEquals("MCP server not found", exception.message)
            verify(mcpServerMapper, never()).updateStatus(anyLong(), anyInt())
        }
    }

    @Nested
    @DisplayName("Delete MCP Server Tests")
    inner class DeleteMcpServerTests {

        @Test
        @DisplayName("deleteMcpServer - Logically delete MCP server successfully")
        fun `deleteMcpServer should logically delete mcp server successfully`() {
            // Given
            `when`(mcpServerMapper.selectById(1L)).thenReturn(testMcpServer)
            `when`(mcpServerMapper.deleteById(1L)).thenReturn(1)

            // When
            val result = mcpServerService.deleteMcpServer(1L)

            // Then
            assertTrue(result)
            verify(mcpServerMapper).selectById(1L)
            verify(mcpServerMapper).deleteById(1L)
            // An orphan binding would keep warning "MCP not found" on every later delivery
            verify(agentMcpBindingMapper).deleteByMcpId(1L)
        }

        @Test
        @DisplayName("deleteMcpServer - Leave bindings alone when the row was not deleted")
        fun `deleteMcpServer should not cascade when nothing was deleted`() {
            // Given
            `when`(mcpServerMapper.selectById(1L)).thenReturn(testMcpServer)
            `when`(mcpServerMapper.deleteById(1L)).thenReturn(0)

            // When
            val result = mcpServerService.deleteMcpServer(1L)

            // Then
            assertFalse(result)
            verify(agentMcpBindingMapper, never()).deleteByMcpId(anyLong())
        }

        @Test
        @DisplayName("deleteMcpServer - Throw BizException when MCP not found")
        fun `deleteMcpServer should throw BizException when mcp not found`() {
            // Given
            `when`(mcpServerMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                mcpServerService.deleteMcpServer(999L)
            }
            assertEquals("MCP server not found", exception.message)
            verify(mcpServerMapper, never()).deleteById(anyLong())
        }
    }

    @Nested
    @DisplayName("Convert To Response Tests")
    inner class ConvertToResponseTests {

        @Test
        @DisplayName("convertToResponse - Convert entity to response")
        fun `convertToResponse should convert entity to response`() {
            // When
            val result = mcpServerService.convertToResponse(testMcpServer)

            // Then
            assertNotNull(result)
            assertEquals(testMcpServer.id, result.id)
            assertEquals(testMcpServer.name, result.name)
            assertEquals(testMcpServer.description, result.description)
            assertEquals(testMcpServer.type, result.type)
        }
    }

    @Nested
    @DisplayName("Auth Type Tests")
    inner class AuthTypeTests {

        @Test
        @DisplayName("createMcpServer - 不传 authType 时落 NONE")
        fun `createMcpServer should default auth type to NONE`() {
            // Given - V25 之前建的行都是「静态 headers」语义,缺省必须是 NONE 而不是报错
            val request = McpServerCreateRequest(
                name = "Plain MCP",
                type = "streamablehttp",
                url = "http://localhost:8080/mcp",
            )
            `when`(mcpServerMapper.selectByName("Plain MCP", 1L)).thenReturn(null)
            `when`(mcpServerMapper.insert(any())).thenReturn(1)

            mcpServerService.createMcpServer(request)

            val captor = argumentCaptor<McpServer>()
            verify(mcpServerMapper).insert(captor.capture())
            assertEquals(McpAuthTypes.NONE, captor.firstValue.authType)
            assertNull(captor.firstValue.oauthConfig)
        }

        @Test
        @DisplayName("createMcpServer - 未知 authType 直接拒")
        fun `createMcpServer should reject an unknown auth type`() {
            val request = McpServerCreateRequest(
                name = "Odd MCP",
                type = "streamablehttp",
                url = "http://localhost:8080/mcp",
                authType = "BEARER",
            )
            `when`(mcpServerMapper.selectByName("Odd MCP", 1L)).thenReturn(null)

            val exception = assertThrows<BizException> { mcpServerService.createMcpServer(request) }
            assertTrue(exception.message!!.contains("Unsupported auth type"))
            verify(mcpServerMapper, never()).insert(any())
        }

        @Test
        @DisplayName("createMcpServer - BASIC 还没接上运行侧,先拒掉")
        fun `createMcpServer should reject BASIC until the runtime honours it`() {
            // Given - 存一个运行时完全不认的值,等于告诉管理员「配好了」而请求仍是裸的
            val request = McpServerCreateRequest(
                name = "Basic MCP",
                type = "streamablehttp",
                url = "http://localhost:8080/mcp",
                authType = McpAuthTypes.BASIC,
            )
            `when`(mcpServerMapper.selectByName("Basic MCP", 1L)).thenReturn(null)

            val exception = assertThrows<BizException> { mcpServerService.createMcpServer(request) }
            assertTrue(exception.message!!.contains("not wired into the runtime yet"))
        }

        @Test
        @DisplayName("createMcpServer - stdio 不接受 OAuth")
        fun `createMcpServer should reject OAuth on a stdio server`() {
            val request = McpServerCreateRequest(
                name = "Stdio OAuth",
                type = "stdio",
                command = "python mcp.py",
                authType = McpAuthTypes.OAUTH2,
            )
            `when`(mcpServerMapper.selectByName("Stdio OAuth", 1L)).thenReturn(null)

            val exception = assertThrows<BizException> { mcpServerService.createMcpServer(request) }
            assertTrue(exception.message!!.contains("no HTTP request to attach a token to"))
        }

        @Test
        @DisplayName("createMcpServer - OAuth 配置序列化成 JSON 落库")
        fun `createMcpServer should store the OAuth config as JSON`() {
            val config = McpOAuthConfig(authorizationServer = "https://auth.example.com/realms/x", scopes = listOf("mcp:read"))
            val request = McpServerCreateRequest(
                name = "OAuth MCP",
                type = "streamablehttp",
                url = "http://localhost:8080/mcp",
                authType = McpAuthTypes.OAUTH2,
                oauthConfig = config,
            )
            `when`(mcpServerMapper.selectByName("OAuth MCP", 1L)).thenReturn(null)
            `when`(objectMapper.writeValueAsString(any<McpOAuthConfig>())).thenReturn("""{"authorizationServer":"https://auth.example.com/realms/x"}""")
            `when`(mcpServerMapper.insert(any())).thenReturn(1)

            mcpServerService.createMcpServer(request)

            val captor = argumentCaptor<McpServer>()
            verify(mcpServerMapper).insert(captor.capture())
            assertEquals(McpAuthTypes.OAUTH2, captor.firstValue.authType)
            assertTrue(captor.firstValue.oauthConfig!!.contains("authorizationServer"))
        }

        @Test
        @DisplayName("createMcpServer - 非 OAuth 却带配置就拒绝,不静默丢弃")
        fun `createMcpServer should reject an OAuth config on a non-OAuth server`() {
            val request = McpServerCreateRequest(
                name = "Mismatched MCP",
                type = "streamablehttp",
                url = "http://localhost:8080/mcp",
                authType = McpAuthTypes.STATIC_HEADER,
                oauthConfig = McpOAuthConfig(scopes = listOf("mcp:read")),
            )
            `when`(mcpServerMapper.selectByName("Mismatched MCP", 1L)).thenReturn(null)

            val exception = assertThrows<BizException> { mcpServerService.createMcpServer(request) }
            assertTrue(exception.message!!.contains("only applies to an auth type of"))
            verify(mcpServerMapper, never()).insert(any())
        }

        @Test
        @DisplayName("createMcpServer - 授权服务器必须是 http(s) 地址")
        fun `createMcpServer should reject a non-http authorization server`() {
            // Given - 发现阶段 admin 会真的去请求这个地址,file:// 等于把本地文件读给配置者看
            val request = McpServerCreateRequest(
                name = "FileIssuer MCP",
                type = "streamablehttp",
                url = "http://localhost:8080/mcp",
                authType = McpAuthTypes.OAUTH2,
                oauthConfig = McpOAuthConfig(authorizationServer = "file:///etc/passwd"),
            )
            `when`(mcpServerMapper.selectByName("FileIssuer MCP", 1L)).thenReturn(null)

            val exception = assertThrows<BizException> { mcpServerService.createMcpServer(request) }
            assertTrue(exception.message!!.contains("must be an http(s) URL"))
        }

        @Test
        @DisplayName("updateMcpServer - 从 OAUTH2 切走时清空配置与用户凭据")
        fun `updateMcpServer should clear the OAuth config when leaving OAUTH2`() {
            val stored = McpServer().apply {
                id = 1L
                tenantId = 1L
                name = "OAuth MCP"
                type = "streamablehttp"
                url = "http://localhost:8080/mcp"
                authType = McpAuthTypes.OAUTH2
                oauthConfig = """{"scopes":["mcp:read"]}"""
                creator = "admin"
                active = 1
            }
            `when`(mcpServerMapper.selectById(1L)).thenReturn(stored)
            `when`(mcpServerMapper.updateById(any())).thenReturn(1)
            `when`(mcpUserCredentialMapper.deleteByMcpId(1L)).thenReturn(2)

            mcpServerService.updateMcpServer(1L, McpServerUpdateRequest(authType = McpAuthTypes.STATIC_HEADER))

            val captor = argumentCaptor<McpServer>()
            verify(mcpServerMapper).updateById(captor.capture())
            assertEquals(McpAuthTypes.STATIC_HEADER, captor.firstValue.authType)
            assertNull(captor.firstValue.oauthConfig)
            // Reading and revoking a grant both go through the OAuth endpoints, and those refuse a
            // server that is no longer OAUTH2: left behind, the ciphertext would have no owner-facing
            // way out at all.
            verify(mcpUserCredentialMapper).deleteByMcpId(1L)
        }

        @Test
        @DisplayName("updateMcpServer - 没离开 OAUTH2 就不动用户凭据")
        fun `updateMcpServer should leave per-user grants alone when OAuth stays on`() {
            val stored = McpServer().apply {
                id = 1L
                tenantId = 1L
                name = "OAuth MCP"
                type = "streamablehttp"
                url = "http://localhost:8080/mcp"
                authType = McpAuthTypes.OAUTH2
                oauthConfig = """{"scopes":["mcp:read"]}"""
                creator = "admin"
                active = 1
            }
            `when`(mcpServerMapper.selectById(1L)).thenReturn(stored)
            `when`(mcpServerMapper.updateById(any())).thenReturn(1)

            mcpServerService.updateMcpServer(1L, McpServerUpdateRequest(description = "still OAuth"))

            verify(mcpUserCredentialMapper, never()).deleteByMcpId(anyLong())
        }

        @Test
        @DisplayName("updateMcpServer - OAuth 服务换地址时清掉逐人凭据")
        fun `updateMcpServer should drop grants when an OAuth server moves to another url`() {
            val stored = McpServer().apply {
                id = 1L
                tenantId = 1L
                name = "OAuth MCP"
                type = "streamablehttp"
                url = "http://localhost:8080/mcp"
                authType = McpAuthTypes.OAUTH2
                oauthConfig = """{"scopes":["mcp:read"]}"""
                creator = "admin"
                active = 1
            }
            `when`(mcpServerMapper.selectById(1L)).thenReturn(stored)
            `when`(mcpServerMapper.updateById(any())).thenReturn(1)
            `when`(mcpUserCredentialMapper.deleteByMcpId(1L)).thenReturn(4)

            mcpServerService.updateMcpServer(1L, McpServerUpdateRequest(url = "http://localhost:9090/mcp"))

            // A grant names the resource it was issued for, so these four tokens can never be
            // exchanged again — keeping them would leave `status` reading "authorized" forever.
            verify(mcpUserCredentialMapper).deleteByMcpId(1L)
            val captor = argumentCaptor<McpServer>()
            verify(mcpServerMapper).updateById(captor.capture())
            assertEquals("http://localhost:9090/mcp", captor.firstValue.url)
            assertEquals(McpAuthTypes.OAUTH2, captor.firstValue.authType)
            assertNotNull(captor.firstValue.oauthConfig)
        }

        @Test
        @DisplayName("updateMcpServer - url 原样重提不算换地址")
        fun `updateMcpServer should keep grants when the url is resubmitted unchanged`() {
            val stored = McpServer().apply {
                id = 1L
                tenantId = 1L
                name = "OAuth MCP"
                type = "streamablehttp"
                url = "http://localhost:8080/mcp"
                authType = McpAuthTypes.OAUTH2
                oauthConfig = """{"scopes":["mcp:read"]}"""
                creator = "admin"
                active = 1
            }
            `when`(mcpServerMapper.selectById(1L)).thenReturn(stored)
            `when`(mcpServerMapper.updateById(any())).thenReturn(1)

            // The edit form always submits the url it was filled with, so "present but identical"
            // must not be read as a move — otherwise every save would log everyone out.
            mcpServerService.updateMcpServer(1L, McpServerUpdateRequest(url = "http://localhost:8080/mcp"))

            verify(mcpUserCredentialMapper, never()).deleteByMcpId(anyLong())
        }

        @Test
        @DisplayName("updateMcpServer - 本来就不是 OAuth 的服务也不去清凭据")
        fun `updateMcpServer should not look for grants on a server that never had OAuth`() {
            `when`(mcpServerMapper.selectById(1L)).thenReturn(testMcpServer)
            `when`(mcpServerMapper.updateById(any())).thenReturn(1)

            mcpServerService.updateMcpServer(1L, McpServerUpdateRequest(description = "no OAuth here"))

            verify(mcpUserCredentialMapper, never()).deleteByMcpId(anyLong())
        }

        @Test
        @DisplayName("updateMcpServer - 把 OAUTH2 服务改成 stdio 要被拒")
        fun `updateMcpServer should reject switching an OAuth server to stdio`() {
            val stored = McpServer().apply {
                id = 1L
                tenantId = 1L
                name = "OAuth MCP"
                type = "streamablehttp"
                url = "http://localhost:8080/mcp"
                authType = McpAuthTypes.OAUTH2
                creator = "admin"
                active = 1
            }
            `when`(mcpServerMapper.selectById(1L)).thenReturn(stored)

            val exception = assertThrows<BizException> {
                mcpServerService.updateMcpServer(1L, McpServerUpdateRequest(type = "stdio", command = "python mcp.py"))
            }
            assertTrue(exception.message!!.contains("no HTTP request to attach a token to"))
            verify(mcpServerMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("deleteMcpServer - 一并清掉用户凭据")
        fun `deleteMcpServer should drop per-user credentials`() {
            `when`(mcpServerMapper.selectById(1L)).thenReturn(testMcpServer)
            `when`(mcpServerMapper.deleteById(1L)).thenReturn(1)
            `when`(agentMcpBindingMapper.deleteByMcpId(1L)).thenReturn(2)
            `when`(mcpUserCredentialMapper.deleteByMcpId(1L)).thenReturn(3)

            assertTrue(mcpServerService.deleteMcpServer(1L))

            verify(mcpUserCredentialMapper).deleteByMcpId(1L)
        }

        @Test
        @DisplayName("updateMcpServer - 请求没带授权服务器时保住发现写入的 issuer")
        fun `updateMcpServer should keep a discovered issuer the request leaves out`() {
            val stored = McpServer().apply {
                id = 1L
                tenantId = 1L
                name = "OAuth MCP"
                type = "streamablehttp"
                url = "http://localhost:8080/mcp"
                authType = McpAuthTypes.OAUTH2
                oauthConfig = """{"authorizationServer":"https://as.example.com","scopes":["mcp:read"]}"""
                creator = "admin"
                active = 1
            }
            `when`(mcpServerMapper.selectById(1L)).thenReturn(stored)
            `when`(mcpServerMapper.updateById(any())).thenReturn(1)
            `when`(objectMapper.readValue(stored.oauthConfig!!, McpOAuthConfig::class.java)).thenReturn(
                McpOAuthConfig(authorizationServer = "https://as.example.com", scopes = listOf("mcp:read")),
            )
            `when`(objectMapper.writeValueAsString(any<McpOAuthConfig>())).thenReturn("{}")

            mcpServerService.updateMcpServer(1L, McpServerUpdateRequest(oauthConfig = McpOAuthConfig(scopes = listOf("mcp:read", "mcp:write"))))

            // The form does not show that field, so "absent" here means "I did not touch it": dropping it
            // would orphan the tenant's registration and force a re-discovery to undo a scope edit.
            val configCaptor = argumentCaptor<McpOAuthConfig>()
            verify(objectMapper).writeValueAsString(configCaptor.capture())
            assertEquals("https://as.example.com", configCaptor.firstValue.authorizationServer)
            assertEquals(listOf("mcp:read", "mcp:write"), configCaptor.firstValue.scopes)
        }

        @Test
        @DisplayName("createMcpServer - 只有协议没有 host 的授权服务器也要被拒")
        fun `createMcpServer should reject an authorization server with no host`() {
            val request = McpServerCreateRequest(
                name = "Half URL MCP",
                type = "streamablehttp",
                url = "http://localhost:8080/mcp",
                authType = McpAuthTypes.OAUTH2,
                oauthConfig = McpOAuthConfig(authorizationServer = "https://"),
            )
            `when`(mcpServerMapper.selectByName("Half URL MCP", 1L)).thenReturn(null)

            val exception = assertThrows<BizException> { mcpServerService.createMcpServer(request) }
            assertTrue(exception.message!!.contains("must be an http(s) URL"))
            verify(mcpServerMapper, never()).insert(any())
        }
    }
}

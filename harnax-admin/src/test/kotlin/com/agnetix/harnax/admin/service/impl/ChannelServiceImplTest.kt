package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.ChannelCreateRequest
import com.agnetix.harnax.admin.dto.ChannelUpdateRequest
import com.agnetix.harnax.admin.service.AgentService
import com.agnetix.harnax.entity.Agent
import com.agnetix.harnax.entity.Channel
import com.agnetix.harnax.mapper.ChannelMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.never
import org.mockito.quality.Strictness
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.LocalDateTime

/**
 * ChannelServiceImpl Unit Tests
 * Uses Mockito to simulate Mapper and Service layer dependencies
 * Covers normal flows, exception flows, and boundary conditions
 *
 * @author agnetix
 * @since 2026-05-17
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChannelServiceImplTest {

    @Mock
    private lateinit var channelMapper: ChannelMapper

    @Mock
    private lateinit var agentService: AgentService

    private lateinit var testChannel: Channel
    private lateinit var testAgent: Agent

    @BeforeEach
    fun setUp() {
        testChannel = Channel().apply {
            id = 1L
            tenantId = 1L
            name = "Test Channel"
            type = "wecom"
            agentId = 100L
            callbackKey = "wecom-abcdef1234567890"
            sessionId = "chn-11111111-2222-3333-4444-555555555555"
            communicationMode = "webhook"
            permissionMode = "DEFAULT"
            enabled = 1
            configJson = """{"appId":"xxx","appSecret":"yyy"}"""
            description = "Test channel description"
            creator = "admin"
            status = 1
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }

        testAgent = Agent().apply {
            id = 100L
            name = "Test Agent"
            description = "Test agent description"
            systemPrompt = "You are a helpful assistant"
            modelId = 1L
            status = 1
            active = 1
        }

        // Mock HttpServletRequest for RequestContextHolder
        val mockRequest = MockHttpServletRequest()
        mockRequest.addHeader("Authorization", "Bearer mock-token")
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(mockRequest))
    }

    @AfterEach
    fun tearDown() {
        TenantContext.clear()
        RequestContextHolder.resetRequestAttributes()
    }

    private fun createService(): ChannelServiceImpl {
        val service = ChannelServiceImpl(
            channelMapper = channelMapper,
            agentService = agentService,
        )
        // baseUrl is injected via @Value, set it manually
        ReflectionTestUtils.setField(service, "baseUrl", "http://localhost:8080")
        return service
    }

    @Nested
    @DisplayName("Page Query Tests")
    inner class PageQueryTests {

        @Test
        @DisplayName("page - Normal pagination query")
        fun `page should return paginated results`() {
            // Given
            val channels = listOf(testChannel)
            `when`(channelMapper.selectChannelList(null, null, null)).thenReturn(channels)

            // When
            val page = createService().page(null, null, null, 1, 10)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 0)
            verify(channelMapper).selectChannelList(null, null, null)
        }

        @Test
        @DisplayName("page - Filter by keyword")
        fun `page should filter by keyword`() {
            // Given
            `when`(channelMapper.selectChannelList("Test", null, null)).thenReturn(listOf(testChannel))

            // When
            val page = createService().page("Test", null, null, 1, 10)

            // Then
            assertNotNull(page)
            verify(channelMapper).selectChannelList("Test", null, null)
        }

        @Test
        @DisplayName("page - Filter by type")
        fun `page should filter by type`() {
            // Given
            `when`(channelMapper.selectChannelList(null, "wecom", null)).thenReturn(listOf(testChannel))

            // When
            val page = createService().page(null, "wecom", null, 1, 10)

            // Then
            assertNotNull(page)
            verify(channelMapper).selectChannelList(null, "wecom", null)
        }

        @Test
        @DisplayName("page - Filter by status")
        fun `page should filter by status`() {
            // Given
            `when`(channelMapper.selectChannelList(null, null, 1)).thenReturn(listOf(testChannel))

            // When
            val page = createService().page(null, null, 1, 1, 10)

            // Then
            assertNotNull(page)
            verify(channelMapper).selectChannelList(null, null, 1)
        }

        @Test
        @DisplayName("page - Return empty page when no data")
        fun `page should return empty page when no data`() {
            // Given
            `when`(channelMapper.selectChannelList(null, null, null)).thenReturn(emptyList())

            // When
            val page = createService().page(null, null, null, 1, 10)

            // Then
            assertNotNull(page)
            assertTrue(page.records.isEmpty())
        }
    }

    @Nested
    @DisplayName("Get Channel Tests")
    inner class GetChannelTests {

        @Test
        @DisplayName("getChannel - Query by ID successfully")
        fun `getChannel should return channel by id`() {
            // Given
            `when`(channelMapper.selectById(1L)).thenReturn(testChannel)

            // When
            val result = createService().getChannel(1L)

            // Then
            assertNotNull(result)
            assertEquals("Test Channel", result?.name)
            assertEquals("wecom", result?.type)
            verify(channelMapper).selectById(1L)
        }

        @Test
        @DisplayName("getChannel - Return null when not exists")
        fun `getChannel should return null when not exists`() {
            // Given
            `when`(channelMapper.selectById(999L)).thenReturn(null)

            // When
            val result = createService().getChannel(999L)

            // Then
            assertNull(result)
            verify(channelMapper).selectById(999L)
        }
    }

    @Nested
    @DisplayName("Create Channel Tests")
    inner class CreateChannelTests {

        @Test
        @DisplayName("createChannel - Create channel with all fields successfully")
        fun `createChannel should create channel with all fields successfully`() {
            // Given
            val request = ChannelCreateRequest(
                name = "New Channel",
                type = "feishu",
                agentId = 100L,
                communicationMode = "websocket",
                permissionMode = "BYPASS",
                enabled = 0,
                configJson = """{"appId":"a1"}""",
                description = "New channel description",
                status = 0,
            )

            `when`(channelMapper.insert(any())).thenReturn(1)

            // When
            val result = createService().createChannel(request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<Channel>()
            verify(channelMapper).insert(captor.capture())
            val saved = captor.firstValue
            assertEquals("New Channel", saved.name)
            assertEquals("feishu", saved.type)
            assertEquals(100L, saved.agentId)
            assertEquals("websocket", saved.communicationMode)
            assertEquals("BYPASS", saved.permissionMode)
            assertEquals(0, saved.enabled)
            assertEquals("""{"appId":"a1"}""", saved.configJson)
            assertEquals(0, saved.status)
        }

        @Test
        @DisplayName("createChannel - Set default values when optional fields not provided")
        fun `createChannel should set default values when optional fields not provided`() {
            // Given
            val request = ChannelCreateRequest(
                name = "Minimal Channel",
                type = "http",
                agentId = 100L,
            )

            `when`(channelMapper.insert(any())).thenReturn(1)

            // When
            val result = createService().createChannel(request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<Channel>()
            verify(channelMapper).insert(captor.capture())
            val saved = captor.firstValue
            assertEquals("webhook", saved.communicationMode)
            assertEquals("DEFAULT", saved.permissionMode)
            assertEquals(1, saved.enabled)
            assertEquals(1, saved.status)
        }

        @Test
        @DisplayName("createChannel - Set tenantId from TenantContext")
        fun `createChannel should set tenantId from TenantContext`() {
            // Given
            TenantContext.setTenantId(42L)
            val request = ChannelCreateRequest(
                name = "Tenant Channel",
                type = "wecom",
                agentId = 100L,
            )

            `when`(channelMapper.insert(any())).thenReturn(1)

            // When
            val result = createService().createChannel(request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<Channel>()
            verify(channelMapper).insert(captor.capture())
            assertEquals(42L, captor.firstValue.tenantId)
        }

        @Test
        @DisplayName("createChannel - Use default tenantId 1 when TenantContext not set")
        fun `createChannel should use default tenantId when TenantContext not set`() {
            // Given - TenantContext not set
            val request = ChannelCreateRequest(
                name = "No Tenant Channel",
                type = "wecom",
                agentId = 100L,
            )

            `when`(channelMapper.insert(any())).thenReturn(1)

            // When
            val result = createService().createChannel(request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<Channel>()
            verify(channelMapper).insert(captor.capture())
            assertEquals(1L, captor.firstValue.tenantId)
        }

        @Test
        @DisplayName("createChannel - Generate callbackKey with type prefix and sessionId with chn prefix")
        fun `createChannel should generate callbackKey and sessionId`() {
            // Given
            val request = ChannelCreateRequest(
                name = "Key Channel",
                type = "DingTalk",
                agentId = 100L,
            )

            `when`(channelMapper.insert(any())).thenReturn(1)

            // When
            val result = createService().createChannel(request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<Channel>()
            verify(channelMapper).insert(captor.capture())
            val saved = captor.firstValue
            // callbackKey prefix is lowercased channel type
            assertTrue(saved.callbackKey.startsWith("dingtalk-"))
            // sessionId is immutable and prefixed with chn-
            assertTrue(saved.sessionId.startsWith("chn-"))
        }

        @Test
        @DisplayName("createChannel - Throw RuntimeException when required name missing")
        fun `createChannel should throw RuntimeException when name missing`() {
            // Given - name is null, request.name!! triggers NPE which is wrapped
            val request = ChannelCreateRequest(
                name = null,
                type = "wecom",
                agentId = 100L,
            )

            // When & Then
            val exception = assertThrows<RuntimeException> {
                createService().createChannel(request)
            }
            assertTrue(exception.message?.contains("Failed to create Channel") == true)
            verify(channelMapper, never()).insert(any())
        }

        @Test
        @DisplayName("createChannel - Throw RuntimeException when insert fails")
        fun `createChannel should throw RuntimeException when insert fails`() {
            // Given
            val request = ChannelCreateRequest(
                name = "Fail Channel",
                type = "wecom",
                agentId = 100L,
            )

            doThrow(RuntimeException("db error")).`when`(channelMapper).insert(any())

            // When & Then
            val exception = assertThrows<RuntimeException> {
                createService().createChannel(request)
            }
            assertTrue(exception.message?.contains("Failed to create Channel") == true)
        }
    }

    @Nested
    @DisplayName("Update Channel Tests")
    inner class UpdateChannelTests {

        @Test
        @DisplayName("updateChannel - Update all fields successfully")
        fun `updateChannel should update all fields successfully`() {
            // Given
            val request = ChannelUpdateRequest(
                name = "Updated Channel",
                type = "feishu",
                agentId = 200L,
                communicationMode = "long_polling",
                permissionMode = "ACCEPT_EDITS",
                enabled = 0,
                configJson = """{"appId":"updated"}""",
                description = "Updated description",
                status = 0,
            )

            `when`(channelMapper.selectById(1L)).thenReturn(testChannel)
            `when`(channelMapper.updateById(any())).thenReturn(1)

            // When
            val result = createService().updateChannel(1L, request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<Channel>()
            verify(channelMapper).updateById(captor.capture())
            val updated = captor.firstValue
            assertEquals("Updated Channel", updated.name)
            assertEquals("feishu", updated.type)
            assertEquals(200L, updated.agentId)
            assertEquals("long_polling", updated.communicationMode)
            assertEquals("ACCEPT_EDITS", updated.permissionMode)
            assertEquals(0, updated.enabled)
            assertEquals("""{"appId":"updated"}""", updated.configJson)
            assertEquals("Updated description", updated.description)
            assertEquals(0, updated.status)
        }

        @Test
        @DisplayName("updateChannel - Update partial fields and keep others unchanged")
        fun `updateChannel should update partial fields successfully`() {
            // Given
            val request = ChannelUpdateRequest(
                name = "Partial Channel",
            )

            `when`(channelMapper.selectById(1L)).thenReturn(testChannel)
            `when`(channelMapper.updateById(any())).thenReturn(1)

            // When
            val result = createService().updateChannel(1L, request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<Channel>()
            verify(channelMapper).updateById(captor.capture())
            val updated = captor.firstValue
            assertEquals("Partial Channel", updated.name)
            // Unchanged fields
            assertEquals("wecom", updated.type)
            assertEquals(100L, updated.agentId)
            assertEquals("webhook", updated.communicationMode)
            assertEquals(1, updated.status)
        }

        @Test
        @DisplayName("updateChannel - Throw RuntimeException when channel not found")
        fun `updateChannel should throw RuntimeException when channel not found`() {
            // Given
            val request = ChannelUpdateRequest(name = "Test")

            `when`(channelMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<RuntimeException> {
                createService().updateChannel(999L, request)
            }
            // Service wraps the exception message
            assertTrue(exception.message?.contains("Channel not found") == true)
            verify(channelMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("updateChannel - Throw RuntimeException when update fails")
        fun `updateChannel should throw RuntimeException when update fails`() {
            // Given
            val request = ChannelUpdateRequest(name = "Fail Update")

            `when`(channelMapper.selectById(1L)).thenReturn(testChannel)
            doThrow(RuntimeException("db error")).`when`(channelMapper).updateById(any())

            // When & Then
            val exception = assertThrows<RuntimeException> {
                createService().updateChannel(1L, request)
            }
            assertTrue(exception.message?.contains("Failed to update Channel") == true)
        }
    }

    @Nested
    @DisplayName("Toggle Status Tests")
    inner class ToggleStatusTests {

        @Test
        @DisplayName("toggleChannelStatus - Disable channel successfully")
        fun `toggleChannelStatus should disable channel successfully`() {
            // Given
            `when`(channelMapper.selectById(1L)).thenReturn(testChannel)
            `when`(channelMapper.updateStatus(1L, 0)).thenReturn(1)

            // When
            val result = createService().toggleChannelStatus(1L, 0)

            // Then
            assertTrue(result)
            verify(channelMapper).selectById(1L)
            verify(channelMapper).updateStatus(1L, 0)
        }

        @Test
        @DisplayName("toggleChannelStatus - Enable channel successfully")
        fun `toggleChannelStatus should enable channel successfully`() {
            // Given
            `when`(channelMapper.selectById(1L)).thenReturn(testChannel)
            `when`(channelMapper.updateStatus(1L, 1)).thenReturn(1)

            // When
            val result = createService().toggleChannelStatus(1L, 1)

            // Then
            assertTrue(result)
            verify(channelMapper).updateStatus(1L, 1)
        }

        @Test
        @DisplayName("toggleChannelStatus - Throw RuntimeException when channel not found")
        fun `toggleChannelStatus should throw RuntimeException when channel not found`() {
            // Given
            `when`(channelMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<RuntimeException> {
                createService().toggleChannelStatus(999L, 0)
            }
            assertEquals("Channel not found", exception.message)
            verify(channelMapper, never()).updateStatus(1L, 0)
        }

        @Test
        @DisplayName("toggleChannelStatus - Return false when update affects no rows")
        fun `toggleChannelStatus should return false when update affects no rows`() {
            // Given
            `when`(channelMapper.selectById(1L)).thenReturn(testChannel)
            `when`(channelMapper.updateStatus(1L, 0)).thenReturn(0)

            // When
            val result = createService().toggleChannelStatus(1L, 0)

            // Then
            assertFalse(result)
        }
    }

    @Nested
    @DisplayName("Delete Channel Tests")
    inner class DeleteChannelTests {

        @Test
        @DisplayName("deleteChannel - Delete channel successfully")
        fun `deleteChannel should delete channel successfully`() {
            // Given
            `when`(channelMapper.deleteById(1L)).thenReturn(1)

            // When
            val result = createService().deleteChannel(1L)

            // Then
            assertTrue(result)
            verify(channelMapper).deleteById(1L)
        }

        @Test
        @DisplayName("deleteChannel - Return false when delete fails")
        fun `deleteChannel should return false when delete fails`() {
            // Given
            `when`(channelMapper.deleteById(999L)).thenReturn(0)

            // When
            val result = createService().deleteChannel(999L)

            // Then
            assertFalse(result)
            verify(channelMapper).deleteById(999L)
        }
    }

    @Nested
    @DisplayName("Get By Callback Key Tests")
    inner class GetByCallbackKeyTests {

        @Test
        @DisplayName("getByCallbackKey - Query by callbackKey successfully")
        fun `getByCallbackKey should return channel by callback key`() {
            // Given
            `when`(channelMapper.selectByCallbackKey("wecom-abcdef1234567890")).thenReturn(testChannel)

            // When
            val result = createService().getByCallbackKey("wecom-abcdef1234567890")

            // Then
            assertNotNull(result)
            assertEquals("Test Channel", result?.name)
            verify(channelMapper).selectByCallbackKey("wecom-abcdef1234567890")
        }

        @Test
        @DisplayName("getByCallbackKey - Return null when not exists")
        fun `getByCallbackKey should return null when not exists`() {
            // Given
            `when`(channelMapper.selectByCallbackKey("not-exist")).thenReturn(null)

            // When
            val result = createService().getByCallbackKey("not-exist")

            // Then
            assertNull(result)
            verify(channelMapper).selectByCallbackKey("not-exist")
        }
    }

    @Nested
    @DisplayName("Convert To Response Tests")
    inner class ConvertToResponseTests {

        @Test
        @DisplayName("convertToResponse - Convert channel with agent name and callback URL")
        fun `convertToResponse should convert channel with agent name and callback url`() {
            // Given
            `when`(agentService.getAgent(100L)).thenReturn(testAgent)

            // When
            val result = createService().convertToResponse(testChannel)

            // Then
            assertNotNull(result)
            assertEquals(testChannel.id, result.id)
            assertEquals(testChannel.name, result.name)
            assertEquals("wecom", result.type)
            assertEquals("Test Agent", result.agentName)
            assertEquals(
                "http://localhost:8080/api/channel/callback/wecom-abcdef1234567890",
                result.callbackUrl,
            )
            verify(agentService).getAgent(100L)
        }

        @Test
        @DisplayName("convertToResponse - Agent name is null when agent not found")
        fun `convertToResponse should leave agent name null when agent not found`() {
            // Given
            `when`(agentService.getAgent(100L)).thenReturn(null)

            // When
            val result = createService().convertToResponse(testChannel)

            // Then
            assertNotNull(result)
            assertNull(result.agentName)
            // Callback URL is still generated
            assertNotNull(result.callbackUrl)
        }

        @Test
        @DisplayName("convertToResponse - Type display name is resolved")
        fun `convertToResponse should resolve type display name`() {
            // Given
            `when`(agentService.getAgent(100L)).thenReturn(testAgent)

            // When
            val result = createService().convertToResponse(testChannel)

            // Then
            assertEquals("Enterprise WeChat", result.typeDisplayName)
        }
    }
}

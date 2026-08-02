package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.ChannelCreateRequest
import com.agnetix.harnax.admin.dto.ChannelResponse
import com.agnetix.harnax.admin.dto.ChannelUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.ChannelService
import com.agnetix.harnax.entity.Channel
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
import org.mockito.kotlin.any
import org.mockito.quality.Strictness
import java.time.LocalDateTime

/**
 * ChannelController 单元测试
 * 直接实例化 Controller + Mock Service，断言 ResultVo
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChannelControllerTest {

    @Mock
    private lateinit var channelService: ChannelService

    @InjectMocks
    private lateinit var controller: ChannelController

    private lateinit var testChannel: Channel
    private lateinit var testResponse: ChannelResponse

    @BeforeEach
    fun setUp() {
        testChannel = Channel().apply {
            id = 1L
            tenantId = 1L
            name = "WeCom Channel"
            type = "wecom"
            agentId = 100L
            callbackKey = "cb-key-abc"
            sessionId = "chn-uuid-123"
            communicationMode = "webhook"
            permissionMode = "DEFAULT"
            enabled = 1
            status = 1
            creator = "admin"
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }

        testResponse = ChannelResponse(
            id = 1L,
            name = "WeCom Channel",
            type = "wecom",
            typeDisplayName = "Enterprise WeChat",
            agentId = 100L,
            callbackKey = "cb-key-abc",
            sessionId = "chn-uuid-123",
            status = 1,
        )
    }

    @Nested
    @DisplayName("GET /api/admin/channels/page")
    inner class PageEndpoint {

        @Test
        @DisplayName("pageChannel - 返回分页结果")
        fun `pageChannel should return paginated results`() {
            val page = Page<Channel>(total = 1L, pageNum = 1L, pageSize = 10L, records = listOf(testChannel))
            `when`(channelService.page(null, null, null, 1, 10)).thenReturn(page)
            `when`(channelService.convertToResponse(testChannel)).thenReturn(testResponse)

            val result = controller.pageChannel(1, 10, null, null, null)

            assertTrue(result.isSuccess())
            assertEquals(1L, result.data?.total)
            assertEquals("WeCom Channel", result.data?.records?.get(0)?.name)
        }

        @Test
        @DisplayName("pageChannel - 传递过滤条件")
        fun `pageChannel should pass filters correctly`() {
            val page = Page<Channel>(total = 0L, pageNum = 1L, pageSize = 10L, records = emptyList())
            `when`(channelService.page("wecom", "wecom", 1, 1, 10)).thenReturn(page)

            val result = controller.pageChannel(1, 10, "wecom", "wecom", 1)

            assertTrue(result.isSuccess())
            verify(channelService).page("wecom", "wecom", 1, 1, 10)
        }

        @Test
        @DisplayName("pageChannel - pageNum/pageSize 为 null 时使用默认值")
        fun `pageChannel should use default paging when null`() {
            val page = Page<Channel>(total = 0L, pageNum = 1L, pageSize = 10L, records = emptyList())
            `when`(channelService.page(null, null, null, 1, 10)).thenReturn(page)

            val result = controller.pageChannel(null, null, null, null, null)

            assertTrue(result.isSuccess())
            verify(channelService).page(null, null, null, 1, 10)
        }

        @Test
        @DisplayName("pageChannel - service 抛异常返回 error")
        fun `pageChannel should return error when service throws`() {
            `when`(channelService.page(null, null, null, 1, 10)).thenThrow(RuntimeException("DB error"))

            val result = controller.pageChannel(1, 10, null, null, null)

            assertFalse(result.isSuccess())
            assertEquals(500, result.code)
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/channels/{id}")
    inner class GetByIdEndpoint {

        @Test
        @DisplayName("getChannel - 返回渠道详情")
        fun `getChannel should return channel details`() {
            `when`(channelService.getChannel(1L)).thenReturn(testChannel)
            `when`(channelService.convertToResponse(testChannel)).thenReturn(testResponse)

            val result = controller.getChannel(1L)

            assertTrue(result.isSuccess())
            assertEquals("WeCom Channel", result.data?.name)
            assertEquals("wecom", result.data?.type)
        }

        @Test
        @DisplayName("getChannel - 不存在时 data 为 null")
        fun `getChannel should return null data when not found`() {
            `when`(channelService.getChannel(999L)).thenReturn(null)

            val result = controller.getChannel(999L)

            assertTrue(result.isSuccess())
            assertNull(result.data)
        }

        @Test
        @DisplayName("getChannel - service 抛异常返回 error")
        fun `getChannel should return error when service throws`() {
            `when`(channelService.getChannel(1L)).thenThrow(RuntimeException("DB error"))

            val result = controller.getChannel(1L)

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("POST /api/admin/channels")
    inner class CreateEndpoint {

        @Test
        @DisplayName("createChannel - 创建成功")
        fun `createChannel should return success`() {
            val request = ChannelCreateRequest(name = "New Channel", type = "wecom", agentId = 100L)
            `when`(channelService.createChannel(any())).thenReturn(true)

            val result = controller.createChannel(request)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("createChannel - service 返回 false 时返回 error")
        fun `createChannel should return error when service returns false`() {
            val request = ChannelCreateRequest(name = "New Channel", type = "wecom", agentId = 100L)
            `when`(channelService.createChannel(any())).thenReturn(false)

            val result = controller.createChannel(request)

            assertFalse(result.isSuccess())
            assertEquals("Failed to create channel", result.message)
        }

        @Test
        @DisplayName("createChannel - Agent 不存在时返回 error")
        fun `createChannel should return error when agent not found`() {
            val request = ChannelCreateRequest(name = "New Channel", type = "wecom", agentId = 999L)
            `when`(channelService.createChannel(any())).thenThrow(BizException("Agent not found"))

            val result = controller.createChannel(request)

            assertFalse(result.isSuccess())
            assertEquals("Agent not found", result.message)
        }
    }

    @Nested
    @DisplayName("PUT /api/admin/channels/update/{id}")
    inner class UpdateEndpoint {

        @Test
        @DisplayName("updateChannel - 更新成功")
        fun `updateChannel should return success`() {
            val request = ChannelUpdateRequest(name = "Updated Channel")
            `when`(channelService.updateChannel(any(), any())).thenReturn(true)

            val result = controller.updateChannel(1L, request)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("updateChannel - service 返回 false 时返回 error")
        fun `updateChannel should return error when service returns false`() {
            val request = ChannelUpdateRequest(name = "Updated Channel")
            `when`(channelService.updateChannel(any(), any())).thenReturn(false)

            val result = controller.updateChannel(1L, request)

            assertFalse(result.isSuccess())
            assertEquals("Failed to update channel", result.message)
        }

        @Test
        @DisplayName("updateChannel - 渠道不存在时返回 error")
        fun `updateChannel should return error when not found`() {
            val request = ChannelUpdateRequest(name = "Updated Channel")
            `when`(channelService.updateChannel(any(), any())).thenThrow(BizException("Channel not found"))

            val result = controller.updateChannel(999L, request)

            assertFalse(result.isSuccess())
            assertEquals("Channel not found", result.message)
        }
    }

    @Nested
    @DisplayName("PUT /api/admin/channels/toggle/{id}")
    inner class ToggleEndpoint {

        @Test
        @DisplayName("toggleChannel - 切换状态成功")
        fun `toggleChannel should return success`() {
            `when`(channelService.toggleChannelStatus(1L, 0)).thenReturn(true)

            val result = controller.toggleChannel(1L, 0)

            assertTrue(result.isSuccess())
            verify(channelService).toggleChannelStatus(1L, 0)
        }

        @Test
        @DisplayName("toggleChannel - service 返回 false 时返回 error")
        fun `toggleChannel should return error when service returns false`() {
            `when`(channelService.toggleChannelStatus(1L, 1)).thenReturn(false)

            val result = controller.toggleChannel(1L, 1)

            assertFalse(result.isSuccess())
            assertEquals("Failed to update channel", result.message)
        }

        @Test
        @DisplayName("toggleChannel - service 抛异常返回 error")
        fun `toggleChannel should return error when service throws`() {
            `when`(channelService.toggleChannelStatus(999L, 1)).thenThrow(BizException("Channel not found"))

            val result = controller.toggleChannel(999L, 1)

            assertFalse(result.isSuccess())
            assertEquals("Channel not found", result.message)
        }
    }

    @Nested
    @DisplayName("DELETE /api/admin/channels/{id}")
    inner class DeleteEndpoint {

        @Test
        @DisplayName("deleteChannel - 删除成功")
        fun `deleteChannel should return success`() {
            `when`(channelService.deleteChannel(1L)).thenReturn(true)

            val result = controller.deleteChannel(1L)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("deleteChannel - service 返回 false 时返回 error")
        fun `deleteChannel should return error when service returns false`() {
            `when`(channelService.deleteChannel(1L)).thenReturn(false)

            val result = controller.deleteChannel(1L)

            assertFalse(result.isSuccess())
            assertEquals("Failed to delete channel", result.message)
        }

        @Test
        @DisplayName("deleteChannel - 渠道不存在时返回 error")
        fun `deleteChannel should return error when not found`() {
            `when`(channelService.deleteChannel(999L)).thenThrow(BizException("Channel not found"))

            val result = controller.deleteChannel(999L)

            assertFalse(result.isSuccess())
            assertEquals("Channel not found", result.message)
        }
    }
}

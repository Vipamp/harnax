package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.impl.WechatLoginService
import com.agnetix.harnax.admin.service.impl.WechatLoginStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness

/**
 * WechatLoginController 单元测试
 * 直接实例化 Controller + Mock Service,直调方法断言 ResultVo
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WechatLoginControllerTest {

    @Mock
    private lateinit var wechatLoginService: WechatLoginService

    @InjectMocks
    private lateinit var controller: WechatLoginController

    @Nested
    @DisplayName("POST /api/admin/channels/{id}/wechat/login")
    inner class StartLoginEndpoint {

        @Test
        @DisplayName("startLogin - 成功返回 base64 二维码")
        fun `startLogin should return base64 qr code`() {
            `when`(wechatLoginService.startLogin(1L)).thenReturn("data:image/png;base64,iVBORw0KG...")

            val result = controller.startLogin(1L)

            assertTrue(result.isSuccess())
            assertEquals("data:image/png;base64,iVBORw0KG...", result.data)
        }

        @Test
        @DisplayName("startLogin - service 抛异常时返回错误")
        fun `startLogin should return error on service exception`() {
            `when`(wechatLoginService.startLogin(999L))
                .thenThrow(BizException("Channel not found"))

            val result = controller.startLogin(999L)

            assertFalse(result.isSuccess())
            assertEquals("Channel not found", result.message)
        }

        @Test
        @DisplayName("startLogin - 异常无 message 时返回默认错误消息")
        fun `startLogin should return default error message when exception message is null`() {
            `when`(wechatLoginService.startLogin(1L)).thenThrow(RuntimeException())

            val result = controller.startLogin(1L)

            assertFalse(result.isSuccess())
            assertEquals("Failed to start WeChat login", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/channels/{id}/wechat/login/status")
    inner class QueryStatusEndpoint {

        @Test
        @DisplayName("queryStatus - 返回 LOGGED_IN 状态")
        fun `queryStatus should return logged in status`() {
            val status = WechatLoginStatus(status = "LOGGED_IN", message = "Login successful")
            `when`(wechatLoginService.queryStatus(1L)).thenReturn(status)

            val result = controller.queryStatus(1L)

            assertTrue(result.isSuccess())
            assertEquals("LOGGED_IN", result.data?.status)
            assertEquals("Login successful", result.data?.message)
        }

        @Test
        @DisplayName("queryStatus - 返回 WAITING 状态")
        fun `queryStatus should return waiting status`() {
            val status = WechatLoginStatus(status = "WAITING", message = "Waiting for scan")
            `when`(wechatLoginService.queryStatus(1L)).thenReturn(status)

            val result = controller.queryStatus(1L)

            assertTrue(result.isSuccess())
            assertEquals("WAITING", result.data?.status)
        }

        @Test
        @DisplayName("queryStatus - 返回 EXPIRED 状态")
        fun `queryStatus should return expired status`() {
            val status = WechatLoginStatus(status = "EXPIRED", message = "QR code expired")
            `when`(wechatLoginService.queryStatus(1L)).thenReturn(status)

            val result = controller.queryStatus(1L)

            assertTrue(result.isSuccess())
            assertEquals("EXPIRED", result.data?.status)
        }

        @Test
        @DisplayName("queryStatus - service 抛异常时返回错误")
        fun `queryStatus should return error on service exception`() {
            `when`(wechatLoginService.queryStatus(999L))
                .thenThrow(RuntimeException("No login in progress"))

            val result = controller.queryStatus(999L)

            assertFalse(result.isSuccess())
            assertEquals("No login in progress", result.message)
        }
    }

    @Nested
    @DisplayName("POST /api/admin/channels/{id}/wechat/login/cancel")
    inner class CancelLoginEndpoint {

        @Test
        @DisplayName("cancelLogin - 取消成功")
        fun `cancelLogin should return success`() {
            val result = controller.cancelLogin(1L)

            assertTrue(result.isSuccess())
            verify(wechatLoginService).cancelLogin(1L)
        }

        @Test
        @DisplayName("cancelLogin - service 抛异常时返回错误")
        fun `cancelLogin should return error on service exception`() {
            doThrow(RuntimeException("Cancel failed")).`when`(wechatLoginService).cancelLogin(999L)

            val result = controller.cancelLogin(999L)

            assertFalse(result.isSuccess())
            assertEquals("Cancel failed", result.message)
        }

        @Test
        @DisplayName("cancelLogin - 异常无 message 时返回默认错误消息")
        fun `cancelLogin should return default error message when exception message is null`() {
            doThrow(RuntimeException()).`when`(wechatLoginService).cancelLogin(1L)

            val result = controller.cancelLogin(1L)

            assertFalse(result.isSuccess())
            assertEquals("Failed to cancel WeChat login", result.message)
        }
    }
}

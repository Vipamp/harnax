package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.entity.Channel
import com.agnetix.harnax.mapper.ChannelMapper
import com.github.wechat.ilink.sdk.ILinkClient
import com.github.wechat.ilink.sdk.ILinkClientBuilder
import com.github.wechat.ilink.sdk.core.login.LoginContext
import com.github.wechat.ilink.sdk.core.login.LoginStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.quality.Strictness
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.Base64

/**
 * WechatLoginService 单元测试
 * 通过 mockStatic(ILinkClient.builder) 模拟外部 iLink SDK 调用
 *
 * @author agnetix
 * @since 2026-06-28
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WechatLoginServiceTest {

    @Mock
    private lateinit var channelMapper: ChannelMapper

    private lateinit var service: WechatLoginService

    private val objectMapper = jacksonObjectMapper()

    @BeforeEach
    fun setUp() {
        service = createService()
    }

    @AfterEach
    fun tearDown() {
        // 释放所有进行中的登录, 避免测试之间互相污染
        service.shutdown()
    }

    private fun createService(): WechatLoginService = WechatLoginService(channelMapper)

    /**
     * 构造一个已 stub 好 executeLogin 的 ILinkClient mock
     */
    private fun mockClient(qrContent: String = "https://weixin.qq.com/x/qr-content"): ILinkClient {
        val client = mock(ILinkClient::class.java)
        `when`(client.executeLogin()).thenReturn(qrContent)
        return client
    }

    /**
     * 通过静态 mock ILinkClient.builder() 让 startLogin 使用指定的 mock client
     */
    private fun startLoginWith(channelId: Long, client: ILinkClient): String {
        val builder = mock(ILinkClientBuilder::class.java)
        `when`(builder.build()).thenReturn(client)
        return mockStatic(ILinkClient::class.java).use { mocked ->
            mocked.`when`<ILinkClientBuilder> { ILinkClient.builder() }.thenReturn(builder)
            service.startLogin(channelId)
        }
    }

    /**
     * 给 mock client 设置指定登录状态
     */
    private fun stubStatus(client: ILinkClient, status: LoginStatus.Status, errorMessage: String? = null) {
        val loginStatus = mock(LoginStatus::class.java)
        `when`(loginStatus.status).thenReturn(status)
        `when`(loginStatus.errorMessage).thenReturn(errorMessage)
        `when`(client.loginStatus).thenReturn(loginStatus)
    }

    @Nested
    @DisplayName("启动登录测试")
    inner class StartLoginTests {

        @Test
        @DisplayName("startLogin - 返回base64编码的PNG二维码DataUrl")
        fun `startLogin should return base64 png data url`() {
            // Given
            val client = mockClient("qr-text-content")

            // When
            val dataUrl = startLoginWith(1L, client)

            // Then
            assertTrue(dataUrl.startsWith("data:image/png;base64,"), "should be a png data url: $dataUrl")
            val base64 = dataUrl.removePrefix("data:image/png;base64,")
            val bytes = assertDoesNotThrow { Base64.getDecoder().decode(base64) }
            assertTrue(bytes.isNotEmpty())
            // PNG 魔数校验
            assertEquals(0x89.toByte(), bytes[0])
            assertEquals('P'.code.toByte(), bytes[1])
            verify(client, times(1)).executeLogin()
        }

        @Test
        @DisplayName("startLogin - 重新发起登录时关闭并取消上一个登录")
        fun `startLogin should cancel and close previous pending login`() {
            // Given
            val client1 = mockClient()
            val client2 = mockClient()

            // When
            startLoginWith(1L, client1)
            startLoginWith(1L, client2)

            // Then: 第一个 client 被取消并关闭, 第二个仍然存活
            verify(client1, times(1)).cancelLogin()
            verify(client1, times(1)).close()
            verify(client2, never()).close()
        }

        @Test
        @DisplayName("startLogin - 不同渠道的登录互不影响")
        fun `startLogin for different channels should not interfere`() {
            // Given
            val client1 = mockClient()
            val client2 = mockClient()

            // When
            startLoginWith(1L, client1)
            startLoginWith(2L, client2)

            // Then
            verify(client1, never()).close()
            verify(client2, never()).close()
        }
    }

    @Nested
    @DisplayName("查询登录状态测试")
    inner class QueryStatusTests {

        @Test
        @DisplayName("queryStatus - 无进行中登录返回NOT_LOGIN")
        fun `queryStatus should return NOT_LOGIN when no login in progress`() {
            // When
            val result = service.queryStatus(99L)

            // Then
            assertEquals("NOT_LOGIN", result.status)
            assertEquals("No login in progress", result.message)
        }

        @Test
        @DisplayName("queryStatus - 等待扫码返回WAITING")
        fun `queryStatus should return WAITING when waiting for scan`() {
            // Given
            val client = mockClient()
            startLoginWith(1L, client)
            stubStatus(client, LoginStatus.Status.WAITING)

            // When
            val result = service.queryStatus(1L)

            // Then
            assertEquals("WAITING", result.status)
        }

        @Test
        @DisplayName("queryStatus - 已扫码待确认返回SCANNED")
        fun `queryStatus should return SCANNED when scanned`() {
            // Given
            val client = mockClient()
            startLoginWith(1L, client)
            stubStatus(client, LoginStatus.Status.SCANNED)

            // When
            val result = service.queryStatus(1L)

            // Then
            assertEquals("SCANNED", result.status)
            // 未完成登录, client 不应被关闭
            verify(client, never()).close()
        }

        @Test
        @DisplayName("queryStatus - 登录成功持久化凭证并关闭client")
        fun `queryStatus should persist credentials and close client when logged in`() {
            // Given
            val client = mockClient()
            startLoginWith(1L, client)
            stubStatus(client, LoginStatus.Status.LOGGED_IN)
            `when`(client.loginContext).thenReturn(
                LoginContext("bot-token-abc", "user-1", "bot-1", "https://ilink.example.com"),
            )
            val channel = Channel().apply {
                id = 1L
                configJson = """{"appId":"wx123"}"""
            }
            `when`(channelMapper.selectById(1L)).thenReturn(channel)

            // When
            val result = service.queryStatus(1L)

            // Then
            assertEquals("LOGGED_IN", result.status)
            assertEquals("Login successful", result.message)

            val captor = argumentCaptor<Channel>()
            verify(channelMapper, times(1)).updateById(captor.capture())
            @Suppress("UNCHECKED_CAST")
            val config = objectMapper.readValue(captor.firstValue.configJson, Map::class.java) as Map<String, Any?>
            // 凭证已合并, 原有配置保留
            assertEquals("bot-token-abc", config["botToken"])
            assertEquals("user-1", config["userId"])
            assertEquals("bot-1", config["botId"])
            assertEquals("https://ilink.example.com", config["baseUrl"])
            assertEquals("wx123", config["appId"])

            // client 被关闭且移除, 再次查询返回 NOT_LOGIN
            verify(client, times(1)).close()
            assertEquals("NOT_LOGIN", service.queryStatus(1L).status)
        }

        @Test
        @DisplayName("queryStatus - 原configJson非法时覆盖写入新凭证")
        fun `queryStatus should overwrite invalid existing configJson`() {
            // Given
            val client = mockClient()
            startLoginWith(1L, client)
            stubStatus(client, LoginStatus.Status.LOGGED_IN)
            `when`(client.loginContext).thenReturn(
                LoginContext("bot-token-abc", "user-1", "bot-1", "https://ilink.example.com"),
            )
            val channel = Channel().apply {
                id = 1L
                configJson = "not-a-json"
            }
            `when`(channelMapper.selectById(1L)).thenReturn(channel)

            // When
            val result = service.queryStatus(1L)

            // Then
            assertEquals("LOGGED_IN", result.status)
            val captor = argumentCaptor<Channel>()
            verify(channelMapper, times(1)).updateById(captor.capture())
            @Suppress("UNCHECKED_CAST")
            val config = objectMapper.readValue(captor.firstValue.configJson, Map::class.java) as Map<String, Any?>
            assertEquals("bot-token-abc", config["botToken"])
        }

        @Test
        @DisplayName("queryStatus - 登录成功但context缺失返回ERROR")
        fun `queryStatus should return ERROR when login context missing`() {
            // Given
            val client = mockClient()
            startLoginWith(1L, client)
            stubStatus(client, LoginStatus.Status.LOGGED_IN)
            `when`(client.loginContext).thenReturn(null)

            // When
            val result = service.queryStatus(1L)

            // Then
            assertEquals("ERROR", result.status)
            assertEquals("Login context missing", result.message)
            verify(channelMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("queryStatus - 登录成功但渠道不存在抛出异常")
        fun `queryStatus should throw when channel not found on persist`() {
            // Given
            val client = mockClient()
            startLoginWith(1L, client)
            stubStatus(client, LoginStatus.Status.LOGGED_IN)
            `when`(client.loginContext).thenReturn(
                LoginContext("bot-token-abc", "user-1", "bot-1", "https://ilink.example.com"),
            )
            `when`(channelMapper.selectById(1L)).thenReturn(null)

            // When & Then
            assertThrows<IllegalStateException> {
                service.queryStatus(1L)
            }
            verify(channelMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("queryStatus - 二维码过期返回EXPIRED并释放client")
        fun `queryStatus should return EXPIRED and release client when qr expired`() {
            // Given
            val client = mockClient()
            startLoginWith(1L, client)
            stubStatus(client, LoginStatus.Status.EXPIRED)

            // When
            val result = service.queryStatus(1L)

            // Then
            assertEquals("EXPIRED", result.status)
            verify(client, times(1)).close()
            // 已释放, 再次查询返回 NOT_LOGIN
            assertEquals("NOT_LOGIN", service.queryStatus(1L).status)
        }

        @Test
        @DisplayName("queryStatus - 登录错误返回ERROR及错误信息")
        fun `queryStatus should return ERROR with error message`() {
            // Given
            val client = mockClient()
            startLoginWith(1L, client)
            stubStatus(client, LoginStatus.Status.ERROR, "network broken")

            // When
            val result = service.queryStatus(1L)

            // Then
            assertEquals("ERROR", result.status)
            assertEquals("network broken", result.message)
            verify(client, times(1)).close()
        }

        @Test
        @DisplayName("queryStatus - 登录错误无错误信息时使用默认文案")
        fun `queryStatus should return default error message when error message is null`() {
            // Given
            val client = mockClient()
            startLoginWith(1L, client)
            stubStatus(client, LoginStatus.Status.ERROR, null)

            // When
            val result = service.queryStatus(1L)

            // Then
            assertEquals("ERROR", result.status)
            assertEquals("Login error", result.message)
        }
    }

    @Nested
    @DisplayName("取消登录测试")
    inner class CancelLoginTests {

        @Test
        @DisplayName("cancelLogin - 取消进行中的登录并关闭client")
        fun `cancelLogin should cancel and close pending client`() {
            // Given
            val client = mockClient()
            startLoginWith(1L, client)

            // When
            service.cancelLogin(1L)

            // Then
            verify(client, times(1)).cancelLogin()
            verify(client, times(1)).close()
            assertEquals("NOT_LOGIN", service.queryStatus(1L).status)
        }

        @Test
        @DisplayName("cancelLogin - 无进行中登录时不抛异常")
        fun `cancelLogin should not throw when no pending login`() {
            assertDoesNotThrow {
                service.cancelLogin(42L)
            }
        }

        @Test
        @DisplayName("cancelLogin - client关闭抛异常时被吞掉")
        fun `cancelLogin should swallow client close errors`() {
            // Given
            val client = mockClient()
            org.mockito.Mockito.doThrow(RuntimeException("cancel failed")).`when`(client).cancelLogin()
            startLoginWith(1L, client)

            // When & Then
            assertDoesNotThrow {
                service.cancelLogin(1L)
            }
            verify(client, times(1)).close()
        }
    }

    @Nested
    @DisplayName("关闭服务测试")
    inner class ShutdownTests {

        @Test
        @DisplayName("shutdown - 释放全部进行中的登录")
        fun `shutdown should release all pending logins`() {
            // Given
            val client1 = mockClient()
            val client2 = mockClient()
            startLoginWith(1L, client1)
            startLoginWith(2L, client2)

            // When
            service.shutdown()

            // Then
            verify(client1, times(1)).cancelLogin()
            verify(client1, times(1)).close()
            verify(client2, times(1)).cancelLogin()
            verify(client2, times(1)).close()
            assertEquals("NOT_LOGIN", service.queryStatus(1L).status)
            assertEquals("NOT_LOGIN", service.queryStatus(2L).status)
        }

        @Test
        @DisplayName("shutdown - 无进行中登录时不抛异常")
        fun `shutdown should not throw when nothing pending`() {
            assertDoesNotThrow {
                service.shutdown()
            }
            verify(channelMapper, never()).selectById(anyLong())
        }
    }
}

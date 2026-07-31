package com.agnetix.harnax.admin.controller.mp

import com.agnetix.harnax.admin.dto.CaptchaResponse
import com.agnetix.harnax.admin.dto.LoginRequest
import com.agnetix.harnax.admin.dto.mp.MpLoginResponse
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.AuthService
import com.agnetix.harnax.admin.service.CaptchaService
import com.agnetix.harnax.admin.service.mp.MpAuthService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.quality.Strictness

/**
 * MpAuthController 单元测试
 * 直接实例化 Controller + Mock Service,直调方法断言 ResultVo
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MpAuthControllerTest {

    @Mock
    private lateinit var authService: AuthService

    @Mock
    private lateinit var captchaService: CaptchaService

    @Mock
    private lateinit var mpAuthService: MpAuthService

    @InjectMocks
    private lateinit var controller: MpAuthController

    @Nested
    @DisplayName("POST /api/admin/mp/auth/login")
    inner class LoginEndpoint {

        @Test
        @DisplayName("login - 登录成功返回 JWT 与 routerApiKey")
        fun `login should return token and router api key`() {
            val request = LoginRequest(username = "mpuser", password = "password123")
            val response = MpLoginResponse(
                accessToken = "jwt-token",
                routerApiKey = "hnx_sk_live_xxx",
                routerUrl = "http://localhost:8081",
                expiresIn = 7200L,
                userInfo = MpLoginResponse.UserInfo(userId = 1L, username = "mpuser", nickname = "MP User"),
            )
            `when`(mpAuthService.login(any())).thenReturn(response)

            val result = controller.login(request)

            assertTrue(result.isSuccess())
            assertEquals("jwt-token", result.data?.accessToken)
            assertEquals("hnx_sk_live_xxx", result.data?.routerApiKey)
            assertEquals("http://localhost:8081", result.data?.routerUrl)
            assertEquals(7200L, result.data?.expiresIn)
            assertEquals("mpuser", result.data?.userInfo?.username)
        }

        @Test
        @DisplayName("login - 密码错误时异常向上传播")
        fun `login should propagate exception on invalid credentials`() {
            val request = LoginRequest(username = "mpuser", password = "wrong")
            `when`(mpAuthService.login(any())).thenThrow(BizException("Invalid credentials"))

            assertThrows<BizException> {
                controller.login(request)
            }
        }

        @Test
        @DisplayName("login - 用户不存在时异常向上传播")
        fun `login should propagate exception when user not found`() {
            val request = LoginRequest(username = "nobody", password = "password123")
            `when`(mpAuthService.login(any())).thenThrow(BizException("User not found"))

            assertThrows<BizException> {
                controller.login(request)
            }
        }
    }

    @Nested
    @DisplayName("POST /api/admin/mp/auth/logout")
    inner class LogoutEndpoint {

        @Test
        @DisplayName("logout - 登出成功")
        fun `logout should return success`() {
            val result = controller.logout()

            assertTrue(result.isSuccess())
            verify(authService).logout()
        }

        @Test
        @DisplayName("logout - service 抛异常时异常向上传播")
        fun `logout should propagate service exception`() {
            doThrow(RuntimeException("Logout failed")).`when`(authService).logout()

            assertThrows<RuntimeException> {
                controller.logout()
            }
        }
    }

    @Nested
    @DisplayName("GET /api/admin/mp/auth/captcha")
    inner class CaptchaEndpoint {

        @Test
        @DisplayName("getCaptcha - 返回验证码图片")
        fun `getCaptcha should return captcha image`() {
            val captcha = CaptchaResponse(
                imageBase64 = "data:image/png;base64,iVBORw0KG...",
                captchaKey = "uuid-key",
                expiresIn = 300L,
            )
            `when`(captchaService.generateCaptcha()).thenReturn(captcha)

            val result = controller.getCaptcha()

            assertTrue(result.isSuccess())
            assertEquals("uuid-key", result.data?.captchaKey)
            assertEquals(300L, result.data?.expiresIn)
            assertNotNull(result.data?.imageBase64)
        }

        @Test
        @DisplayName("getCaptcha - service 抛异常时返回错误")
        fun `getCaptcha should return error on service exception`() {
            `when`(captchaService.generateCaptcha()).thenThrow(RuntimeException("Image generation failed"))

            val result = controller.getCaptcha()

            assertFalse(result.isSuccess())
            assertEquals("Image generation failed", result.message)
        }

        @Test
        @DisplayName("getCaptcha - 异常无 message 时返回默认错误消息")
        fun `getCaptcha should return default error message when exception message is null`() {
            `when`(captchaService.generateCaptcha()).thenThrow(RuntimeException())

            val result = controller.getCaptcha()

            assertFalse(result.isSuccess())
            assertEquals("Failed to get captcha", result.message)
        }
    }
}

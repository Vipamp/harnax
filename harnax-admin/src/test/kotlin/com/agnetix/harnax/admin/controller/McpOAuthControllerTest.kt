package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.McpOAuthAuthorizeResponse
import com.agnetix.harnax.admin.dto.McpOAuthClientRequest
import com.agnetix.harnax.admin.dto.McpOAuthDiscoveryResponse
import com.agnetix.harnax.admin.dto.McpOAuthExchangeRequest
import com.agnetix.harnax.admin.dto.McpOAuthExchangeResponse
import com.agnetix.harnax.admin.dto.McpOAuthRevokeResponse
import com.agnetix.harnax.admin.dto.McpOAuthStatusResponse
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.McpOAuthService
import com.agnetix.harnax.admin.service.McpOAuthUserService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
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
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.quality.Strictness
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.QueryTimeoutException
import java.time.LocalDateTime

/**
 * McpOAuthController 单元测试
 *
 * The service is where the discovery logic lives and is covered on its own; what is pinned here is the
 * answer an administrator actually reads. Both failures of this feature arrive as a long sentence the
 * service wrote to be actionable ("run discovery first", "set oauthConfig.authorizationServer"), so a
 * controller that wrapped them in a generic prefix would throw away the only text that says what to do.
 *
 * The per-user endpoints are pinned for the same reason and for one more: [authorizeUrl] hands back a
 * URL a browser has to follow verbatim, [exchange] is where that browser's answer comes back to and
 * must not become a generic prefix, and [revoke] says out loud whether the authorization server took
 * the token back, which is the sentence a user acts on.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpOAuthControllerTest {

    @Mock
    private lateinit var mcpOAuthService: McpOAuthService

    @Mock
    private lateinit var mcpOAuthUserService: McpOAuthUserService

    @InjectMocks
    private lateinit var controller: McpOAuthController

    private val discovered = McpOAuthDiscoveryResponse(
        issuer = "https://as.example.com",
        issuerSource = "PROTECTED_RESOURCE",
        authorizationEndpoint = "https://as.example.com/authorize",
        tokenEndpoint = "https://as.example.com/token",
        scopesSupported = listOf("mcp:read"),
        clientId = "harnax-web",
        clientSecretPresent = true,
        callbackUrl = "http://localhost:8000/mcp/oauth/callback",
    )

    @Nested
    @DisplayName("discover")
    inner class DiscoverTests {

        @Test
        @DisplayName("discover - 成功时把写入的注册快照原样回显")
        fun `a successful run echoes the stored registration`() {
            `when`(mcpOAuthService.discover(1L)).thenReturn(discovered)

            val result = controller.discover(1L)

            assertEquals(200, result.code)
            assertSame(discovered, result.data)
        }

        @Test
        @DisplayName("discover - BizException 的理由不被前缀包住")
        fun `a discovery failure answers with the reason the service composed`() {
            `when`(mcpOAuthService.discover(1L)).thenThrow(
                BizException("Cannot locate the authorization server for https://mcp.example.com/mcp: answered 404"),
            )

            val result = controller.discover(1L)

            assertEquals(500, result.code)
            assertEquals(
                "Cannot locate the authorization server for https://mcp.example.com/mcp: answered 404",
                result.message,
            )
        }

        @Test
        @DisplayName("discover - 唯一键冲突不 quoting 库表细节")
        fun `a concurrent registration collision does not quote the schema`() {
            `when`(mcpOAuthService.discover(1L)).thenThrow(
                DuplicateKeyException(
                    "Duplicate entry '1-https://as.example.com-' for key " +
                        "'mcp_oauth_client.uk_mcp_oauth_client_tenant_issuer_client'",
                ),
            )

            val result = controller.discover(1L)

            assertEquals(
                "An OAuth client registration for this authorization server already exists, please run discovery again",
                result.message,
            )
            assertFalse(result.message.contains("Duplicate entry"), result.message)
        }

        @Test
        @DisplayName("discover - 无 message 的异常落到前缀文案")
        fun `an exception with no message falls back to the prefix text`() {
            `when`(mcpOAuthService.discover(1L)).thenThrow(RuntimeException())

            val result = controller.discover(1L)

            assertEquals("Failed to discover OAuth metadata", result.message)
        }
    }

    @Nested
    @DisplayName("saveClient")
    inner class SaveClientTests {

        @Test
        @DisplayName("saveClient - 请求体不经过任何改写就交给服务")
        fun `the request reaches the service unchanged`() {
            val request = McpOAuthClientRequest(clientId = "harnax-web", clientSecret = "s3cret")
            `when`(mcpOAuthService.saveClient(2L, request)).thenReturn(discovered)

            val result = controller.saveClient(2L, request)

            assertEquals(200, result.code)
            assertTrue(result.data!!.clientSecretPresent)
            val captor = argumentCaptor<McpOAuthClientRequest>()
            verify(mcpOAuthService).saveClient(eq(2L), captor.capture())
            assertSame(request, captor.firstValue)
        }

        @Test
        @DisplayName("saveClient - 「先跑发现」这条指引完整送到页面")
        fun `the run-discovery-first guidance survives the controller`() {
            `when`(mcpOAuthService.saveClient(eq(3L), org.mockito.kotlin.any())).thenThrow(
                BizException(
                    "The authorization server of this MCP server is not known yet: run discovery first " +
                        "(POST /api/admin/mcp/3/oauth/discover), or set oauthConfig.authorizationServer",
                ),
            )

            val result = controller.saveClient(3L, McpOAuthClientRequest(clientId = "harnax-web"))

            assertTrue(result.message.contains("run discovery first"), result.message)
            assertTrue(result.message.contains("oauthConfig.authorizationServer"), result.message)
        }
    }

    @Nested
    @DisplayName("authorizeUrl")
    inner class AuthorizeUrlTests {

        private val authorize = McpOAuthAuthorizeResponse(
            authorizeUrl = "https://as.example.com/authorize?response_type=code&state=abc",
            issuer = "https://as.example.com",
            scopes = listOf("mcp:read"),
            expiresIn = 300,
        )

        @Test
        @DisplayName("authorizeUrl - 链接不经过任何改写就交给浏览器")
        fun `the URL is handed over unchanged`() {
            `when`(mcpOAuthUserService.authorizeUrl(1L, null)).thenReturn(authorize)

            val result = controller.authorizeUrl(1L, null)

            assertEquals(200, result.code)
            assertSame(authorize, result.data)
        }

        @Test
        @DisplayName("authorizeUrl - scope 参数原样下传")
        fun `the scope argument is passed through as typed`() {
            `when`(mcpOAuthUserService.authorizeUrl(2L, "mcp:read mcp:write")).thenReturn(authorize)

            controller.authorizeUrl(2L, "mcp:read mcp:write")

            verify(mcpOAuthUserService).authorizeUrl(2L, "mcp:read mcp:write")
        }

        @Test
        @DisplayName("authorizeUrl - 缺 client_id 时把补救入口送到页面")
        fun `an incomplete setup keeps the remedy the service named`() {
            `when`(mcpOAuthUserService.authorizeUrl(eq(3L), org.mockito.kotlin.anyOrNull())).thenThrow(
                BizException("No client_id is registered for https://as.example.com: save the OAuth client (POST /api/admin/mcp/3/oauth/client)"),
            )

            val result = controller.authorizeUrl(3L, null)

            assertEquals(500, result.code)
            assertEquals(
                "No client_id is registered for https://as.example.com: save the OAuth client (POST /api/admin/mcp/3/oauth/client)",
                result.message,
            )
        }
    }

    @Nested
    @DisplayName("exchange")
    inner class ExchangeTests {

        @Test
        @DisplayName("exchange - code 与 state 不改写地交给服务,成功回答原样送出")
        fun `the posted code and state reach the service unchanged`() {
            val request = McpOAuthExchangeRequest(code = "the-code", state = "the-state")
            val answer = McpOAuthExchangeResponse(
                authorized = true,
                message = "This MCP server is authorized for your account",
                scopes = listOf("mcp:read"),
                accessExpiresAt = LocalDateTime.now().plusHours(1),
            )
            `when`(mcpOAuthUserService.exchange(request)).thenReturn(answer)

            val result = controller.exchange(request)

            assertEquals(200, result.code)
            assertSame(answer, result.data)
            assertTrue(result.data!!.authorized)
            assertEquals(listOf("mcp:read"), result.data!!.scopes)
        }

        @Test
        @DisplayName("exchange - 没有登录态时服务那句拒绝不被前缀包住")
        fun `the reason the service gave reaches the page as written`() {
            `when`(mcpOAuthUserService.exchange(org.mockito.kotlin.any())).thenThrow(
                BizException("Authorization is per user and this request carries no user identity"),
            )

            val result = controller.exchange(McpOAuthExchangeRequest(code = "c", state = "s"))

            assertEquals(500, result.code)
            assertEquals("Authorization is per user and this request carries no user identity", result.message)
        }

        @Test
        @DisplayName("exchange - 无 message 的异常落到前缀文案")
        fun `an exception with no message falls back to the prefix text`() {
            `when`(mcpOAuthUserService.exchange(org.mockito.kotlin.any())).thenThrow(RuntimeException())

            assertEquals("Failed to complete the authorization", controller.exchange(McpOAuthExchangeRequest()).message)
        }
    }

    @Nested
    @DisplayName("status")
    inner class StatusTests {

        @Test
        @DisplayName("status - 没授权过是正常回答而不是错误")
        fun `having no grant is an answer not a failure`() {
            `when`(mcpOAuthUserService.status(1L)).thenReturn(McpOAuthStatusResponse(authorized = false))

            val result = controller.status(1L)

            assertEquals(200, result.code)
            assertFalse(result.data!!.authorized)
        }

        @Test
        @DisplayName("status - 服务不可用时落到前缀文案")
        fun `an unexpected failure answers with the prefix text`() {
            `when`(mcpOAuthUserService.status(1L)).thenThrow(RuntimeException())

            assertEquals("Failed to read the OAuth status", controller.status(1L).message)
        }
    }

    @Nested
    @DisplayName("revoke")
    inner class RevokeTests {

        @Test
        @DisplayName("revoke - 上游有没有真的撤销这句话不能被改写")
        fun `the honest split between local and upstream is returned as written`() {
            val response = McpOAuthRevokeResponse(
                revoked = true,
                upstreamRevoked = false,
                message = "The local copy is cleared, but the authorization server did not accept the revocation",
            )
            `when`(mcpOAuthUserService.revoke(1L)).thenReturn(response)

            val result = controller.revoke(1L)

            assertSame(response, result.data)
            assertFalse(result.data!!.upstreamRevoked)
        }

        @Test
        @DisplayName("revoke - 数据库错误不透传原文")
        fun `a database failure does not reach the page in its own words`() {
            `when`(mcpOAuthUserService.revoke(1L)).thenThrow(
                QueryTimeoutException("SELECT * FROM mcp_user_credential WHERE tenant_id = 1 timed out"),
            )

            val result = controller.revoke(1L)

            assertEquals("Database operation failed, please check the submitted values and try again", result.message)
            assertFalse(result.message.contains("mcp_user_credential"), result.message)
        }
    }
}

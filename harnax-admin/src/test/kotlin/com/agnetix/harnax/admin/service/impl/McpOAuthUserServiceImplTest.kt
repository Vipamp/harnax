package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.McpOAuthConfig
import com.agnetix.harnax.admin.dto.McpOAuthExchangeRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.McpServerService
import com.agnetix.harnax.admin.util.AesUtil
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.McpOAuthStateStore
import com.agnetix.harnax.admin.util.PendingAuthorization
import com.agnetix.harnax.admin.util.RemoteFetch
import com.agnetix.harnax.admin.util.RemoteFetchException
import com.agnetix.harnax.admin.util.RemoteJsonFetcher
import com.agnetix.harnax.entity.McpAuthTypes
import com.agnetix.harnax.entity.McpOauthClient
import com.agnetix.harnax.entity.McpServer
import com.agnetix.harnax.entity.McpUserCredential
import com.agnetix.harnax.mapper.McpOauthClientMapper
import com.agnetix.harnax.mapper.McpUserCredentialMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.Base64

/**
 * McpOAuthUserServiceImpl Unit Tests
 *
 * Outbound HTTP is stubbed at [RemoteJsonFetcher] and the pending states use the real
 * [McpOAuthStateStore], because the two things worth pinning about this flow are both about handoffs:
 * the verifier that goes out and the one that redeems the code are the same value, and a `state` is
 * good for exactly one exchange whoever reaches it first.
 *
 * @author agnetix
 * @since 2026-09-11
 */
class McpOAuthUserServiceImplTest {

    private val jwtUtil = mock<JwtUtil>()
    private val mcpServerService = mock<McpServerService>()
    private val clientMapper = mock<McpOauthClientMapper>()
    private val credentialMapper = mock<McpUserCredentialMapper>()
    private val aesUtil = mock<AesUtil>()
    private val fetcher = mock<RemoteJsonFetcher>()
    private val stateStore = McpOAuthStateStore()
    private val objectMapper = jacksonObjectMapper()

    private lateinit var service: McpOAuthUserServiceImpl

    private val issuer = "https://as.example.com"
    private val mcpUrl = "http://mcp.example.com:3000/mcp"
    private val authorizeEndpoint = "$issuer/authorize"
    private val tokenUrl = "$issuer/token"
    private val revokeUrl = "$issuer/revoke"
    private val callbackUrl = "http://localhost:8000/mcp/oauth/callback"

    /** Answers keyed by URL, so a case can say exactly which endpoint has to be hit. */
    private val postAnswers = mutableMapOf<String, RemoteFetch>()

    private val mcpId = 1L

    @BeforeEach
    fun setUp() {
        val request = MockHttpServletRequest()
        request.addHeader("Authorization", "Bearer mock-token")
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
        whenever(jwtUtil.validateToken(any())).thenReturn(true)
        whenever(jwtUtil.getUserIdFromToken(any())).thenReturn(7L)
        whenever(jwtUtil.getUsernameFromToken(any())).thenReturn("tester")
        // The real prefix rule of AesUtil is not the point here; that a value went through the
        // encryptor at all is, and "enc:" makes both directions readable in an assertion.
        whenever(aesUtil.encrypt(any())).thenAnswer { "enc:" + it.getArgument<String>(0) }
        whenever(aesUtil.decrypt(any())).thenAnswer { it.getArgument<String>(0).removePrefix("enc:") }
        whenever(fetcher.postForm(any(), any())).thenAnswer { invocation ->
            postAnswers[invocation.getArgument<String>(0)] ?: RemoteFetch(502, null, null)
        }
        service = McpOAuthUserServiceImpl(
            jwtUtil,
            mcpServerService,
            clientMapper,
            credentialMapper,
            aesUtil,
            fetcher,
            stateStore,
            objectMapper,
        )
    }

    @AfterEach
    fun tearDown() {
        RequestContextHolder.resetRequestAttributes()
    }

    @Nested
    @DisplayName("authorizeUrl")
    inner class AuthorizeUrlTests {

        @Test
        @DisplayName("授权链接带 PKCE、resource 与登记的回调地址")
        fun `the authorization request carries PKCE, the resource and the registered redirect_uri`() {
            configured()
            registration()

            val response = service.authorizeUrl(mcpId, null)

            val params = queryOf(response.authorizeUrl)
            assertEquals(authorizeEndpoint, response.authorizeUrl.substringBefore('?'))
            assertEquals("code", params["response_type"])
            assertEquals("harnax-web", params["client_id"])
            // The redirect_uri is the one on the registration, not one built from this request: the
            // AS compares it byte-exact, and the row is where the administrator typed it.
            assertEquals(callbackUrl, params["redirect_uri"])
            assertEquals("S256", params["code_challenge_method"])
            assertEquals(43, (params["code_challenge"] ?: "").length)
            assertEquals(mcpUrl, params["resource"])
            assertEquals("mcp:read", params["scope"])
            assertEquals(32, (params["state"] ?: "").length)
            assertEquals(issuer, response.issuer)
            assertEquals(listOf("mcp:read"), response.scopes)
            assertEquals(McpOAuthStateStore.TTL.seconds, response.expiresIn)
            assertEquals(1, stateStore.size(), "the pending state must be held for the callback")
        }

        @Test
        @DisplayName("入参 scope 覆盖服务端配置且去重")
        fun `a scope argument overrides the configured scopes`() {
            configured()
            registration()

            val response = service.authorizeUrl(mcpId, "mcp:write mcp:read,mcp:write")

            assertEquals("mcp:write mcp:read", queryOf(response.authorizeUrl)["scope"])
            assertEquals(listOf("mcp:write", "mcp:read"), response.scopes)
        }

        @Test
        @DisplayName("scope 参数宽过列宽就拒绝,不是截断存下")
        fun `a scope list too wide for the column is refused`() {
            configured()
            registration()

            val error = assertThrows(BizException::class.java) { service.authorizeUrl(mcpId, "mcp:" + "x".repeat(600)) }

            assertTrue(error.message!!.contains("too wide to record"), error.message)
            assertEquals(0, stateStore.size(), "a refusal must not leave a pending state behind")
        }

        @Test
        @DisplayName("关掉 resource indicator 就不带 resource 参数")
        fun `turning the resource indicator off drops the resource parameter`() {
            configured(config = McpOAuthConfig(authorizationServer = issuer, scopes = listOf("mcp:read"), resourceIndicator = false))
            registration()

            val params = queryOf(service.authorizeUrl(mcpId, null).authorizeUrl)

            assertFalse(params.containsKey("resource"), params.toString())
        }

        @Test
        @DisplayName("配了 audience 才带 audience 参数")
        fun `an audience is forwarded only when configured`() {
            configured(config = McpOAuthConfig(authorizationServer = issuer, audience = "harnax"))
            registration()

            assertEquals("harnax", queryOf(service.authorizeUrl(mcpId, null).authorizeUrl)["audience"])
        }

        @Test
        @DisplayName("发现出的 issuer 带斜杠也能命中注册行")
        fun `a trailing slash on the configured issuer still finds the registration`() {
            configured(config = McpOAuthConfig(authorizationServer = "$issuer/"))
            registration()

            assertEquals(issuer, service.authorizeUrl(mcpId, null).issuer)
            val captor = argumentCaptor<String>()
            verify(clientMapper).selectByTenantAndIssuer(eq(1L), captor.capture())
            assertEquals(issuer, captor.firstValue, "the registration is looked up by the slash-free issuer")
        }

        @Test
        @DisplayName("client_id 还没登记就拒绝发起")
        fun `an unregistered client_id is refused`() {
            configured()
            registration(clientId = "")

            val error = assertThrows(BizException::class.java) { service.authorizeUrl(mcpId, null) }

            assertTrue(error.message!!.contains("save the OAuth client"), error.message)
            assertEquals(0, stateStore.size(), "a refusal must not leave a pending state behind")
        }

        @Test
        @DisplayName("没跑过发现就拒绝,并给出 discover 路径")
        fun `a setup with no discovered issuer is refused with the path to fix it`() {
            configured(config = McpOAuthConfig())

            val error = assertThrows(BizException::class.java) { service.authorizeUrl(mcpId, null) }

            assertTrue(error.message!!.contains("POST /api/admin/mcp/$mcpId/oauth/discover"), error.message)
        }

        @Test
        @DisplayName("发现出的行没有 authorization_endpoint 时指向发现")
        fun `a registration without an authorization endpoint says so`() {
            configured()
            registration(authorizationEndpoint = null)

            val error = assertThrows(BizException::class.java) { service.authorizeUrl(mcpId, null) }

            assertTrue(error.message!!.contains("no recorded authorization endpoint"), error.message)
        }

        @Test
        @DisplayName("非 OAuth 服务不参与授权码流程")
        fun `a server that is not configured for OAuth is refused`() {
            configured(authType = McpAuthTypes.STATIC_HEADER)

            val error = assertThrows(BizException::class.java) { service.authorizeUrl(mcpId, null) }

            assertTrue(error.message!!.contains("auth type ${McpAuthTypes.STATIC_HEADER}"), error.message)
        }

        @Test
        @DisplayName("取不到用户身份就不发起:授权是逐人的")
        fun `a request with no user identity is refused`() {
            configured()
            whenever(jwtUtil.validateToken(any())).thenReturn(false)

            val error = assertThrows(BizException::class.java) { service.authorizeUrl(mcpId, null) }

            assertTrue(error.message!!.contains("no user identity"), error.message)
        }

        @Test
        @DisplayName("待授权状态超过上限时明确拒绝而不是静默丢弃")
        fun `a full state store is refused out loud`() {
            configured()
            registration()
            // Other users' entries: the per-user cap below would fire first on the caller's own, and
            // then this case would no longer be about the shared budget it names.
            repeat(McpOAuthStateStore.MAX_PENDING) {
                stateStore.put("full-$it", pending(it.toString(), userId = 100L + it))
            }

            val error = assertThrows(BizException::class.java) { service.authorizeUrl(mcpId, null) }

            assertTrue(error.message!!.contains("Too many authorizations"), error.message)
            assertEquals(McpOAuthStateStore.MAX_PENDING, stateStore.size(), "a refusal leaves nothing behind")
        }

        @Test
        @DisplayName("一个用户占不满整份待授权预算")
        fun `one user cannot take the whole pending budget`() {
            configured()
            registration()
            repeat(McpOAuthStateStore.MAX_PER_USER) {
                stateStore.put("mine-$it", pending("mine-$it"))
            }

            val error = assertThrows(BizException::class.java) { service.authorizeUrl(mcpId, null) }

            // Without this cap one authenticated script fills the shared store and nobody else can
            // start an authorization until the entries age out.
            assertTrue(error.message!!.contains("already have"), error.message)
            assertEquals(McpOAuthStateStore.MAX_PER_USER, stateStore.size(), "a refusal leaves nothing behind")
            verify(fetcher, never()).postForm(any(), any())
        }
    }

    @Nested
    @DisplayName("exchange")
    inner class ExchangeTests {

        @Test
        @DisplayName("换票:verifier 与发出的 challenge 对得上,密文落库")
        fun `the exchange redeems the code with the verifier that matches the challenge it sent`() {
            configured()
            registration()
            val params = queryOf(service.authorizeUrl(mcpId, null).authorizeUrl)
            postAnswers[tokenUrl] = jsonFetch(
                """{"access_token":"at-1","refresh_token":"rt-1","scope":"mcp:read","expires_in":3600}""",
            )

            val result = service.exchange(presented(code = "the-code", state = params["state"]))

            assertTrue(result.authorized, result.message)
            val form = capturedForm(tokenUrl)
            assertEquals("authorization_code", form["grant_type"])
            assertEquals("the-code", form["code"])
            assertEquals(callbackUrl, form["redirect_uri"])
            assertEquals("harnax-web", form["client_id"])
            assertEquals("s3cret", form["client_secret"], "the stored secret is decrypted for the exchange")
            assertEquals(mcpUrl, form["resource"])
            val postedVerifier = form["code_verifier"] ?: error("no code_verifier was posted")
            assertEquals(params["code_challenge"], challengeOf(postedVerifier))

            val stored = argumentOfInsert()
            assertEquals("enc:at-1", stored.accessTokenEnc)
            assertEquals("enc:rt-1", stored.refreshTokenEnc)
            assertEquals(listOf("mcp:read"), stored.scopes?.split(','))
            assertEquals(McpUserCredential.STATUS_ACTIVE, stored.status)
            assertEquals(1L, stored.tenantId)
            assertEquals(7L, stored.userId, "the grant belongs to the user who asked for the URL")
            assertEquals(mcpId, stored.mcpId)
            assertNull(stored.lastError)
            assertNotNull(stored.accessExpiresAt)
            assertEquals(0, stateStore.size(), "the state is consumed either way")
            // What the page learns is what was granted and when it expires - never the token.
            assertEquals(listOf("mcp:read"), result.scopes)
            assertNotNull(result.accessExpiresAt)
        }

        @Test
        @DisplayName("已有的行走更新,且 refresh_token 整体替换")
        fun `an existing row is updated and a previous refresh token never survives`() {
            configured()
            registration()
            val params = queryOf(service.authorizeUrl(mcpId, null).authorizeUrl)
            whenever(credentialMapper.selectByUserAndMcp(1L, 7L, mcpId)).thenReturn(
                credential(refreshTokenEnc = "enc:rt-old", accessTokenEnc = "enc:at-old"),
            )
            postAnswers[tokenUrl] = jsonFetch("""{"access_token":"at-2"}""")

            val result = service.exchange(presented(code = "c", state = params["state"]))

            assertTrue(result.authorized, result.message)
            verify(credentialMapper, never()).insert(any())
            val updated = argumentOfUpdate()
            assertEquals("enc:at-2", updated.accessTokenEnc)
            // The AS stopped handing one out, so the old refresh token is dropped: keeping it would
            // leave a way to mint tokens whose scopes nobody just agreed to.
            assertNull(updated.refreshTokenEnc)
            assertEquals(listOf("mcp:read"), updated.scopes?.split(','), "requested scopes are the fallback")
        }

        @Test
        @DisplayName("AS 发的 scope 宽过列宽:整份不记,并说明为什么")
        fun `a granted scope list too wide for the column is not recorded at all`() {
            configured()
            registration()
            val params = queryOf(service.authorizeUrl(mcpId, null).authorizeUrl)
            val wide = (1..50).joinToString(",") { "resource.scope.number$it" }
            postAnswers[tokenUrl] = jsonFetch("""{"access_token":"at-1","scope":"$wide","expires_in":3600}""")

            val result = service.exchange(presented(code = "c", state = params["state"]))

            assertTrue(result.authorized, result.message)
            val stored = argumentOfInsert()
            // A cut-off list would read as "these are the granted scopes" with a last entry nobody
            // granted, so the whole list is left out and the row says why.
            assertNull(stored.scopes)
            assertTrue(stored.lastError!!.contains("is not recorded here"), stored.lastError)
            assertTrue(stored.lastError!!.contains("${wide.length}"), "the note names how wide the answer was")
            assertFalse(stored.lastError!!.contains("resource.scope.number1"), stored.lastError)
            assertEquals(McpUserCredential.STATUS_ACTIVE, stored.status, "the grant itself is usable")
            assertTrue(result.scopes.isEmpty(), "the answer reports what was recorded, which is nothing")
        }

        @Test
        @DisplayName("AS 不回 scope:页面读到的与库里记下的是同一份")
        fun `an answer that omits the optional scope field reports the list the row holds`() {
            configured()
            registration()
            val params = queryOf(service.authorizeUrl(mcpId, null).authorizeUrl)
            // RFC 6749 §5.1 makes `scope` optional: an AS that grants what was asked for may omit it,
            // and the row then records the requested list.
            postAnswers[tokenUrl] = jsonFetch("""{"access_token":"at-1","expires_in":3600}""")

            val result = service.exchange(presented(code = "c", state = params["state"]))

            assertTrue(result.authorized, result.message)
            assertEquals(listOf("mcp:read"), argumentOfInsert().scopes?.split(','))
            assertEquals(listOf("mcp:read"), result.scopes, "an empty answer here would contradict the panel a moment later")
        }

        @Test
        @DisplayName("两次换票撞了唯一键:这次的票写到先落库的那行")
        fun `a duplicate key race writes onto the row that won instead of failing`() {
            configured()
            registration()
            val params = queryOf(service.authorizeUrl(mcpId, null).authorizeUrl)
            // First lookup says the user has no row yet; the one after the collision finds the row the
            // other request created in the meantime.
            whenever(credentialMapper.selectByUserAndMcp(1L, 7L, mcpId))
                .thenReturn(null)
                .thenReturn(credential().apply { id = 31L })
            whenever(credentialMapper.insert(any())).thenThrow(
                DuplicateKeyException("Duplicate entry for key 'uk_mcp_user_credential_tenant_user_mcp'"),
            )
            postAnswers[tokenUrl] = jsonFetch("""{"access_token":"at-2","scope":"mcp:read"}""")

            val result = service.exchange(presented(code = "c", state = params["state"]))

            assertTrue(result.authorized, result.message)
            val updated = argumentOfUpdate()
            assertEquals(31L, updated.id, "the token of this exchange goes on the row that won the race")
            assertEquals("enc:at-2", updated.accessTokenEnc)
            assertEquals(McpUserCredential.STATUS_ACTIVE, updated.status)
            assertEquals(1L, updated.tenantId)
            assertEquals(7L, updated.userId)
        }

        @Test
        @DisplayName("state 不认识时不查服务也不打 AS")
        fun `an unknown state is refused before any request is made`() {
            configured()
            registration()

            val result = service.exchange(presented(code = "c", state = "never-issued"))

            assertFalse(result.authorized)
            assertTrue(result.message.contains("unknown or has expired"), result.message)
            verify(mcpServerService, never()).getMcpServer(any())
            verify(fetcher, never()).postForm(any(), any())
            verifyNoCredentialWrites()
        }

        @Test
        @DisplayName("state 只能用一次")
        fun `a state cannot be replayed`() {
            configured()
            registration()
            val params = queryOf(service.authorizeUrl(mcpId, null).authorizeUrl)
            postAnswers[tokenUrl] = jsonFetch("""{"access_token":"at-1"}""")

            assertTrue(service.exchange(presented(code = "c", state = params["state"])).authorized)
            val second = service.exchange(presented(code = "c", state = params["state"]))

            assertFalse(second.authorized)
            assertTrue(second.message.contains("unknown or has expired"), second.message)
        }

        @Test
        @DisplayName("过期的 state 即使还在表里也不给用")
        fun `an expired state is not honoured`() {
            configured()
            registration()
            stateStore.put("stale", pending("stale", expiresAtNanos = System.nanoTime() - 1))

            val result = service.exchange(presented(code = "c", state = "stale"))

            assertFalse(result.authorized)
            assertEquals(0, stateStore.size())
        }

        @Test
        @DisplayName("AS 报 error 时把理由回给页面,不去换 token")
        fun `an error answer from the AS reaches the page and is not exchanged`() {
            configured()
            registration()
            val params = queryOf(service.authorizeUrl(mcpId, null).authorizeUrl)

            val result = service.exchange(
                presented(state = params["state"], error = "access_denied", errorDescription = "The user clicked cancel"),
            )

            assertFalse(result.authorized)
            assertTrue(result.message.contains("access_denied"), result.message)
            assertTrue(result.message.contains("The user clicked cancel"), result.message)
            verify(fetcher, never()).postForm(any(), any())
        }

        @Test
        @DisplayName("上游的原话只回显 200 字符")
        fun `the upstream answer is quoted back within the budget and not in full`() {
            configured()
            registration()
            val params = queryOf(service.authorizeUrl(mcpId, null).authorizeUrl)

            val result = service.exchange(
                presented(state = params["state"], error = "server_error", errorDescription = "x".repeat(300)),
            )

            assertFalse(result.authorized)
            assertTrue(result.message.contains("server_error"), result.message)
            assertTrue(result.message.contains("x".repeat(100)), result.message)
            // error + a space + description share one 200-character budget: an AS that rants cannot
            // fill the page, and the page has no use for the rest of it.
            assertFalse(result.message.contains("x".repeat(188)), result.message)
            verify(fetcher, never()).postForm(any(), any())
            verifyNoCredentialWrites()
        }

        @Test
        @DisplayName("只有 code 没有 state 也拒绝")
        fun `an exchange with no state is refused`() {
            configured()
            registration()

            assertFalse(service.exchange(presented(code = "c", state = " ")).authorized)
        }

        @Test
        @DisplayName("state 是自己的但 AS 没给 code:不去换票")
        fun `a returned state without a code stops before the token endpoint`() {
            configured()
            registration()
            val params = queryOf(service.authorizeUrl(mcpId, null).authorizeUrl)

            val result = service.exchange(presented(code = "   ", state = params["state"]))

            assertFalse(result.authorized)
            assertTrue(result.message.contains("returned no code"), result.message)
            verify(fetcher, never()).postForm(any(), any())
            assertEquals(0, stateStore.size(), "the flow is over either way")
        }

        @Test
        @DisplayName("别人会话里发起的 state:拒绝、烧掉、谁的凭据也不动")
        fun `a state started in another session is refused burned and writes nothing`() {
            configured()
            registration()
            stateStore.put("other", pending("other", userId = 8L))
            postAnswers[tokenUrl] = jsonFetch("""{"access_token":"at-1"}""")

            val result = service.exchange(presented(code = "stolen-code", state = "other"))

            assertFalse(result.authorized)
            assertTrue(result.message.contains("another session"), result.message)
            assertEquals(0, stateStore.size(), "a state that is not the caller's is burned all the same")
            verify(fetcher, never()).postForm(any(), any())
            verifyNoCredentialWrites()
            // The answer goes to the browser, so it must not carry what the caller just handed over:
            // a code is live until someone redeems it, and the verifier is the pairing half.
            assertFalse(result.message.contains("stolen-code"), result.message)
            assertFalse(result.message.contains("verifier-other"), result.message)
        }

        @Test
        @DisplayName("别人的 state 配一个自造的 error:按归属拒绝,不能替对方取消授权")
        fun `a fabricated error cannot cancel another user's authorization`() {
            configured()
            registration()
            stateStore.put("other", pending("other", userId = 8L))

            val result = service.exchange(
                presented(state = "other", error = "access_denied", errorDescription = "The user clicked cancel"),
            )

            // Ownership is decided before anything the body says. Answering with the caller's own
            // `error` here would let anyone who learns a state cancel its owner's in-flight
            // authorization, and the mismatch warning - the detector for consent phishing - would
            // never fire about it.
            assertFalse(result.authorized)
            assertTrue(result.message.contains("another session"), result.message)
            assertFalse(result.message.contains("access_denied"), result.message)
            assertEquals(0, stateStore.size(), "a state that is not the caller's is burned all the same")
            verify(fetcher, never()).postForm(any(), any())
            verifyNoCredentialWrites()
        }

        @Test
        @DisplayName("state 已过期时,AS 的拒绝理由照样回给页面")
        fun `an error answer is reported even when its state has aged out`() {
            configured()
            registration()

            val result = service.exchange(
                presented(state = "never-issued", error = "access_denied", errorDescription = "The user clicked cancel"),
            )

            // Nothing is stored and nothing is left to burn, so the authorization server's own reason
            // is worth more to the page than "unknown state".
            assertFalse(result.authorized)
            assertTrue(result.message.contains("The authorization server refused"), result.message)
            assertTrue(result.message.contains("The user clicked cancel"), result.message)
            verify(fetcher, never()).postForm(any(), any())
            verifyNoCredentialWrites()
        }

        @Test
        @DisplayName("没有登录态就换不了票,state 也还没被烧")
        fun `an unauthenticated caller is turned away before the state is touched`() {
            configured()
            registration()
            val params = queryOf(service.authorizeUrl(mcpId, null).authorizeUrl)
            RequestContextHolder.resetRequestAttributes()

            val error = assertThrows(BizException::class.java) {
                service.exchange(presented(code = "c", state = params["state"]))
            }

            assertTrue(error.message!!.contains("no user identity"), error.message)
            assertEquals(1, stateStore.size(), "the refusal comes first, so the owner can still finish their flow")
            verify(fetcher, never()).postForm(any(), any())
            verifyNoCredentialWrites()
        }

        @Test
        @DisplayName("换哪个 MCP 由 state 决定:请求里没有可挑的 id")
        fun `the pending request decides which server receives the grant`() {
            val other = 99L
            configured()
            registration()
            whenever(mcpServerService.getMcpServer(other)).thenReturn(server(id = other))
            stateStore.put("own", pending("own", mcpId = other))
            postAnswers[tokenUrl] = jsonFetch("""{"access_token":"at-1"}""")

            val result = service.exchange(presented(code = "c", state = "own"))

            assertTrue(result.authorized, result.message)
            verify(mcpServerService).getMcpServer(other)
            assertEquals(other, argumentOfInsert().mcpId)
        }

        @Test
        @DisplayName("成功回答里没有一个字节能装 token")
        fun `the answer handed to the browser holds no token material`() {
            configured()
            registration()
            val params = queryOf(service.authorizeUrl(mcpId, null).authorizeUrl)
            postAnswers[tokenUrl] = jsonFetch("""{"access_token":"at-1","refresh_token":"rt-1","scope":"mcp:read"}""")

            val result = service.exchange(presented(code = "c", state = params["state"]))
            val wire = objectMapper.writeValueAsString(result)

            assertTrue(result.authorized, result.message)
            assertFalse(wire.contains("at-1"), wire)
            assertFalse(wire.contains("rt-1"), wire)
            assertFalse(wire.contains("access_token"), wire)
            assertFalse(wire.contains("refresh_token"), wire)
        }

        @Test
        @DisplayName("AS 不给 access_token 就不算授权成功")
        fun `an answer without an access_token is not a grant`() {
            configured()
            registration()
            val params = queryOf(service.authorizeUrl(mcpId, null).authorizeUrl)
            postAnswers[tokenUrl] = jsonFetch("""{"refresh_token":"rt-1","scope":"mcp:read"}""")

            val result = service.exchange(presented(code = "c", state = params["state"]))

            assertFalse(result.authorized)
            assertTrue(result.message.contains("without an access_token"), result.message)
            verifyNoCredentialWrites()
        }

        @Test
        @DisplayName("非 2xx 的换 token 回答带上游状态码与理由")
        fun `a failed exchange reports the status and the upstream reason`() {
            configured()
            registration()
            val params = queryOf(service.authorizeUrl(mcpId, null).authorizeUrl)
            postAnswers[tokenUrl] = RemoteFetch(
                400,
                null,
                objectMapper.readTree("""{"error":"invalid_grant","error_description":"code already used"}"""),
            )

            val result = service.exchange(presented(code = "c", state = params["state"]))

            assertFalse(result.authorized)
            assertTrue(result.message.contains("HTTP 400"), result.message)
            assertTrue(result.message.contains("code already used"), result.message)
            verifyNoCredentialWrites()
        }

        @Test
        @DisplayName("AS 发 200 但不是 JSON 对象时也拒绝")
        fun `an empty or non-object body is not a grant either`() {
            configured()
            registration()
            val params = queryOf(service.authorizeUrl(mcpId, null).authorizeUrl)
            postAnswers[tokenUrl] = RemoteFetch(204, null, null)

            val result = service.exchange(presented(code = "c", state = params["state"]))

            assertFalse(result.authorized)
            assertTrue(result.message.contains("no JSON object"), result.message)
        }

        @Test
        @DisplayName("token 里的 iss 与发现的 issuer 不符就不落库")
        fun `a token from another issuer is never stored`() {
            configured()
            registration()
            val params = queryOf(service.authorizeUrl(mcpId, null).authorizeUrl)
            postAnswers[tokenUrl] = jsonFetch(
                """{"access_token":"${jwt("""{"iss":"https://evil.example.com"}""")}"}""",
            )

            val result = service.exchange(presented(code = "c", state = params["state"]))

            assertFalse(result.authorized)
            assertTrue(result.message.contains("https://evil.example.com"), result.message)
            verifyNoCredentialWrites()
        }

        @Test
        @DisplayName("响应级 iss 同样按字节严格比")
        fun `a response level issuer is compared byte for byte`() {
            configured()
            registration()
            val params = queryOf(service.authorizeUrl(mcpId, null).authorizeUrl)
            postAnswers[tokenUrl] = jsonFetch("""{"access_token":"opaque","iss":"https://as.example.com/"}""")

            val result = service.exchange(presented(code = "c", state = params["state"]))

            assertFalse(result.authorized, "a trailing slash makes it a different issuer string")
            assertTrue(result.message.contains("iss"), result.message)
            verifyNoCredentialWrites()
        }

        @Test
        @DisplayName("aud 不含本 MCP 的 resource 时拒绝存这条授权")
        fun `a token whose audience excludes this server is refused`() {
            configured()
            registration()
            val params = queryOf(service.authorizeUrl(mcpId, null).authorizeUrl)
            postAnswers[tokenUrl] = jsonFetch(
                """{"access_token":"${jwt("""{"aud":["http://other.example.com/mcp"]}""")}"}""",
            )

            val result = service.exchange(presented(code = "c", state = params["state"]))

            assertFalse(result.authorized)
            assertTrue(result.message.contains("audience"), result.message)
            verifyNoCredentialWrites()
        }

        @Test
        @DisplayName("opaque token 照常接受:这里不消费 token 内容")
        fun `an opaque token is stored since its claims cannot be inspected`() {
            configured()
            registration()
            val params = queryOf(service.authorizeUrl(mcpId, null).authorizeUrl)
            postAnswers[tokenUrl] = jsonFetch("""{"access_token":"v1.abc-123"}""")

            assertTrue(service.exchange(presented(code = "c", state = params["state"])).authorized)
            assertEquals("enc:v1.abc-123", argumentOfInsert().accessTokenEnc)
        }

        @Test
        @DisplayName("aud 只有一个字符串时也按 resource 比")
        fun `a single-valued audience is checked the same way`() {
            configured()
            registration()
            val params = queryOf(service.authorizeUrl(mcpId, null).authorizeUrl)
            postAnswers[tokenUrl] = jsonFetch("""{"access_token":"${jwt("""{"aud":"$mcpUrl"}""")}"}""")

            assertTrue(service.exchange(presented(code = "c", state = params["state"])).authorized)
        }

        @Test
        @DisplayName("MCP 服务已删或换了租户时不落库")
        fun `a server that no longer belongs to this tenant cannot receive the grant`() {
            configured()
            registration()
            val params = queryOf(service.authorizeUrl(mcpId, null).authorizeUrl)
            postAnswers[tokenUrl] = jsonFetch("""{"access_token":"at-1"}""")
            whenever(mcpServerService.getMcpServer(mcpId)).thenReturn(server(tenantId = 2L))

            val result = service.exchange(presented(code = "c", state = params["state"]))

            assertFalse(result.authorized)
            assertTrue(result.message.contains("no longer exists"), result.message)
            verifyNoCredentialWrites()
        }

        @Test
        @DisplayName("换票期间服务改回静态 header:授权作废")
        fun `a server taken out of OAuth mid-flow stops the exchange`() {
            configured()
            registration()
            val params = queryOf(service.authorizeUrl(mcpId, null).authorizeUrl)
            whenever(mcpServerService.getMcpServer(mcpId)).thenReturn(server(authType = McpAuthTypes.STATIC_HEADER))

            val result = service.exchange(presented(code = "c", state = params["state"]))

            assertFalse(result.authorized)
            assertTrue(result.message.contains("OAuth authorization does not apply"), result.message)
        }

        @Test
        @DisplayName("换票期间服务改了地址:这个 code 是给旧地址换的,不用")
        fun `a server whose url moved mid-flow stops the exchange`() {
            configured()
            registration()
            val params = queryOf(service.authorizeUrl(mcpId, null).authorizeUrl)
            postAnswers[tokenUrl] = jsonFetch("""{"access_token":"at-1"}""")
            whenever(mcpServerService.getMcpServer(mcpId)).thenReturn(
                server().apply { url = "http://mcp.example.com:4000/mcp" },
            )

            val result = service.exchange(presented(code = "c", state = params["state"]))

            assertFalse(result.authorized)
            assertTrue(result.message.contains("changed while the authorization was in progress"), result.message)
            verify(fetcher, never()).postForm(any(), any())
            verifyNoCredentialWrites()
        }

        @Test
        @DisplayName("token 端点丢了就指回发现")
        fun `a missing token endpoint points back at discovery`() {
            configured()
            registration(tokenEndpoint = null)
            val params = queryOf(service.authorizeUrl(mcpId, null).authorizeUrl)

            val result = service.exchange(presented(code = "c", state = params["state"]))

            assertFalse(result.authorized)
            assertTrue(result.message.contains("run discovery again"), result.message)
        }

        @Test
        @DisplayName("出站网络异常不冒到页面外,只留一句可读的话")
        fun `an unreachable token endpoint answers as a failure not a crash`() {
            configured()
            registration()
            val params = queryOf(service.authorizeUrl(mcpId, null).authorizeUrl)
            whenever(fetcher.postForm(eq(tokenUrl), any())).thenThrow(RemoteFetchException("Connection refused"))

            val result = service.exchange(presented(code = "c", state = params["state"]))

            assertFalse(result.authorized)
            assertTrue(result.message.contains("Connection refused"), result.message)
            verifyNoCredentialWrites()
        }

        @Test
        @DisplayName("数据库故障不透给浏览器:换成一句可读的失败")
        fun `a database failure is answered as a failure not in its own words`() {
            configured()
            registration()
            val params = queryOf(service.authorizeUrl(mcpId, null).authorizeUrl)
            postAnswers[tokenUrl] = jsonFetch("""{"access_token":"at-1"}""")
            whenever(credentialMapper.insert(any())).thenThrow(
                DataIntegrityViolationException("Column 'access_token_enc' cannot be null; statement: insert into mcp_user_credential"),
            )

            val result = service.exchange(presented(code = "c", state = params["state"]))

            assertFalse(result.authorized)
            assertTrue(result.message.contains("Database operation failed"), result.message)
            assertFalse(result.message.contains("access_token_enc"), result.message)
            assertTrue(result.message.contains("please start the authorization again"), result.message)
        }
    }

    @Nested
    @DisplayName("status")
    inner class StatusTests {

        @Test
        @DisplayName("没授权过的用户读到的是空白而不是错误")
        fun `a user who never authorized reads an empty status`() {
            configured()

            val response = service.status(mcpId)

            assertFalse(response.authorized)
            assertNull(response.status)
            verifyNoCredentialWrites()
        }

        @Test
        @DisplayName("ACTIVE 且未过期才算已授权")
        fun `an active unexpired grant reads as authorized`() {
            configured()
            whenever(credentialMapper.selectByUserAndMcp(1L, 7L, mcpId)).thenReturn(
                credential(status = McpUserCredential.STATUS_ACTIVE, scopes = "mcp:read,mcp:write"),
            )

            val response = service.status(mcpId)

            assertTrue(response.authorized)
            assertEquals(listOf("mcp:read", "mcp:write"), response.scopes)
            assertEquals(McpUserCredential.STATUS_ACTIVE, response.status)
        }

        @Test
        @DisplayName("access token 过期后不算可用:刷新还没接")
        fun `an expired access token is not a usable grant`() {
            configured()
            whenever(credentialMapper.selectByUserAndMcp(1L, 7L, mcpId)).thenReturn(
                credential(accessExpiresAt = LocalDateTime.now().minusMinutes(1)),
            )

            val response = service.status(mcpId)

            assertFalse(response.authorized)
            assertEquals(McpUserCredential.STATUS_ACTIVE, response.status, "the row is still what it says it is")
        }

        @Test
        @DisplayName("没有过期时间的行按可用读")
        fun `a grant with no expiry reads as usable`() {
            configured()
            whenever(credentialMapper.selectByUserAndMcp(1L, 7L, mcpId)).thenReturn(
                credential(accessExpiresAt = null),
            )

            assertTrue(service.status(mcpId).authorized)
        }

        @Test
        @DisplayName("REVOKED 不复活")
        fun `a revoked row stays unauthorized`() {
            configured()
            whenever(credentialMapper.selectByUserAndMcp(1L, 7L, mcpId)).thenReturn(
                credential(status = McpUserCredential.STATUS_REVOKED),
            )

            assertFalse(service.status(mcpId).authorized)
        }

        @Test
        @DisplayName("只看自己的行:租户与用户都进查询条件")
        fun `the lookup is keyed by tenant and user`() {
            configured()

            service.status(mcpId)

            verify(credentialMapper).selectByUserAndMcp(1L, 7L, mcpId)
        }

        @Test
        @DisplayName("非 OAuth 服务不提供授权状态")
        fun `a server without OAuth is refused`() {
            configured(authType = McpAuthTypes.NONE)

            assertThrows(BizException::class.java) { service.status(mcpId) }
        }
    }

    @Nested
    @DisplayName("revoke")
    inner class RevokeTests {

        @Test
        @DisplayName("撤销优先杀 refresh token,并把两份密文都写回 NULL")
        fun `revocation prefers the refresh token and clears both ciphertexts`() {
            configured()
            storedGrant()
            registration(revocationEndpoint = revokeUrl)
            postAnswers[revokeUrl] = RemoteFetch(200, null, null)

            val response = service.revoke(mcpId)

            assertTrue(response.revoked)
            assertTrue(response.upstreamRevoked, response.message)
            val form = capturedForm(revokeUrl)
            assertEquals("rt-1", form["token"])
            assertEquals("refresh_token", form["token_type_hint"])
            assertEquals("harnax-web", form["client_id"])
            assertEquals("s3cret", form["client_secret"])
            val cleared = argumentOfUpdate()
            assertNull(cleared.accessTokenEnc)
            assertNull(cleared.refreshTokenEnc)
            assertNull(cleared.scopes)
            assertNull(cleared.accessExpiresAt)
            assertEquals(McpUserCredential.STATUS_REVOKED, cleared.status)
        }

        @Test
        @DisplayName("没有 refresh token 时拿 access token 去撤销")
        fun `an access token is presented when no refresh token was stored`() {
            configured()
            registration(revocationEndpoint = revokeUrl)
            whenever(credentialMapper.selectByUserAndMcp(1L, 7L, mcpId)).thenReturn(
                credential(refreshTokenEnc = null),
            )
            postAnswers[revokeUrl] = RemoteFetch(200, null, null)

            assertTrue(service.revoke(mcpId).upstreamRevoked)
            assertEquals("access_token", capturedForm(revokeUrl)["token_type_hint"])
        }

        @Test
        @DisplayName("AS 不提供 revocation 时说明上游仍然可用")
        fun `a server with no revocation endpoint says its own copy stays valid`() {
            configured()
            storedGrant()
            registration()

            val response = service.revoke(mcpId)

            assertTrue(response.revoked)
            assertFalse(response.upstreamRevoked)
            assertTrue(response.message.contains("RFC 7009"), response.message)
            verify(fetcher, never()).postForm(any(), any())
            assertEquals(McpUserCredential.STATUS_REVOKED, argumentOfUpdate().status)
        }

        @Test
        @DisplayName("上游拒绝撤销也要清本地,并说清两边不一致")
        fun `a refused upstream revocation still clears locally and says so`() {
            configured()
            storedGrant()
            registration(revocationEndpoint = revokeUrl)
            postAnswers[revokeUrl] = RemoteFetch(403, null, null)

            val response = service.revoke(mcpId)

            assertTrue(response.revoked)
            assertFalse(response.upstreamRevoked)
            assertTrue(response.message.contains("did not accept"), response.message)
            assertEquals(McpUserCredential.STATUS_REVOKED, argumentOfUpdate().status)
        }

        @Test
        @DisplayName("撤销端点连不上不整个失败")
        fun `an unreachable revocation endpoint does not fail the revoke`() {
            configured()
            storedGrant()
            registration(revocationEndpoint = revokeUrl)
            whenever(fetcher.postForm(eq(revokeUrl), any())).thenThrow(RemoteFetchException("Connection reset"))

            val response = service.revoke(mcpId)

            assertTrue(response.revoked)
            assertFalse(response.upstreamRevoked)
            assertEquals(McpUserCredential.STATUS_REVOKED, argumentOfUpdate().status)
        }

        @Test
        @DisplayName("库里密文是空的就不拿去上游")
        fun `a row with no token left is cleared locally only`() {
            configured()
            registration(revocationEndpoint = revokeUrl)
            whenever(credentialMapper.selectByUserAndMcp(1L, 7L, mcpId)).thenReturn(
                credential(accessTokenEnc = null, refreshTokenEnc = null, status = McpUserCredential.STATUS_NEEDS_CONSENT),
            )

            val response = service.revoke(mcpId)

            assertTrue(response.revoked)
            assertFalse(response.upstreamRevoked)
            assertTrue(response.message.contains("no stored token"), response.message)
            verify(fetcher, never()).postForm(any(), any())
        }

        @Test
        @DisplayName("换过密钥后密文解不开:本地照清,理由说清是解不开")
        fun `a token that no longer decrypts still clears the local row`() {
            configured()
            storedGrant()
            registration(revocationEndpoint = revokeUrl)
            whenever(aesUtil.decrypt(any())).thenThrow(IllegalStateException("AES-GCM: decryption failed"))

            val response = service.revoke(mcpId)

            assertTrue(response.revoked)
            assertFalse(response.upstreamRevoked)
            assertTrue(response.message.contains("could not be decrypted"), response.message)
            verify(fetcher, never()).postForm(any(), any())
            // Revocation is the user's only way out of a grant, so an unreadable token must not strand
            // the row: the local half is cleared regardless.
            val cleared = argumentOfUpdate()
            assertNull(cleared.accessTokenEnc)
            assertNull(cleared.refreshTokenEnc)
            assertEquals(McpUserCredential.STATUS_REVOKED, cleared.status)
        }

        @Test
        @DisplayName("注册行被删了:说不出是哪家 AS,不甩锅给 RFC 7009")
        fun `a grant whose registration is gone says so instead of blaming the missing endpoint`() {
            configured()
            storedGrant()

            val response = service.revoke(mcpId)

            assertTrue(response.revoked)
            assertFalse(response.upstreamRevoked)
            assertTrue(response.message.contains("no longer be resolved"), response.message)
            assertTrue(response.message.contains("/oauth/discover"), response.message)
            assertFalse(response.message.contains("RFC 7009"), response.message)
            verify(fetcher, never()).postForm(any(), any())
            assertEquals(McpUserCredential.STATUS_REVOKED, argumentOfUpdate().status)
        }

        @Test
        @DisplayName("没授权过也回答已撤销,但不写库")
        fun `revoking nothing is a success with no write`() {
            configured()
            registration()

            val response = service.revoke(mcpId)

            assertTrue(response.revoked)
            assertFalse(response.upstreamRevoked)
            assertTrue(response.message.contains("nothing to revoke"), response.message)
            verifyNoCredentialWrites()
        }

        @Test
        @DisplayName("撤销不动别人的行")
        fun `revoke only ever touches the callers own row`() {
            configured()

            service.revoke(mcpId)

            verify(credentialMapper).selectByUserAndMcp(1L, 7L, mcpId)
        }
    }

    // ---- fixtures ----

    /** Server as configured for OAuth; a case that gets past the setup checks also stubs [registration]. */
    private fun configured(
        authType: String = McpAuthTypes.OAUTH2,
        config: McpOAuthConfig = McpOAuthConfig(authorizationServer = issuer, scopes = listOf("mcp:read")),
    ): McpServer {
        val server = server(authType = authType, config = config)
        whenever(mcpServerService.getMcpServer(mcpId)).thenReturn(server)
        return server
    }

    private fun server(
        id: Long = mcpId,
        authType: String = McpAuthTypes.OAUTH2,
        tenantId: Long = 1L,
        config: McpOAuthConfig = McpOAuthConfig(authorizationServer = issuer, scopes = listOf("mcp:read")),
    ): McpServer = McpServer().apply {
        this.id = id
        this.tenantId = tenantId
        name = "demo"
        url = mcpUrl
        type = "streamablehttp"
        this.authType = authType
        oauthConfig = objectMapper.writeValueAsString(config)
    }

    private fun registration(
        clientId: String = "harnax-web",
        authorizationEndpoint: String? = authorizeEndpoint,
        tokenEndpoint: String? = tokenUrl,
        revocationEndpoint: String? = null,
    ): McpOauthClient {
        val client = McpOauthClient().apply {
            id = 11L
            tenantId = 1L
            issuer = this@McpOAuthUserServiceImplTest.issuer
            this.clientId = clientId
            clientSecretEnc = "enc:s3cret"
            this.authorizationEndpoint = authorizationEndpoint
            this.tokenEndpoint = tokenEndpoint
            this.revocationEndpoint = revocationEndpoint
            scopesSupported = "mcp:read,mcp:write"
            callbackUrl = this@McpOAuthUserServiceImplTest.callbackUrl
            creator = "tester"
        }
        whenever(clientMapper.selectByTenantAndIssuer(any(), any())).thenReturn(client)
        return client
    }

    private fun credential(
        status: String = McpUserCredential.STATUS_ACTIVE,
        accessTokenEnc: String? = "enc:at-1",
        refreshTokenEnc: String? = "enc:rt-1",
        scopes: String? = "mcp:read",
        accessExpiresAt: LocalDateTime? = LocalDateTime.now().plusHours(1),
    ): McpUserCredential = McpUserCredential().apply {
        id = 21L
        tenantId = 1L
        userId = 7L
        mcpId = this@McpOAuthUserServiceImplTest.mcpId
        this.status = status
        this.accessTokenEnc = accessTokenEnc
        this.refreshTokenEnc = refreshTokenEnc
        this.scopes = scopes
        this.accessExpiresAt = accessExpiresAt
        lastRefreshedAt = LocalDateTime.now()
    }

    private fun storedGrant(credential: McpUserCredential = credential()): McpUserCredential {
        whenever(credentialMapper.selectByUserAndMcp(1L, 7L, mcpId)).thenReturn(credential)
        return credential
    }

    private fun pending(
        state: String,
        userId: Long = 7L,
        mcpId: Long = this.mcpId,
        expiresAtNanos: Long = McpOAuthStateStore.newExpiresAtNanos(),
    ): PendingAuthorization = PendingAuthorization(
        tenantId = 1L,
        userId = userId,
        mcpId = mcpId,
        issuer = issuer,
        codeVerifier = "verifier-$state",
        redirectUri = callbackUrl,
        resource = mcpUrl,
        requestedScopes = listOf("mcp:read"),
        expiresAtNanos = expiresAtNanos,
    )

    /**
     * What the landing page posts: the query parameters the authorization server returned, plus the
     * caller's own JWT - which is why no case has to say who it is, [setUp] already put a token in
     * the request context.
     */
    private fun presented(
        code: String? = null,
        state: String? = null,
        error: String? = null,
        errorDescription: String? = null,
    ): McpOAuthExchangeRequest = McpOAuthExchangeRequest(code, state, error, errorDescription)

    private fun jsonFetch(body: String): RemoteFetch = RemoteFetch(200, null, objectMapper.readTree(body))

    private fun jwt(claims: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"RS256"}""".toByteArray(StandardCharsets.UTF_8))
        val payload = encoder.encodeToString(claims.toByteArray(StandardCharsets.UTF_8))
        return "$header.$payload.signature"
    }

    private fun challengeOf(verifier: String): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))

    private fun queryOf(url: String): Map<String, String> = URI(url).query
        .split('&')
        .associate { part ->
            val pair = part.split('=', limit = 2)
            decode(pair[0]) to decode(pair.getOrNull(1) ?: "")
        }

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

    private fun capturedForm(url: String): Map<String, String> {
        val captor = argumentCaptor<Map<String, String>>()
        verify(fetcher).postForm(eq(url), captor.capture())
        return captor.firstValue
    }

    private fun argumentOfInsert(): McpUserCredential {
        val captor = argumentCaptor<McpUserCredential>()
        verify(credentialMapper).insert(captor.capture())
        return captor.firstValue
    }

    private fun argumentOfUpdate(): McpUserCredential {
        val captor = argumentCaptor<McpUserCredential>()
        verify(credentialMapper).updateById(captor.capture())
        return captor.firstValue
    }

    private fun verifyNoCredentialWrites() {
        verify(credentialMapper, never()).insert(any())
        verify(credentialMapper, never()).updateById(any())
    }
}

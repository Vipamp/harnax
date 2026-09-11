package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.McpOAuthClientRequest
import com.agnetix.harnax.admin.dto.McpOAuthConfig
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.McpServerService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.RemoteFetch
import com.agnetix.harnax.admin.util.RemoteFetchException
import com.agnetix.harnax.admin.util.RemoteJsonFetcher
import com.agnetix.harnax.admin.util.SecretFieldEncryptor
import com.agnetix.harnax.entity.McpAuthTypes
import com.agnetix.harnax.entity.McpOauthClient
import com.agnetix.harnax.entity.McpServer
import com.agnetix.harnax.mapper.McpOauthClientMapper
import com.agnetix.harnax.mapper.McpServerMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * McpOAuthServiceImpl Unit Tests
 *
 * The outbound HTTP layer is stubbed at [RemoteJsonFetcher], so every case asserts which URL
 * discovery asked for and what it did with the answer - no socket, no authorization server.
 *
 * @author agnetix
 * @since 2026-09-09
 */
class McpOAuthServiceImplTest {

    private val jwtUtil = mock<JwtUtil>()
    private val mcpServerService = mock<McpServerService>()
    private val mcpServerMapper = mock<McpServerMapper>()
    private val clientMapper = mock<McpOauthClientMapper>()
    private val encryptor = mock<SecretFieldEncryptor>()
    private val fetcher = mock<RemoteJsonFetcher>()
    private val objectMapper = jacksonObjectMapper()

    private val responses = mutableMapOf<String, () -> RemoteFetch>()

    private lateinit var service: McpOAuthServiceImpl

    private val mcpUrl = "http://mcp.example.com:3000/mcp"
    private val issuer = "https://as.example.com"

    @BeforeEach
    fun setUp() {
        val request = MockHttpServletRequest()
        request.addHeader("Authorization", "Bearer mock-token")
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
        whenever(jwtUtil.validateToken(any())).thenReturn(true)
        whenever(jwtUtil.getUsernameFromToken(any())).thenReturn("admin")

        // Anything not registered below simply "isn't published at this path", which is what most of
        // these cases are about.
        whenever(fetcher.fetch(any())).thenAnswer { invocation ->
            val url = invocation.getArgument<String>(0)
            responses[url]?.invoke() ?: RemoteFetch(404, null, null)
        }
        // Mirrors the real keep/replace/clear rule so the assertions below are about the stored row;
        // the masked-value branch is covered against the real encryptor in SecretFieldEncryptorTest.
        whenever(encryptor.resolveSecret(anyOrNull(), anyOrNull())).thenAnswer { invocation ->
            val provided = invocation.getArgument<String?>(0)
            when {
                provided == null -> invocation.getArgument<String?>(1)
                provided.isBlank() -> null
                else -> "enc:$provided"
            }
        }
        whenever(clientMapper.insert(any())).thenAnswer { invocation ->
            invocation.getArgument<McpOauthClient>(0).id = 7L
            1
        }
        service = McpOAuthServiceImpl(
            jwtUtil,
            mcpServerService,
            mcpServerMapper,
            clientMapper,
            encryptor,
            fetcher,
            objectMapper,
            BASE_URL,
            // Unset in development too: the fallback below is what the shipped default relies on.
            "",
        )
    }

    @AfterEach
    fun tearDown() {
        RequestContextHolder.resetRequestAttributes()
    }

    private fun server(
        authType: String = McpAuthTypes.OAUTH2,
        url: String = mcpUrl,
        config: McpOAuthConfig = McpOAuthConfig(),
    ): McpServer = McpServer().apply {
        id = 1L
        tenantId = 1L
        name = "OAuth MCP"
        type = "streamablehttp"
        this.url = url
        this.authType = authType
        oauthConfig = objectMapper.writeValueAsString(config)
        active = 1
        status = 1
    }

    private fun existingClient(): McpOauthClient = McpOauthClient().apply {
        id = 3L
        tenantId = 1L
        this.issuer = this@McpOAuthServiceImplTest.issuer
        clientId = "harnax-existing"
        clientSecretEnc = "stored-ciphertext"
        callbackUrl = "https://old.example.com/callback"
        registrationSource = "MANUAL"
        authorizationEndpoint = "$issuer/authorize"
        tokenEndpoint = "$issuer/token"
        scopesSupported = "read,write"
        active = 1
    }

    private fun onThisServer(config: McpOAuthConfig = McpOAuthConfig()): McpServer = server(config = config).also { stubServer(it) }

    private fun stubServer(vararg servers: McpServer) {
        servers.forEach { whenever(mcpServerService.getMcpServer(it.id)).thenReturn(it) }
    }

    private fun storedRow(client: McpOauthClient): McpOauthClient = client.also {
        whenever(clientMapper.selectByTenantAndIssuer(1L, issuer)).thenReturn(it)
    }

    private fun metadata(
        iss: String = issuer,
        scopes: List<String> = listOf("read", "write"),
    ): String = objectMapper.writeValueAsString(
        mapOf(
            "issuer" to iss,
            "authorization_endpoint" to "$iss/authorize",
            "token_endpoint" to "$iss/token",
            "revocation_endpoint" to "$iss/revoke",
            "scopes_supported" to scopes,
        ),
    )

    private fun ok(
        url: String,
        body: String,
    ) {
        responses[url] = { RemoteFetch(200, null, objectMapper.readTree(body)) }
    }

    private fun down(url: String) {
        responses[url] = { throw RemoteFetchException("Request to $url failed: connection refused") }
    }

    private fun challenge(
        url: String,
        header: String?,
    ) {
        responses[url] = { RemoteFetch(401, header, null) }
    }

    private fun insertedClient(): McpOauthClient {
        val captor = argumentCaptor<McpOauthClient>()
        verify(clientMapper).insert(captor.capture())
        return captor.firstValue
    }

    private fun savedClient(): McpOauthClient {
        val captor = argumentCaptor<McpOauthClient>()
        verify(clientMapper).updateById(captor.capture())
        return captor.firstValue
    }

    private fun assertNothingFetchedOrStored() {
        verify(fetcher, never()).fetch(any())
        verify(clientMapper, never()).insert(any())
    }

    @Nested
    @DisplayName("Issuer resolution")
    inner class IssuerResolutionTests {

        @Test
        fun `a configured issuer is used without touching the resource metadata`() {
            onThisServer(McpOAuthConfig(authorizationServer = issuer))
            ok("$issuer/.well-known/oauth-authorization-server", metadata())

            val response = service.discover(1L)

            assertEquals(issuer, response.issuer)
            assertEquals("CONFIG", response.issuerSource)
            verify(fetcher, never()).fetch("http://mcp.example.com:3000/.well-known/oauth-protected-resource/mcp")
            verify(fetcher, never()).fetch("http://mcp.example.com:3000/.well-known/oauth-protected-resource")
        }

        @Test
        fun `an empty authorization server falls back to the RFC 9728 document with the path inserted`() {
            onThisServer()
            ok(
                "http://mcp.example.com:3000/.well-known/oauth-protected-resource/mcp",
                """{"authorization_servers":["$issuer"],"resource":"$mcpUrl"}""",
            )
            ok("$issuer/.well-known/oauth-authorization-server", metadata())

            val response = service.discover(1L)

            assertEquals("PROTECTED_RESOURCE", response.issuerSource)
            assertEquals("$issuer/authorize", response.authorizationEndpoint)
        }

        @Test
        fun `the host root document is tried when the path scoped ones are missing`() {
            onThisServer()
            ok(
                "http://mcp.example.com:3000/.well-known/oauth-protected-resource",
                """{"authorization_servers":["$issuer"]}""",
            )
            ok("$issuer/.well-known/oauth-authorization-server", metadata())

            assertEquals("PROTECTED_RESOURCE", service.discover(1L).issuerSource)
        }

        @Test
        fun `several advertised authorization servers take the first`() {
            onThisServer()
            ok(
                "http://mcp.example.com:3000/.well-known/oauth-protected-resource/mcp",
                """{"authorization_servers":["$issuer","https://other.example.com"]}""",
            )
            ok("$issuer/.well-known/oauth-authorization-server", metadata())

            assertEquals(issuer, service.discover(1L).issuer)
        }

        @Test
        fun `the 401 challenge pointer is used when no well-known document answers`() {
            onThisServer()
            challenge(mcpUrl, """Bearer realm="mcp", resource_metadata="http://mcp.example.com:3000/authz.json"""")
            ok("http://mcp.example.com:3000/authz.json", """{"authorization_servers":["$issuer"]}""")
            ok("$issuer/.well-known/oauth-authorization-server", metadata())

            assertEquals("RESOURCE_METADATA", service.discover(1L).issuerSource)
        }

        @Test
        fun `an advertised issuer that is not http(s) is rejected instead of stored`() {
            onThisServer()
            ok(
                "http://mcp.example.com:3000/.well-known/oauth-protected-resource/mcp",
                """{"authorization_servers":["file:///etc/passwd"]}""",
            )

            val error = assertThrows(BizException::class.java) { service.discover(1L) }
            assertTrue(error.message!!.contains("must be an http(s) URL"), error.message)
        }

        @Test
        fun `nothing answering is an error rather than a silent downgrade`() {
            onThisServer()
            down("http://mcp.example.com:3000/.well-known/oauth-protected-resource/mcp")
            down("http://mcp.example.com:3000/mcp/.well-known/oauth-protected-resource")
            down("http://mcp.example.com:3000/.well-known/oauth-protected-resource")
            down(mcpUrl)

            val error = assertThrows(BizException::class.java) { service.discover(1L) }
            assertTrue(error.message!!.startsWith("Cannot locate the authorization server"), error.message)
            verify(clientMapper, never()).insert(any())
        }

        @Test
        fun `a trailing slash on the issuer is dropped before it becomes an identity`() {
            onThisServer(McpOAuthConfig(authorizationServer = "$issuer/"))
            ok("$issuer/.well-known/oauth-authorization-server", metadata())

            val response = service.discover(1L)

            // The lookup that reuses a registration is byte-exact, so "same server, extra slash" must
            // not be allowed to create a second one.
            assertEquals(issuer, response.issuer)
            assertEquals(issuer, insertedClient().issuer)
        }

        @Test
        fun `an issuer carrying userinfo is refused and the credential is not echoed back`() {
            onThisServer(McpOAuthConfig(authorizationServer = "https://admin:s3cret@as.example.com"))

            val error = assertThrows(BizException::class.java) { service.discover(1L) }

            assertTrue(error.message!!.contains("without userinfo, query or fragment"), error.message)
            assertFalse(error.message!!.contains("s3cret"), error.message)
            assertTrue(error.message!!.contains("https://***@as.example.com"), error.message)
            assertNothingFetchedOrStored()
        }

        @Test
        fun `an issuer carrying a fragment is refused because it never reaches the metadata url`() {
            onThisServer(McpOAuthConfig(authorizationServer = "$issuer#main"))

            val error = assertThrows(BizException::class.java) { service.discover(1L) }

            assertTrue(error.message!!.contains("without userinfo, query or fragment"), error.message)
            assertNothingFetchedOrStored()
        }

        @Test
        fun `an issuer wider than the column is refused instead of truncated by the database`() {
            onThisServer(McpOAuthConfig(authorizationServer = "$issuer/realms/" + "a".repeat(300)))

            val error = assertThrows(BizException::class.java) { service.discover(1L) }

            assertTrue(error.message!!.contains("longer than the 255 characters"), error.message)
            assertNothingFetchedOrStored()
        }

        @Test
        fun `a stored url with stray whitespace around it still discovers`() {
            stubServer(server(url = "  $mcpUrl  "))
            ok(
                "http://mcp.example.com:3000/.well-known/oauth-protected-resource/mcp",
                """{"authorization_servers":["$issuer"]}""",
            )
            ok("$issuer/.well-known/oauth-authorization-server", metadata())

            assertEquals("PROTECTED_RESOURCE", service.discover(1L).issuerSource)
        }

        @Test
        fun `an unquoted resource_metadata pointer is read as well as a quoted one`() {
            onThisServer()
            challenge(mcpUrl, "Bearer error=\"invalid_token\", resource_metadata=http://mcp.example.com:3000/authz.json")
            ok("http://mcp.example.com:3000/authz.json", """{"authorization_servers":["$issuer"]}""")
            ok("$issuer/.well-known/oauth-authorization-server", metadata())

            assertEquals("RESOURCE_METADATA", service.discover(1L).issuerSource)
        }

        @Test
        fun `a resource_metadata pointer that is not http(s) is refused`() {
            onThisServer()
            challenge(mcpUrl, """Bearer resource_metadata="file:///etc/passwd"""")

            val error = assertThrows(BizException::class.java) { service.discover(1L) }

            assertTrue(error.message!!.contains("resource_metadata must be an http(s) URL"), error.message)
            verify(clientMapper, never()).insert(any())
        }
    }

    @Nested
    @DisplayName("Authorization server metadata")
    inner class MetadataTests {

        @Test
        fun `the path inserted RFC 8414 url is requested first for an issuer with a path`() {
            val pathIssuer = "$issuer/realms/harnax"
            onThisServer(McpOAuthConfig(authorizationServer = pathIssuer))
            ok("https://as.example.com/.well-known/oauth-authorization-server/realms/harnax", metadata(pathIssuer))

            assertEquals(pathIssuer, service.discover(1L).issuer)
        }

        @Test
        fun `the appended form is used when the inserted one is missing`() {
            val pathIssuer = "$issuer/realms/harnax"
            onThisServer(McpOAuthConfig(authorizationServer = pathIssuer))
            ok("$pathIssuer/.well-known/oauth-authorization-server", metadata(pathIssuer))

            assertEquals(pathIssuer, service.discover(1L).issuer)
        }

        @Test
        fun `an openid configuration is accepted when the oauth one is absent`() {
            onThisServer(McpOAuthConfig(authorizationServer = issuer))
            ok("$issuer/.well-known/openid-configuration", metadata())

            val response = service.discover(1L)

            assertEquals("$issuer/token", response.tokenEndpoint)
            assertEquals("$issuer/revoke", response.revocationEndpoint)
        }

        @Test
        fun `a document naming another issuer is refused`() {
            onThisServer(McpOAuthConfig(authorizationServer = issuer))
            ok("$issuer/.well-known/oauth-authorization-server", metadata("https://evil.example.com"))

            val error = assertThrows(BizException::class.java) { service.discover(1L) }
            assertTrue(error.message!!.contains("expected $issuer"), error.message)
            verify(clientMapper, never()).insert(any())
        }

        @Test
        fun `a document without a token endpoint is not a usable authorization server`() {
            onThisServer(McpOAuthConfig(authorizationServer = issuer))
            ok(
                "$issuer/.well-known/oauth-authorization-server",
                """{"issuer":"$issuer","authorization_endpoint":"$issuer/authorize"}""",
            )

            val error = assertThrows(BizException::class.java) { service.discover(1L) }
            assertTrue(error.message!!.contains("no authorization_endpoint/token_endpoint"), error.message)
        }

        @Test
        fun `a 200 that is not a json object is not metadata`() {
            onThisServer(McpOAuthConfig(authorizationServer = issuer))
            responses["$issuer/.well-known/oauth-authorization-server"] = { RemoteFetch(200, null, null) }

            val error = assertThrows(BizException::class.java) { service.discover(1L) }
            assertTrue(error.message!!.contains("is not a JSON object"), error.message)
        }

        @Test
        fun `a token endpoint that is not http(s) is refused rather than stored`() {
            onThisServer(McpOAuthConfig(authorizationServer = issuer))
            ok(
                "$issuer/.well-known/oauth-authorization-server",
                """{"issuer":"$issuer","authorization_endpoint":"$issuer/authorize","token_endpoint":"javascript:alert(1)"}""",
            )

            val error = assertThrows(BizException::class.java) { service.discover(1L) }

            // This is the value a later token exchange would be POSTed a client_secret to.
            assertTrue(error.message!!.contains("token_endpoint must be an http(s) URL"), error.message)
            assertTrue(error.message!!.contains("advertised by"), error.message)
            verify(clientMapper, never()).insert(any())
        }

        @Test
        fun `an endpoint wider than its column is refused`() {
            onThisServer(McpOAuthConfig(authorizationServer = issuer))
            ok(
                "$issuer/.well-known/oauth-authorization-server",
                """{"issuer":"$issuer","authorization_endpoint":"$issuer/authorize","token_endpoint":"$issuer/token?state=${"a".repeat(600)}"}""",
            )

            val error = assertThrows(BizException::class.java) { service.discover(1L) }

            assertTrue(error.message!!.contains("longer than the 500 characters"), error.message)
            verify(clientMapper, never()).insert(any())
        }

        @Test
        fun `an optional endpoint the server points at badly is dropped, not stored`() {
            onThisServer(McpOAuthConfig(authorizationServer = issuer))
            ok(
                "$issuer/.well-known/oauth-authorization-server",
                metadata().replace("\"revocation_endpoint\":\"$issuer/revoke\"", "\"revocation_endpoint\":\"ftp://as.example.com/revoke\""),
            )

            val response = service.discover(1L)

            // Discovery itself still succeeded: a bad optional endpoint is not a reason to lose the
            // required ones, but it must not survive into the row that revocation will post to.
            assertNull(response.revocationEndpoint)
            assertEquals("$issuer/token", response.tokenEndpoint)
            assertNull(insertedClient().revocationEndpoint)
        }

        @Test
        fun `a document that declares no issuer cannot confirm an advertised issuer`() {
            onThisServer()
            ok(
                "http://mcp.example.com:3000/.well-known/oauth-protected-resource/mcp",
                """{"authorization_servers":["$issuer"]}""",
            )
            ok(
                "$issuer/.well-known/oauth-authorization-server",
                """{"authorization_endpoint":"$issuer/authorize","token_endpoint":"$issuer/token"}""",
            )

            val error = assertThrows(BizException::class.java) { service.discover(1L) }

            // Here the issuer itself came from an untrusted document, so there is no admin who vouched
            // for it and no second source to cross-check against.
            assertTrue(error.message!!.contains("declares no issuer"), error.message)
            assertTrue(error.message!!.contains("set oauthConfig.authorizationServer"), error.message)
            verify(clientMapper, never()).insert(any())
            verifyNoInteractions(mcpServerMapper)
        }

        @Test
        fun `a document that declares no issuer is accepted for a hand-typed issuer`() {
            onThisServer(McpOAuthConfig(authorizationServer = issuer))
            ok(
                "$issuer/.well-known/oauth-authorization-server",
                """{"authorization_endpoint":"$issuer/authorize","token_endpoint":"$issuer/token"}""",
            )

            assertEquals(issuer, service.discover(1L).issuer)
        }
    }

    @Nested
    @DisplayName("Storing the registration")
    inner class RegistrationTests {

        @Test
        fun `a first discovery inserts the endpoints with the deployment callback url`() {
            onThisServer(McpOAuthConfig(authorizationServer = issuer))
            ok("$issuer/.well-known/oauth-authorization-server", metadata())

            val response = service.discover(1L)
            val client = insertedClient()

            assertEquals(issuer, client.issuer)
            assertEquals(1L, client.tenantId)
            assertEquals("", client.clientId)
            assertEquals("admin", client.creator)
            assertEquals(CALLBACK, client.callbackUrl)
            // echoed so the page can tell a stale stored redirect_uri from the one this build wants
            assertEquals(CALLBACK, response.defaultCallbackUrl)
            assertEquals(listOf("read", "write"), response.scopesSupported)
            assertNull(response.clientId)
            assertFalse(response.clientSecretPresent)
        }

        @Test
        fun `a second discovery keeps the client and its redirect uri and refreshes the endpoints`() {
            onThisServer(McpOAuthConfig(authorizationServer = issuer))
            storedRow(existingClient())
            ok(
                "$issuer/.well-known/oauth-authorization-server",
                """{"issuer":"$issuer","authorization_endpoint":"$issuer/new-authorize","token_endpoint":"$issuer/new-token"}""",
            )

            val response = service.discover(1L)

            verify(clientMapper, never()).insert(any())
            val client = savedClient()
            assertEquals("harnax-existing", client.clientId)
            assertEquals("stored-ciphertext", client.clientSecretEnc)
            // The AS stopped advertising DCR: the snapshot has to say so rather than keep a dead endpoint.
            assertNull(client.registrationEndpoint)
            assertEquals("$issuer/new-authorize", response.authorizationEndpoint)
            assertEquals("https://old.example.com/callback", response.callbackUrl)
        }

        @Test
        fun `a discovered issuer is written back to oauth_config so later calls need no second walk`() {
            val mcpServer = onThisServer()
            ok(
                "http://mcp.example.com:3000/.well-known/oauth-protected-resource/mcp",
                """{"authorization_servers":["$issuer"]}""",
            )
            ok("$issuer/.well-known/oauth-authorization-server", metadata())

            service.discover(1L)

            val captor = argumentCaptor<String>()
            verify(mcpServerMapper).updateOAuthConfig(eq(mcpServer.id), captor.capture())
            val stored = objectMapper.readValue(captor.firstValue, McpOAuthConfig::class.java)
            assertEquals(issuer, stored.authorizationServer)
            // The rest of the row must stay alone: discovery does not own status, headers or the name,
            // and writing a previously-read row back would undo someone else's edit.
            verify(mcpServerMapper, never()).updateById(any())
        }

        @Test
        fun `a configured issuer is not rewritten into oauth_config`() {
            onThisServer(McpOAuthConfig(authorizationServer = issuer))
            ok("$issuer/.well-known/oauth-authorization-server", metadata())

            service.discover(1L)

            verifyNoInteractions(mcpServerMapper)
        }

        @Test
        fun `requested scopes the authorization server does not list are reported`() {
            onThisServer(McpOAuthConfig(authorizationServer = issuer, scopes = listOf("read", "admin")))
            ok("$issuer/.well-known/oauth-authorization-server", metadata())

            assertEquals(listOf("admin"), service.discover(1L).unknownScopes)
        }

        @Test
        fun `a server that is not oauth is refused before any request goes out`() {
            stubServer(server(authType = McpAuthTypes.STATIC_HEADER))

            val error = assertThrows(BizException::class.java) { service.discover(1L) }
            assertTrue(error.message!!.contains(McpAuthTypes.STATIC_HEADER), error.message)
            assertNothingFetchedOrStored()
        }

        @Test
        fun `an oauth server without a url is refused`() {
            stubServer(server(url = ""))

            val error = assertThrows(BizException::class.java) { service.discover(1L) }
            assertEquals("OAuth discovery needs the MCP server's url, and this one has none", error.message)
            assertNothingFetchedOrStored()
        }

        @Test
        fun `an oauth server whose stored url is not http(s) is refused`() {
            stubServer(server(url = "ftp://mcp.example.com/mcp"))

            val error = assertThrows(BizException::class.java) { service.discover(1L) }
            assertTrue(error.message!!.contains("MCP server url must be an http(s) URL"), error.message)
            assertNothingFetchedOrStored()
        }

        @Test
        fun `an unreadable stored config stops discovery instead of being taken as unset`() {
            stubServer(server().apply { oauthConfig = """{"authorizationServer":""" })

            val error = assertThrows(BizException::class.java) { service.discover(1L) }

            // Discovery rewrites this column, so continuing would replace scopes the admin typed with
            // an empty list and report that as a success.
            assertTrue(error.message!!.contains("cannot be read"), error.message)
            assertNothingFetchedOrStored()
            verifyNoInteractions(mcpServerMapper)
        }

        @Test
        fun `the reasons collected on the way out stay bounded`() {
            onThisServer()
            // Each upstream message can be long, and this text goes out inside an API response.
            listOf(
                "http://mcp.example.com:3000/.well-known/oauth-protected-resource/mcp",
                "http://mcp.example.com:3000/mcp/.well-known/oauth-protected-resource",
                "http://mcp.example.com:3000/.well-known/oauth-protected-resource",
                mcpUrl,
            ).forEach { url -> responses[url] = { throw RemoteFetchException("Request to $url failed: " + "x".repeat(400)) } }

            val error = assertThrows(BizException::class.java) { service.discover(1L) }

            assertTrue(error.message!!.startsWith("Cannot locate the authorization server"), error.message)
            assertTrue(error.message!!.length < 800, "error body was ${error.message!!.length} characters")
        }

        @Test
        fun `a row outside the tenant answers as missing`() {
            whenever(mcpServerService.getMcpServer(9L)).thenReturn(null)

            val error = assertThrows(BizException::class.java) { service.discover(9L) }
            assertEquals("MCP server not found", error.message)
        }
    }

    @Nested
    @DisplayName("Client credentials")
    inner class ClientCredentialsTests {

        @Test
        fun `client id and a fresh secret are stored and only the presence is echoed`() {
            onThisServer(McpOAuthConfig(authorizationServer = issuer))
            ok("$issuer/.well-known/oauth-authorization-server", metadata())

            val response = service.saveClient(1L, McpOAuthClientRequest(clientId = " harnax-web ", clientSecret = "s3cret"))

            val client = savedClient()
            assertEquals("harnax-web", client.clientId)
            assertEquals("enc:s3cret", client.clientSecretEnc)
            assertEquals("harnax-web", response.clientId)
            assertTrue(response.clientSecretPresent)
            verify(encryptor).resolveSecret(eq("s3cret"), anyOrNull())
        }

        @Test
        fun `an omitted secret keeps the stored ciphertext`() {
            onThisServer(McpOAuthConfig(authorizationServer = issuer))
            val row = storedRow(existingClient())

            service.saveClient(1L, McpOAuthClientRequest(clientId = "harnax-web"))

            assertEquals("stored-ciphertext", row.clientSecretEnc)
            verify(encryptor).resolveSecret(anyOrNull(), eq("stored-ciphertext"))
        }

        @Test
        fun `an empty secret clears it for a public pkce client`() {
            onThisServer(McpOAuthConfig(authorizationServer = issuer))
            val row = storedRow(existingClient())

            val response = service.saveClient(1L, McpOAuthClientRequest(clientId = "harnax-spa", clientSecret = ""))

            assertNull(row.clientSecretEnc)
            assertFalse(response.clientSecretPresent)
        }

        @Test
        fun `a stored row with endpoints is reused without a new request`() {
            onThisServer(McpOAuthConfig(authorizationServer = issuer))
            storedRow(existingClient())

            val response = service.saveClient(1L, McpOAuthClientRequest(clientId = "harnax-web"))

            verify(fetcher, never()).fetch(any())
            assertEquals("$issuer/authorize", response.authorizationEndpoint)
            assertEquals(listOf("read", "write"), response.scopesSupported)
            assertEquals("CONFIG", response.issuerSource)
        }

        @Test
        fun `metadata is fetched when the row exists but has no endpoints yet`() {
            onThisServer(McpOAuthConfig(authorizationServer = issuer))
            val bare = storedRow(
                existingClient().apply {
                    authorizationEndpoint = null
                    tokenEndpoint = null
                    scopesSupported = null
                },
            )
            ok("$issuer/.well-known/oauth-authorization-server", metadata())

            val response = service.saveClient(1L, McpOAuthClientRequest(clientId = "harnax-web"))

            verify(fetcher, times(1)).fetch("$issuer/.well-known/oauth-authorization-server")
            verify(clientMapper, times(2)).updateById(any())
            assertEquals("harnax-web", bare.clientId)
            assertEquals("$issuer/token", bare.tokenEndpoint)
            assertEquals("$issuer/token", response.tokenEndpoint)
        }

        @Test
        fun `without a known issuer the admin is told to run discovery`() {
            onThisServer()

            val error = assertThrows(BizException::class.java) {
                service.saveClient(1L, McpOAuthClientRequest(clientId = "harnax-web"))
            }
            assertTrue(error.message!!.contains("run discovery first"), error.message)
            verify(clientMapper, never()).updateById(any())
        }

        @Test
        fun `an explicit callback url replaces the generated one`() {
            onThisServer(McpOAuthConfig(authorizationServer = issuer))
            storedRow(existingClient())

            val response = service.saveClient(
                1L,
                McpOAuthClientRequest(clientId = "harnax-web", callbackUrl = "https://ops.example.com/cb"),
            )

            assertEquals("https://ops.example.com/cb", response.callbackUrl)
        }

        @Test
        fun `a callback url that is not http(s) is refused`() {
            onThisServer(McpOAuthConfig(authorizationServer = issuer))
            storedRow(existingClient())

            val error = assertThrows(BizException::class.java) {
                service.saveClient(1L, McpOAuthClientRequest(clientId = "harnax-web", callbackUrl = "javascript:alert(1)"))
            }
            assertTrue(error.message!!.contains("must be an http(s) URL"), error.message)
        }

        @Test
        fun `a blank client id is refused even though the api layer also validates it`() {
            onThisServer(McpOAuthConfig(authorizationServer = issuer))
            storedRow(existingClient())

            val error = assertThrows(BizException::class.java) {
                service.saveClient(1L, McpOAuthClientRequest(clientId = "   "))
            }
            assertEquals("client_id cannot be empty", error.message)
            verify(clientMapper, never()).updateById(any())
        }

        @Test
        fun `a stored issuer that is not usable is refused before the row is written`() {
            // The column can be edited by hand or left by an older build; this is the path that
            // attaches a client_secret to it, so it checks the same bar discovery checks.
            onThisServer(McpOAuthConfig(authorizationServer = "file:///etc/passwd"))

            val error = assertThrows(BizException::class.java) {
                service.saveClient(1L, McpOAuthClientRequest(clientId = "harnax-web", clientSecret = "s3cret"))
            }

            assertTrue(error.message!!.contains("must be an http(s) URL"), error.message)
            verifyNoInteractions(clientMapper)
        }

        @Test
        fun `a callback url wider than its column is refused before anything is written`() {
            onThisServer(McpOAuthConfig(authorizationServer = issuer))
            storedRow(existingClient())

            val error = assertThrows(BizException::class.java) {
                service.saveClient(1L, McpOAuthClientRequest(clientId = "harnax-web", callbackUrl = "https://ops.example.com/cb?" + "a".repeat(600)))
            }

            assertTrue(error.message!!.contains("longer than the 500 characters"), error.message)
            verify(clientMapper, never()).updateById(any())
        }
    }

    @Nested
    @DisplayName("Where the browser returns to")
    inner class RedirectUriSourceTests {

        @Test
        fun `the front-end origin wins once it is configured`() {
            onThisServer(McpOAuthConfig(authorizationServer = issuer))
            ok("$issuer/.well-known/oauth-authorization-server", metadata())
            // nginx puts the SPA and /api/admin behind one origin, so production leaves this equal to
            // app.base-url; the value here is the development shape, where they are two ports.
            val withFrontend = serviceWith("$FRONTEND_URL/")

            val response = withFrontend.discover(1L)

            assertEquals("$FRONTEND_URL/mcp/oauth/callback", insertedClient().callbackUrl)
            assertEquals("$FRONTEND_URL/mcp/oauth/callback", response.defaultCallbackUrl)
        }

        @Test
        fun `an unset front-end origin falls back to the api origin rather than answering empty`() {
            onThisServer(McpOAuthConfig(authorizationServer = issuer))
            ok("$issuer/.well-known/oauth-authorization-server", metadata())

            service.discover(1L)

            assertEquals("$BASE_URL/mcp/oauth/callback", insertedClient().callbackUrl)
        }

        private fun serviceWith(frontendBaseUrl: String): McpOAuthServiceImpl = McpOAuthServiceImpl(
            jwtUtil,
            mcpServerService,
            mcpServerMapper,
            clientMapper,
            encryptor,
            fetcher,
            objectMapper,
            BASE_URL,
            frontendBaseUrl,
        )
    }

    companion object {
        private const val BASE_URL = "https://harnax.example.com"
        private const val CALLBACK = "$BASE_URL/mcp/oauth/callback"
        private const val FRONTEND_URL = "http://localhost:8000"
    }
}

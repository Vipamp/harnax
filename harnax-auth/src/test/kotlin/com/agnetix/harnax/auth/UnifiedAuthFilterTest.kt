package com.agnetix.harnax.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import tools.jackson.databind.ObjectMapper

class UnifiedAuthFilterTest {

    private val sharedSecret = "this-is-a-very-secure-shared-secret-key-for-testing"
    private lateinit var tokenProvider: InternalTokenProvider
    private lateinit var objectMapper: ObjectMapper
    private lateinit var filter: UnifiedAuthFilter
    private lateinit var request: HttpServletRequest
    private lateinit var response: HttpServletResponse
    private lateinit var chain: FilterChain

    @BeforeEach
    fun setUp() {
        tokenProvider = InternalTokenProvider("test-service", sharedSecret, 300)
        objectMapper = ObjectMapper()
        request = mock(HttpServletRequest::class.java)
        response = mock(HttpServletResponse::class.java)
        chain = mock(FilterChain::class.java)
        `when`(request.remoteAddr).thenReturn("127.0.0.1")
        filter = UnifiedAuthFilter(
            tokenProvider = tokenProvider,
            enabled = true,
            objectMapper = objectMapper,
        )
    }

    @AfterEach
    fun tearDown() {
        AuthContextHolder.clear()
    }

    @Nested
    inner class DisabledFilter {
        @Test
        fun `passes through when disabled`() {
            val disabledFilter = UnifiedAuthFilter(
                tokenProvider = tokenProvider,
                enabled = false,
                objectMapper = objectMapper,
            )

            disabledFilter.doFilter(request, response, chain)

            verify(chain).doFilter(request, response)
        }
    }

    @Nested
    inner class SkipPaths {
        @Test
        fun `skips health endpoint`() {
            `when`(request.requestURI).thenReturn("/health")

            filter.doFilter(request, response, chain)

            verify(chain).doFilter(request, response)
        }

        @Test
        fun `skips actuator endpoint`() {
            `when`(request.requestURI).thenReturn("/actuator/info")

            filter.doFilter(request, response, chain)

            verify(chain).doFilter(request, response)
        }

        @Test
        fun `rejects unprotected endpoint without credentials`() {
            `when`(request.requestURI).thenReturn("/some/protected/path")
            `when`(response.isCommitted).thenReturn(false)
            val fakeStream = mock(ServletOutputStream::class.java)
            `when`(response.outputStream).thenReturn(fakeStream)

            filter.doFilter(request, response, chain)

            verify(chain, never()).doFilter(request, response)
            verify(response).status = 401
        }

        @Test
        fun `skips extra configured paths`() {
            val filterWithExtra = UnifiedAuthFilter(
                tokenProvider = tokenProvider,
                enabled = true,
                objectMapper = objectMapper,
                extraSkipPaths = listOf("/public", "/swagger"),
            )
            `when`(request.requestURI).thenReturn("/public/docs")

            filterWithExtra.doFilter(request, response, chain)

            verify(chain).doFilter(request, response)
        }

        @Test
        fun `does not skip protected paths`() {
            `when`(request.requestURI).thenReturn("/api/agent/chat")
            `when`(request.getHeader("Authorization")).thenReturn(null)
            `when`(request.getHeader("X-Api-Key")).thenReturn(null)
            `when`(response.isCommitted).thenReturn(false)

            val outputStream = mock(jakarta.servlet.ServletOutputStream::class.java)
            `when`(response.outputStream).thenReturn(outputStream)

            filter.doFilter(request, response, chain)

            verify(response).status = HttpServletResponse.SC_UNAUTHORIZED
            verify(chain, never()).doFilter(request, response)
        }
    }

    @Nested
    inner class BearerTokenAuth {
        @Test
        fun `authenticates valid Bearer token and continues chain`() {
            val token = tokenProvider.generateToken()
            `when`(request.requestURI).thenReturn("/api/agent/chat")
            `when`(request.getHeader("Authorization")).thenReturn("Bearer $token")
            `when`(request.getHeader("X-Caller-Id")).thenReturn("test-service")

            filter.doFilter(request, response, chain)

            verify(chain).doFilter(request, response)
        }

        @Test
        fun `sets AuthContext during filter chain execution`() {
            val token = tokenProvider.generateToken()
            `when`(request.requestURI).thenReturn("/api/agent/chat")
            `when`(request.getHeader("Authorization")).thenReturn("Bearer $token")
            `when`(request.getHeader("X-Caller-Id")).thenReturn("test-service")

            var capturedContext: AuthContext? = null
            doAnswer {
                capturedContext = AuthContextHolder.get()
                null
            }.`when`(chain).doFilter(request, response)

            filter.doFilter(request, response, chain)

            assertNotNull(capturedContext)
            assertEquals("test-service", capturedContext!!.callerId)
            assertEquals(CallerType.INTERNAL_SERVICE, capturedContext!!.callerType)
        }

        @Test
        fun `clears AuthContext after filter chain`() {
            val token = tokenProvider.generateToken()
            `when`(request.requestURI).thenReturn("/api/agent/chat")
            `when`(request.getHeader("Authorization")).thenReturn("Bearer $token")
            `when`(request.getHeader("X-Caller-Id")).thenReturn("test-service")

            filter.doFilter(request, response, chain)

            assertNull(AuthContextHolder.get(), "AuthContext should be cleared after filter")
        }

        @Test
        fun `clears AuthContext even when chain throws`() {
            val token = tokenProvider.generateToken()
            `when`(request.requestURI).thenReturn("/api/agent/chat")
            `when`(request.getHeader("Authorization")).thenReturn("Bearer $token")
            `when`(request.getHeader("X-Caller-Id")).thenReturn("test-service")
            doThrow(RuntimeException("downstream error")).`when`(chain).doFilter(request, response)

            try {
                filter.doFilter(request, response, chain)
            } catch (_: RuntimeException) {}

            assertNull(AuthContextHolder.get(), "AuthContext should be cleared even on error")
        }

        @Test
        fun `rejects invalid Bearer token with 401`() {
            `when`(request.requestURI).thenReturn("/api/agent/chat")
            `when`(request.getHeader("Authorization")).thenReturn("Bearer invalid-token")
            `when`(request.getHeader("X-Caller-Id")).thenReturn("unknown")
            `when`(response.isCommitted).thenReturn(false)

            val outputStream = mock(jakarta.servlet.ServletOutputStream::class.java)
            `when`(response.outputStream).thenReturn(outputStream)

            filter.doFilter(request, response, chain)

            verify(response).status = HttpServletResponse.SC_UNAUTHORIZED
            verify(chain, never()).doFilter(request, response)
        }

        @Test
        fun `rejects expired Bearer token with 401`() {
            val expiredProvider = InternalTokenProvider("test-service", sharedSecret, 1L)
            val token = expiredProvider.generateToken()
            Thread.sleep(1100)

            `when`(request.requestURI).thenReturn("/api/agent/chat")
            `when`(request.getHeader("Authorization")).thenReturn("Bearer $token")
            `when`(request.getHeader("X-Caller-Id")).thenReturn("test-service")
            `when`(response.isCommitted).thenReturn(false)

            val outputStream = mock(jakarta.servlet.ServletOutputStream::class.java)
            `when`(response.outputStream).thenReturn(outputStream)

            filter.doFilter(request, response, chain)

            verify(response).status = HttpServletResponse.SC_UNAUTHORIZED
            verify(chain, never()).doFilter(request, response)
        }
    }

    @Nested
    inner class ApiKeyAuth {
        private lateinit var apiKeyValidator: ExternalApiKeyValidator
        private lateinit var filterWithApi: UnifiedAuthFilter

        @BeforeEach
        fun setUpApiKey() {
            val apiKeyStore = mock(ApiKeyStore::class.java)
            apiKeyValidator = ExternalApiKeyValidator(apiKeyStore)
            filterWithApi = UnifiedAuthFilter(
                tokenProvider = tokenProvider,
                enabled = true,
                objectMapper = objectMapper,
                externalApiKeyValidator = apiKeyValidator,
            )

            val keyHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest("valid-key".toByteArray())
                .joinToString("") { "%02x".format(it) }
            `when`(apiKeyStore.findByKeyHash(keyHash)).thenReturn(
                ApiKeyInfo(
                    name = "external-app",
                    keyHash = keyHash,
                    scopes = setOf("read"),
                    tenantId = 1L,
                    rateLimitPerMinute = 60,
                    enabled = true,
                    expiresAt = null,
                ),
            )
        }

        @Test
        fun `authenticates valid API key`() {
            `when`(request.requestURI).thenReturn("/api/agent/chat")
            `when`(request.getHeader("Authorization")).thenReturn(null)
            `when`(request.getHeader("X-Api-Key")).thenReturn("valid-key")
            `when`(request.getHeader("X-Caller-Id")).thenReturn("unknown")

            filterWithApi.doFilter(request, response, chain)

            verify(chain).doFilter(request, response)
        }

        @Test
        fun `sets AuthContext with EXTERNAL_API type for API key`() {
            `when`(request.requestURI).thenReturn("/api/agent/chat")
            `when`(request.getHeader("Authorization")).thenReturn(null)
            `when`(request.getHeader("X-Api-Key")).thenReturn("valid-key")
            `when`(request.getHeader("X-Caller-Id")).thenReturn("unknown")

            var capturedContext: AuthContext? = null
            doAnswer {
                capturedContext = AuthContextHolder.get()
                null
            }.`when`(chain).doFilter(request, response)

            filterWithApi.doFilter(request, response, chain)

            assertNotNull(capturedContext)
            assertEquals("external-app", capturedContext!!.callerId)
            assertEquals(CallerType.EXTERNAL_API, capturedContext!!.callerType)
            assertEquals(1L, capturedContext!!.tenantId)
            assertEquals(60, capturedContext!!.rateLimitPerMinute)
            assertEquals(setOf("read"), capturedContext!!.scopes)
        }

        @Test
        fun `rejects invalid API key with 401`() {
            `when`(request.requestURI).thenReturn("/api/agent/chat")
            `when`(request.getHeader("Authorization")).thenReturn(null)
            `when`(request.getHeader("X-Api-Key")).thenReturn("bad-key")
            `when`(request.getHeader("X-Caller-Id")).thenReturn("unknown")
            `when`(response.isCommitted).thenReturn(false)

            val outputStream = mock(jakarta.servlet.ServletOutputStream::class.java)
            `when`(response.outputStream).thenReturn(outputStream)

            filterWithApi.doFilter(request, response, chain)

            verify(response).status = HttpServletResponse.SC_UNAUTHORIZED
            verify(chain, never()).doFilter(request, response)
        }

        @Test
        fun `API key ignored when no validator configured`() {
            // filter without externalApiKeyValidator
            `when`(request.requestURI).thenReturn("/api/agent/chat")
            `when`(request.getHeader("Authorization")).thenReturn(null)
            `when`(request.getHeader("X-Api-Key")).thenReturn("valid-key")
            `when`(request.getHeader("X-Caller-Id")).thenReturn("unknown")
            `when`(response.isCommitted).thenReturn(false)

            val outputStream = mock(jakarta.servlet.ServletOutputStream::class.java)
            `when`(response.outputStream).thenReturn(outputStream)

            filter.doFilter(request, response, chain)

            verify(response).status = HttpServletResponse.SC_UNAUTHORIZED
        }
    }

    @Nested
    inner class CredentialPrecedence {
        @Test
        fun `Bearer token takes precedence over API key when both provided`() {
            val apiKeyStore = mock(ApiKeyStore::class.java)
            val filterWithBoth = UnifiedAuthFilter(
                tokenProvider = tokenProvider,
                enabled = true,
                objectMapper = objectMapper,
                externalApiKeyValidator = ExternalApiKeyValidator(apiKeyStore),
            )
            val token = tokenProvider.generateToken()
            `when`(request.requestURI).thenReturn("/api/agent/chat")
            `when`(request.getHeader("Authorization")).thenReturn("Bearer $token")
            `when`(request.getHeader("X-Caller-Id")).thenReturn("test-service")
            `when`(request.getHeader("X-Api-Key")).thenReturn("some-api-key")

            var capturedContext: AuthContext? = null
            doAnswer {
                capturedContext = AuthContextHolder.get()
                null
            }.`when`(chain).doFilter(request, response)

            filterWithBoth.doFilter(request, response, chain)

            verify(chain).doFilter(request, response)
            assertNotNull(capturedContext)
            // Bearer token → INTERNAL_SERVICE, not EXTERNAL_API
            assertEquals(CallerType.INTERNAL_SERVICE, capturedContext!!.callerType)
            assertEquals("test-service", capturedContext!!.callerId)
            // API key store should NOT have been queried
            verifyNoInteractions(apiKeyStore)
        }
    }

    @Nested
    inner class MissingCredentials {
        @Test
        fun `returns 401 when no credentials provided`() {
            `when`(request.requestURI).thenReturn("/api/agent/chat")
            `when`(request.getHeader("Authorization")).thenReturn(null)
            `when`(request.getHeader("X-Api-Key")).thenReturn(null)
            `when`(response.isCommitted).thenReturn(false)

            val outputStream = mock(jakarta.servlet.ServletOutputStream::class.java)
            `when`(response.outputStream).thenReturn(outputStream)

            filter.doFilter(request, response, chain)

            verify(response).status = HttpServletResponse.SC_UNAUTHORIZED
            verify(chain, never()).doFilter(request, response)
        }

        @Test
        fun `returns 401 for non-Bearer Authorization header`() {
            `when`(request.requestURI).thenReturn("/api/agent/chat")
            `when`(request.getHeader("Authorization")).thenReturn("Basic dXNlcjpwYXNz")
            `when`(request.getHeader("X-Api-Key")).thenReturn(null)
            `when`(request.getHeader("X-Caller-Id")).thenReturn("unknown")
            `when`(response.isCommitted).thenReturn(false)

            val outputStream = mock(jakarta.servlet.ServletOutputStream::class.java)
            `when`(response.outputStream).thenReturn(outputStream)

            filter.doFilter(request, response, chain)

            verify(response).status = HttpServletResponse.SC_UNAUTHORIZED
            verify(chain, never()).doFilter(request, response)
        }
    }
}

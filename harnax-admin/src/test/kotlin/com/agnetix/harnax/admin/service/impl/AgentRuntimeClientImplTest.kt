package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.auth.InternalTokenProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import java.util.concurrent.TimeUnit

/**
 * AgentRuntimeClientImpl unit tests
 *
 * MockWebServer stands in for the session-router clear-session proxy: on this path admin sends a single DELETE,
 * and what we prove is that it hits the right path, carries the internal bearer, and returns the runtime's refusal verbatim to the caller.
 *
 * @author agnetix
 * @since 2026-09-23
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentRuntimeClientImplTest {

    companion object {
        private const val SERVICE_ID = "admin"
        private const val TEST_SHARED_SECRET = "unit-test-shared-secret-at-least-32-chars"
        private const val SESSION_ID = "web-11111111-2222-3333-4444-555555555555"
    }

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("/").toString().removeSuffix("/")

    private fun createService(url: String = baseUrl()): AgentRuntimeClientImpl = AgentRuntimeClientImpl(
        url,
        InternalTokenProvider(SERVICE_ID, TEST_SHARED_SECRET, 300),
    )

    private fun vo(code: Int, message: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"code":$code,"message":"$message","data":null,"timestamp":1704067200000}""")

    @Test
    @DisplayName("clearSession - sends one DELETE to the router session proxy")
    fun `clearSession should delete the session through the router`() {
        // Given
        server.enqueue(vo(200, "success"))

        // When
        val result = createService().clearSession(SESSION_ID)

        // Then
        assertEquals(200, result.code)
        val request = server.takeRequest(3, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("DELETE", request!!.method)
        assertEquals("/api/router/agent/session/$SESSION_ID", request.path)
    }

    @Test
    @DisplayName("clearSession - request carries the internal service token")
    fun `clearSession should carry the internal bearer`() {
        // Given - the router's UnifiedAuthFilter accepts a typ=internal bearer as well as an API Key; here it uses the former
        server.enqueue(vo(200, "success"))

        // When
        createService().clearSession(SESSION_ID)

        // Then
        val authorization = server.takeRequest(3, TimeUnit.SECONDS)!!.getHeader("Authorization")
        assertTrue(
            authorization != null && authorization.startsWith("Bearer "),
            "Missing internal service token: $authorization",
        )
    }

    @Test
    @DisplayName("clearSession - treats a not-bound-to-instance router reply as success")
    fun `clearSession should treat an unbound session as released`() {
        // Given - a session that never ran has no runtime state to reclaim, and that is exactly how the router answers
        server.enqueue(vo(200, "Session $SESSION_ID is not bound to any instance"))

        // When & Then
        assertEquals(200, createService().clearSession(SESSION_ID).code)
    }

    @Test
    @DisplayName("clearSession - relays the runtime's business refusal with its own reason")
    fun `clearSession should relay the runtime refusal with its own reason`() {
        // Given - HTTP 200 + non-200 code is the router's fixed failure shape, and the sentence lives in the deleted status
        server.enqueue(vo(500, "sandbox container is busy"))

        // When
        val result = createService().clearSession(SESSION_ID)

        // Then
        assertEquals(500, result.code)
        assertEquals("sandbox container is busy", result.message)
    }

    @Test
    @DisplayName("clearSession - folds an unreachable router into an error instead of throwing")
    fun `clearSession should fold an unreachable router into an error`() {
        // Given - start then shut down, leaving a port that truly has no one listening anymore
        val dead = MockWebServer()
        dead.start()
        val deadPort = dead.port
        dead.shutdown()

        // When
        val result = createService("http://localhost:$deadPort").clearSession(SESSION_ID)

        // Then - the caller relies on this code to decide "refuse deletion"; throwing would only turn deletion into a 500 stack trace
        assertTrue(result.code != 200, "Unreachable should be a business code, actual: ${result.code}")
        assertTrue(result.message.contains("router", ignoreCase = true), "The reason must name which hop broke, actual: ${result.message}")
    }

    @Test
    @DisplayName("clearSession - treats an empty response body as a failure")
    fun `clearSession should treat an empty answer as a failure`() {
        // Given - no ResultVo means no one confirmed the release
        server.enqueue(MockResponse().setResponseCode(204))

        // When
        val result = createService().clearSession(SESSION_ID)

        // Then
        assertTrue(result.code != 200, "No answer cannot count as a successful release, actual: ${result.code}")
    }

    @Test
    @DisplayName("clearSession - a slash in the session id never becomes a second path segment")
    fun `clearSession keeps a slash inside one path segment`() {
        // Given - the path is admin's one and only naming of which runtime state to clear; an id that can add a segment lets it name another endpoint
        server.enqueue(vo(200, "success"))

        // When
        createService().clearSession("web-1/../../admin")

        // Then
        val path = server.takeRequest(3, TimeUnit.SECONDS)!!.path!!
        assertTrue(
            path.startsWith("/api/router/agent/session/web-1%2F") && !path.contains("//"),
            "The slash must stay inside that segment, actual: $path",
        )
    }
}

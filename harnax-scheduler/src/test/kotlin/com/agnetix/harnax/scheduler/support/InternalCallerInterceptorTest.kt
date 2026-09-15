package com.agnetix.harnax.scheduler.support

import com.agnetix.harnax.auth.InternalTokenProvider
import com.agnetix.harnax.scheduler.config.SchedulerWebConfig
import com.agnetix.harnax.scheduler.controller.AgentTaskOwnerController
import com.agnetix.harnax.scheduler.entity.AgentTask
import com.agnetix.harnax.scheduler.mapper.AgentTaskMapper
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import jakarta.servlet.ServletException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.springframework.http.server.PathContainer
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.handler.MappedInterceptor
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.util.Date

/**
 * Contract C4 seen from the scheduler: no request is served on the `/api/scheduler` surface without a bearer
 * this service can verify as coming from another Harnax *service*, and the end-user identity is read from the two
 * headers admin stamps.
 *
 * The two halves are not redundant with each other, and the tests below keep them apart. The bearer decides
 * *whether* a request is served at all; the headers decide *who it is served for*, and they are trusted only
 * because the bearer was verified first. That is also why `X-Forwarded-Tenant` has no reader anywhere in this
 * module: of the three headers it is the one a browser can put on a request itself (spec §2.3).
 *
 * One fact worth having written down, because it changes what the code has to do:
 * `InternalTokenProvider.verifyToken` does **not** throw for a token that carries a `userId` and no
 * `typ=internal` — it classifies that caller as `EXTERNAL_API`. So the caller-type check inside the
 * interceptor is load-bearing, not belt-and-braces; [a user login token signed with the same secret is
 * refused] is what fails first if anyone deletes it.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InternalCallerInterceptorTest {

    companion object {
        private const val SERVICE_ID = "admin"
        private const val SHARED_SECRET = "scheduler-interceptor-test-secret-at-least-32-chars"
    }

    @Mock
    private lateinit var agentTaskMapper: AgentTaskMapper

    private lateinit var tokenProvider: InternalTokenProvider
    private lateinit var interceptor: InternalCallerInterceptor

    @BeforeEach
    fun setUp() {
        tokenProvider = InternalTokenProvider(SERVICE_ID, SHARED_SECRET, 300)
        interceptor = InternalCallerInterceptor(tokenProvider, ObjectMapper())
    }

    @AfterEach
    fun tearDown() {
        CallerContext.clear()
    }

    /** What admin's `AuthRestTemplateInterceptor` sends. */
    private fun internalBearer(): String = "Bearer ${tokenProvider.generateToken()}"

    /**
     * A login token signed with the *same* secret — the shape spec §9 F1 warns about, since one secret value
     * can legitimately cover both roles in a deployment.
     */
    private fun userBearer(): String = Jwts.builder()
        .subject("alice")
        .claim("userId", 7L)
        .claim("tenantId", 3L)
        .issuedAt(Date())
        .expiration(Date(System.currentTimeMillis() + 60_000))
        .signWith(Keys.hmacShaKeyFor(SHARED_SECRET.toByteArray(StandardCharsets.UTF_8)))
        .compact()

    private fun request(vararg headers: Pair<String, String>): MockHttpServletRequest = MockHttpServletRequest("POST", "/api/scheduler/tasks/1/trigger").apply {
        headers.forEach { (name, value) -> addHeader(name, value) }
    }

    private fun preHandle(
        request: MockHttpServletRequest,
        response: MockHttpServletResponse = MockHttpServletResponse(),
    ): Boolean = interceptor.preHandle(request, response, Any())

    // --- the credential ---

    @Test
    @DisplayName("C4: 缺 Authorization → 401，并带 ResultVo 体")
    fun `a request with no bearer is refused with 401 and a ResultVo body`() {
        val response = MockHttpServletResponse()

        assertFalse(preHandle(request(), response), "an unsigned call must not reach the controller")
        assertEquals(401, response.status)
        assertTrue(response.contentType!!.startsWith("application/json"), "body type: ${response.contentType}")
        val body = response.contentAsString
        assertTrue(body.contains("\"code\":401"), "expected a ResultVo-shaped answer, got: $body")
        assertTrue(body.contains("/api/scheduler/tasks/1/trigger"), "the answer should name the path: $body")
    }

    @Test
    @DisplayName("C4: 非 Bearer 形式的 Authorization → 401")
    fun `a non bearer authorization header is refused`() {
        val response = MockHttpServletResponse()

        assertFalse(preHandle(request("Authorization" to internalBearer().removePrefix("Bearer ")), response))
        assertEquals(401, response.status)
    }

    @Test
    @DisplayName("C4: 用户登录 JWT（同密钥、带 userId、无 typ=internal）→ 401")
    fun `a user login token signed with the same secret is refused`() {
        val response = MockHttpServletResponse()

        assertFalse(
            preHandle(request("Authorization" to userBearer(), "X-Forwarded-User" to "alice"), response),
            "a browser token must not unlock the scheduler's surface, whatever key signed it",
        )
        assertEquals(401, response.status)
        assertNull(CallerContext.username, "a refused request must not leave an identity behind")
    }

    @Test
    @DisplayName("C4: 签错密钥的 token → 401")
    fun `a token signed with another secret is refused`() {
        val foreign = InternalTokenProvider("someone-else", "a-different-secret-value-of-at-least-32-chars!", 300)
        val response = MockHttpServletResponse()

        assertFalse(preHandle(request("Authorization" to "Bearer ${foreign.generateToken()}"), response))
        assertEquals(401, response.status)
    }

    @Test
    @DisplayName("C4: 内部 JWT → 放行")
    fun `an internal token is let through`() {
        assertTrue(preHandle(request("Authorization" to internalBearer(), "X-Forwarded-User" to "alice")))
    }

    @Test
    @DisplayName("C4: 内部 JWT 不带身份头也放行（冷路径没有终端用户）")
    fun `an internal token with no identity headers is still served`() {
        // C5's owner read happens while a task runs: agent-service asked admin for an agent spec, and there is
        // no user JWT anywhere on that chain. Requiring the headers would turn "no browser is driving this"
        // into a refusal, and the visible failure would be one silently missing MCP tool.
        assertTrue(preHandle(request("Authorization" to internalBearer())))
        assertNull(CallerContext.username)
        assertNull(CallerContext.tenantId)
        assertEquals(SERVICE_ID, CallerContext.callerId, "the verified caller is still worth knowing")
    }

    // --- the identity headers ---

    @Test
    @DisplayName("C4: X-Forwarded-User 与 X-Tenant-Id 都写进 CallerContext")
    fun `the two headers admin stamps are read into the caller context`() {
        assertTrue(
            preHandle(
                request(
                    "Authorization" to internalBearer(),
                    "X-Forwarded-User" to "alice",
                    "X-Tenant-Id" to "5",
                ),
            ),
        )
        assertEquals("alice", CallerContext.username)
        assertEquals(5L, CallerContext.tenantId)
    }

    @Test
    @DisplayName("C4: X-Forwarded-Tenant 即使浏览器发了也不读")
    fun `the tenant header a browser can forge is ignored`() {
        assertTrue(
            preHandle(
                request(
                    "Authorization" to internalBearer(),
                    "X-Forwarded-User" to "alice",
                    "X-Tenant-Id" to "5",
                    "X-Forwarded-Tenant" to "999",
                ),
            ),
        )
        assertEquals(5L, CallerContext.tenantId, "X-Forwarded-Tenant must never win over X-Tenant-Id")

        // On its own it buys nothing either: still served, still no tenant.
        assertTrue(preHandle(request("Authorization" to internalBearer(), "X-Forwarded-Tenant" to "999")))
        assertNull(CallerContext.tenantId)
    }

    @Test
    @DisplayName("C4: 非数字的 X-Tenant-Id 当作没有，不影响放行")
    fun `an unparseable tenant is dropped rather than failing the request`() {
        assertTrue(
            preHandle(
                request(
                    "Authorization" to internalBearer(),
                    "X-Forwarded-User" to "alice",
                    "X-Tenant-Id" to "not-a-number",
                ),
            ),
        )
        assertEquals("alice", CallerContext.username)
        assertNull(CallerContext.tenantId)
    }

    // --- the context's lifecycle ---

    @Test
    @DisplayName("C4: postHandle 不清、afterCompletion 清")
    fun `the identity outlives postHandle and is gone after afterCompletion`() {
        val request = request("Authorization" to internalBearer(), "X-Forwarded-User" to "alice")
        val response = MockHttpServletResponse()
        assertTrue(interceptor.preHandle(request, response, Any()))

        // Clearing in postHandle is what this file exists to rule out: postHandle runs *after* the handler and
        // is skipped entirely on the exception path.
        interceptor.postHandle(request, response, Any(), null)
        assertEquals("alice", CallerContext.username)

        interceptor.afterCompletion(request, response, Any(), null)
        assertNull(CallerContext.username)
    }

    @Test
    @DisplayName("C4: 一次正常请求结束后不留身份")
    fun `a served request leaves no caller behind on its own thread`() {
        val mockMvc = mockMvc(AgentTaskOwnerController(agentTaskMapper))
        whenever(agentTaskMapper.selectAnyById(7L)).thenReturn(null)

        mockMvc.perform(
            get("/api/scheduler/agent-tasks/7/owner")
                .header("Authorization", internalBearer())
                .header("X-Forwarded-User", "alice"),
        ).andExpect(status().isOk)

        assertNull(CallerContext.username, "afterCompletion has to clear on the happy path too")
    }

    @Test
    @DisplayName("C4: handler 抛异常后也不留身份")
    fun `a thrown handler exception cannot leak the caller onto a reused thread`() {
        // The reason the clear lives in afterCompletion and not postHandle: on this path Spring skips
        // postHandle, and the container thread goes back into the pool still holding somebody's username.
        val mockMvc = mockMvc(ThrowingController())
        assertNull(CallerContext.username)

        val thrown = assertThrows(ServletException::class.java) {
            mockMvc.perform(
                get("/api/scheduler/boom")
                    .header("Authorization", internalBearer())
                    .header("X-Forwarded-User", "alice")
                    .header("X-Tenant-Id", "5"),
            ).andReturn()
        }
        // This is the path the test is about: the handler threw, so Spring skipped postHandle.
        assertTrue(
            thrown.cause is IllegalStateException,
            "the ServletException must be the handler's own failure, got $thrown",
        )
        assertNull(CallerContext.username, "a caller identity must never survive onto the next request thread")
        assertNull(CallerContext.tenantId)
    }

    // --- coverage of the surface, reads included ---

    @Test
    @DisplayName("C4 + C5: 未签名的属主查询读不到，且不查库")
    fun `the owner read cannot be served without a token`() {
        val mockMvc = mockMvc(AgentTaskOwnerController(agentTaskMapper))

        mockMvc.perform(get("/api/scheduler/agent-tasks/7/owner"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value(401))

        verifyNoInteractions(agentTaskMapper)
    }

    @Test
    @DisplayName("C4 + C5: 带内部 JWT 的属主查询照常 200")
    fun `the owner read is served for an internal token`() {
        whenever(agentTaskMapper.selectAnyById(7L)).thenReturn(
            AgentTask().apply {
                id = 7L
                creator = "bob"
                tenantId = 5L
                agentId = 9L
            },
        )
        val mockMvc = mockMvc(AgentTaskOwnerController(agentTaskMapper))

        mockMvc.perform(get("/api/scheduler/agent-tasks/7/owner").header("Authorization", internalBearer()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.creator").value("bob"))
    }

    @Test
    @DisplayName("C4: 注册范围恰为 /api/scheduler/**，无豁免；actuator 探针在其外")
    fun `the registration covers the whole scheduler api and nothing else`() {
        val registry = RegistryProbe()
        SchedulerWebConfig(tokenProvider, ObjectMapper()).addInterceptors(registry)

        val mapped = registry.registered.single() as MappedInterceptor
        val include = mapped.includePathPatterns!!
        assertEquals(listOf("/api/scheduler/**"), include.toList())
        assertTrue(
            mapped.excludePathPatterns.orEmpty().isEmpty(),
            "no exclusion: the read surface is covered too, see the deviation note in the interceptor",
        )

        // The same patterns matched the way MVC matches them, so the probes staying outside is checked rather
        // than asserted by comment.
        val patterns = PathPatternParser().let { parser -> include.map { parser.parse(it) } }
        listOf(
            "/api/scheduler/tasks/status",
            "/api/scheduler/reload",
            "/api/scheduler/tasks/logs/9/stop",
            "/api/scheduler/agent-tasks/7/owner",
        ).forEach { path ->
            assertTrue(matches(patterns, path), "$path is exactly the surface this release locks down")
        }
        listOf("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness", "/actuator/prometheus").forEach { path ->
            assertFalse(matches(patterns, path), "$path has to stay open — compose probes it every 30s")
        }
    }

    private fun matches(patterns: List<PathPattern>, path: String): Boolean = patterns.any { it.matches(PathContainer.parsePath(path)) }

    // --- helpers ---

    private fun mockMvc(handler: Any): MockMvc = standaloneSetup(handler)
        .addInterceptors(interceptor)
        .build()

    /**
     * `InterceptorRegistry.getInterceptors()` is protected, so a subclass is the only way to read back what
     * [SchedulerWebConfig] registered — the fact worth pinning here, since a narrowed pattern list would stay
     * invisible to every other test in this file.
     */
    private class RegistryProbe : InterceptorRegistry() {
        val registered: List<Any>
            get() = getInterceptors().toList()
    }

    /**
     * A handler that fails after the interceptor has let it through. Annotated because that is what makes
     * standalone MockMvc see it as a handler at all: without it the request resolves to no endpoint, no
     * interceptor runs, and this test would be proving nothing.
     */
    @RestController
    class ThrowingController {

        @GetMapping("/api/scheduler/boom")
        fun boom(): String = throw IllegalStateException("handler blew up")
    }
}

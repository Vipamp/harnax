package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.service.AgentService
import com.agnetix.harnax.admin.service.impl.SchedulerClientImpl
import com.agnetix.harnax.auth.InternalTokenProvider
import com.agnetix.harnax.entity.Agent
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * The forwarding contract of [AgentTaskController], end to end over a real HTTP hop.
 *
 * Release 2 left this service with two jobs on the task endpoints — name the caller, then relay — and both are
 * only testable against something on the other end of a socket. A [MockWebServer] stands in for
 * `harnax-scheduler` and answers with samples taken from that service's own responses, and every case asserts
 * the same three things:
 *
 *  - the outgoing method, path and query, because a forwarded call to the wrong path is answered by the wrong
 *    rules (the older `api/scheduler/tasks` surface, for one, whose answers carry a payload this API never
 *    showed a client);
 *  - the two contract-C4 identity headers, because the scheduler owns the owner-scoped rules now and reads the
 *    caller from `X-Forwarded-User` — a forward that drops it is a forward that answers for nobody;
 *  - the response body field for field, because that body *is* the browser-visible contract: the `ResultVo`
 *    shell, the seven keys of the page envelope, the eighteen fields of a task record, and the business codes
 *    the webui's catch branches switch on. No case here normalises, reorders or re-types a single key.
 *
 * The wire-level part matters: a unit test over a mocked [com.agnetix.harnax.admin.service.SchedulerClient]
 * cannot see the encoding of a timestamp filter, the header stamping, or a null that a mirror DTO would quietly
 * drop — and dropping one is exactly the drift the JSON-shaped forward exists to prevent, so one case feeds a
 * `lastRunStatus` that is present and null and asks for it back.
 *
 * `GET /agents` is here too, and asserts the opposite: it is admin's own domain, so it must not forward at all.
 */
@DisplayName("AgentTaskController forwarding contract")
class AgentTaskForwardingContractTest {

    private companion object {
        const val SERVICE_ID = "admin"
        const val TEST_SHARED_SECRET = "unit-test-shared-secret-at-least-32-chars"
        const val CALLER = "alice"
        const val TENANT = 5L

        /**
         * What `harnax-scheduler`'s `/api/scheduler/agent-tasks/page` answers today: the seven keys of the
         * envelope, and a record with all eighteen fields named — `lastRunStatus` and `lastRunTime` present with
         * a null value, for a task that has never run.
         */
        const val PAGE_SAMPLE = """
            {"code":200,"message":"success","data":{
              "pageNum":2,"pageSize":20,"total":1,
              "records":[{"id":7,"tenantId":5,"name":"Daily News","agentId":100,"agentName":"News Agent",
                "prompt":"Summarize today's news","cronExpression":"0 0 9 * * ?","taskStatus":1,"concurrent":0,
                "timeoutSeconds":300,"description":"daily","isPublic":0,"creator":"alice","active":1,
                "createTime":"2026-07-01T09:00:00","updateTime":"2026-07-02T09:00:00",
                "lastRunStatus":null,"lastRunTime":null}],
              "pages":1,"hasPrevious":true,"hasNext":false},
            "timestamp":1704067200000}
        """

        /** `GET /api/scheduler/agent-tasks/7`, the same eighteen fields as a non-null last run. */
        const val TASK_SAMPLE = """
            {"code":200,"message":"success","data":{"id":7,"tenantId":5,"name":"Daily News","agentId":100,
              "agentName":"News Agent","prompt":"Summarize today's news","cronExpression":"0 0 9 * * ?",
              "taskStatus":0,"concurrent":0,"timeoutSeconds":300,"description":"daily","isPublic":0,
              "creator":"alice","active":1,"createTime":"2026-07-01T09:00:00",
              "updateTime":"2026-07-02T09:00:00","lastRunStatus":1,"lastRunTime":"2026-07-02T09:00:01"},
            "timestamp":1704067200000}
        """

        /** The log list envelope, with the fourteen fields of one finished run. */
        const val LOG_PAGE_SAMPLE = """
            {"code":200,"message":"success","data":{
              "pageNum":1,"pageSize":20,"total":1,
              "records":[{"id":41,"taskId":7,"taskName":"Daily News","prompt":"Summarize today's news",
                "response":"done","sessionId":"task-7-100-6f1a","status":1,"errorInfo":"","tokenUsage":"{}",
                "startTime":"2026-07-02T09:00:00","endTime":"2026-07-02T09:00:05","durationMs":5000,
                "creator":"alice","createTime":"2026-07-02T09:00:00"}],
              "pages":1,"hasPrevious":false,"hasNext":false},
            "timestamp":1704067200000}
        """

        const val SUCCESS_SAMPLE = """{"code":200,"message":"success","data":null,"timestamp":1704067200000}"""

        /** The 40902 sentence, byte for byte, from a delete that committed but reloaded nothing. */
        const val SYNC_FAILED_SAMPLE = """
            {"code":40902,"message":"Task deleted, but the scheduler did not reload: connect timed out. Its orphaned job will not run the deleted task — it deletes itself on its next fire, or with the 60s sweep.","data":null,"timestamp":1704067200000}
        """
    }

    private lateinit var mockMvc: MockMvc
    private lateinit var agentService: AgentService
    private lateinit var server: MockWebServer

    private val objectMapper = ObjectMapper()

    /** The body the mock scheduler answers with, and the status it answers with (200 unless a case says so). */
    private var answer: String = SUCCESS_SAMPLE
    private var answerStatus: Int = 200

    @BeforeEach
    fun setUp() {
        agentService = mock(AgentService::class.java)
        server = MockWebServer().apply {
            // One dispatcher instead of `enqueue`, so the answer and the status a case wants are just fields on
            // the case: a queue is never read while a dispatcher is installed.
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                    .setResponseCode(answerStatus)
                    .setHeader("Content-Type", "application/json")
                    .setBody(answer)
            }
            start()
        }
        val schedulerClient = SchedulerClientImpl(
            "http://localhost:${server.port}",
            InternalTokenProvider(SERVICE_ID, TEST_SHARED_SECRET, 300),
        )
        mockMvc = MockMvcBuilders.standaloneSetup(AgentTaskController(schedulerClient, agentService)).build()

        // The caller this forward is made for. Both halves are thread-locals the real request fills — the
        // authentication from JwtAuthenticationFilter, the tenant from the tenant filter — and the client
        // stamps them on every call out.
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            CALLER,
            null,
            listOf(SimpleGrantedAuthority("ROLE_USER")),
        )
        TenantContext.setTenantId(TENANT)
        stubAgent(100L, "News Agent")
        stubAgent(200L, "Weather Agent")
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        SecurityContextHolder.clearContext()
        TenantContext.clear()
    }

    private fun stubAgent(id: Long, name: String) {
        `when`(agentService.getAgent(id)).thenReturn(
            Agent().apply {
                this.id = id
                this.name = name
            },
        )
    }

    // ==================== assertions ====================

    /**
     * Relay, in both directions: the answer came back exactly as it went in, and the caller it was made for is
     * named on the wire.
     */
    private fun assertForwarded(
        method: HttpMethod,
        expectedPath: String,
        result: MvcResult,
        expectedBody: String,
    ) {
        val request = takeRequest()
        assertEquals(method.toString(), request.method, "outgoing method of $expectedPath")
        assertEquals(expectedPath, request.path, "outgoing path+query")
        assertIdentity(request)
        assertRelayed(expectedBody, result)
    }

    private fun assertIdentity(request: RecordedRequest) {
        assertEquals(
            CALLER,
            request.getHeader("X-Forwarded-User"),
            "the scheduler owns the owner-scoped rules now, so a forward without the caller names nobody",
        )
        assertEquals(TENANT.toString(), request.getHeader("X-Tenant-Id"))
        assertNull(
            request.getHeader("X-Forwarded-Tenant"),
            "the header a browser can set for itself must never be forwarded",
        )
        val authorization = request.getHeader("Authorization")
        assertTrue(
            authorization != null && authorization.startsWith("Bearer "),
            "contract C4 refuses this surface without an internal bearer: $authorization",
        )
    }

    /**
     * Field for field, key order aside; a missing key is as much a break as a changed one.
     *
     * The one exception is the shell's `isSuccess`: that is not a field either service writes but the derived
     * getter of the shared `ResultVo`, recomputed here from the code that came back. It is therefore asserted to
     * agree with the relayed code instead of being compared against the sample, and every other key — the whole
     * payload included — has to match as it came.
     */
    private fun assertRelayed(expectedBody: String, result: MvcResult) {
        val expected = objectMapper.readTree(expectedBody.trim())
        val actual = objectMapper.readTree(result.response.contentAsString) as ObjectNode
        val derived = actual.remove("isSuccess")
        assertEquals(expected.path("code").asInt() == 200, derived?.asBoolean(), "isSuccess must follow the relayed code")
        assertEquals(expected, actual, "relayed body (sent: $expected, got: $actual)")
    }

    private fun takeRequest(): RecordedRequest {
        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertTrue(request != null, "a request should have reached the scheduler stand-in")
        return request!!
    }

    /** The query as the other service binds it: names, order and decoded values all still the client's. */
    private fun queryOf(path: String): List<Pair<String, String>> = path
        .substringAfter('?', "")
        .takeIf { it.isNotEmpty() }
        ?.split('&')
        .orEmpty()
        .filter { it.isNotBlank() }
        .map { pair ->
            val name = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            URLDecoder.decode(name, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
        }

    // ==================== GET /page ====================

    @Nested
    @DisplayName("GET /api/admin/agent-tasks/page")
    inner class PageEndpoint {

        @Test
        fun `every filter goes out as a query parameter and the page comes back untouched`() {
            answer = PAGE_SAMPLE

            val result = mockMvc.perform(
                get("/api/admin/agent-tasks/page")
                    .param("name", "Daily News")
                    .param("agentId", "100")
                    .param("taskStatus", "1")
                    .param("pageNum", "2")
                    .param("pageSize", "20"),
            )
                .andExpect(status().isOk)
                .andReturn()

            val request = takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/scheduler/agent-tasks/page", request.path!!.substringBefore('?'))
            assertEquals(
                listOf("name" to "Daily News", "agentId" to "100", "taskStatus" to "1", "pageNum" to "2", "pageSize" to "20"),
                queryOf(request.path!!),
                "the five list filters, in the order this endpoint declares them",
            )
            assertIdentity(request)
            assertRelayed(PAGE_SAMPLE, result)
        }

        /**
         * The page envelope's seven keys, one of them present with a null value. This is the case that a mirror
         * DTO in this service would have broken: a re-typed record either drops the key or invents a default,
         * and the task list reads `lastRunStatus` null as "never run" on screen.
         */
        @Test
        fun `a present-but-null lastRunStatus survives the relay`() {
            answer = PAGE_SAMPLE

            val result = mockMvc.perform(get("/api/admin/agent-tasks/page")).andReturn()

            val data = objectMapper.readTree(result.response.contentAsString).path("data")
            assertEquals(7, data.size(), "the seven keys three clients read, none added and none dropped")
            val record = data.path("records").path(0)
            assertEquals(18, record.size(), "a task record carries all eighteen fields")
            assertTrue(record.has("lastRunStatus"), "the key must still be there")
            assertTrue(record.path("lastRunStatus").isNull, "and still null, not dropped and not defaulted")
            assertTrue(record.path("lastRunTime").isNull, "the same for the second join-only field")
        }

        @Test
        fun `absent filters are left off rather than sent empty`() {
            answer = PAGE_SAMPLE

            mockMvc.perform(get("/api/admin/agent-tasks/page").param("pageNum", "1").param("pageSize", "10"))
                .andExpect(status().isOk)

            assertEquals(listOf("pageNum" to "1", "pageSize" to "10"), queryOf(takeRequest().path!!))
        }

        @Test
        fun `the scheduler's failure sentence is relayed inside the same shell`() {
            answer = """{"code":500,"message":"Failed to query agent task list: boom","data":null,"timestamp":1704067200000}"""

            val result = mockMvc.perform(get("/api/admin/agent-tasks/page"))
                .andExpect(status().isOk)
                .andReturn()

            assertRelayed(answer, result)
        }
    }

    // ==================== GET /{id} ====================

    @Nested
    @DisplayName("GET /api/admin/agent-tasks/{id}")
    inner class GetByIdEndpoint {

        @Test
        fun `the task the scheduler names comes back as it was written`() {
            answer = TASK_SAMPLE

            val result = mockMvc.perform(get("/api/admin/agent-tasks/7"))
                .andExpect(status().isOk)
                .andReturn()

            assertForwarded(HttpMethod.GET, "/api/scheduler/agent-tasks/7", result, TASK_SAMPLE)
        }

        /**
         * "Agent task not found" is now the scheduler's answer rather than this service's: the row it used to
         * look for lives there. Same code, same sentence, because the clients parse it.
         */
        @Test
        fun `a not-found answer is the scheduler's and is passed straight through`() {
            answer = """{"code":500,"message":"Agent task not found","data":null,"timestamp":1704067200000}"""

            val result = mockMvc.perform(get("/api/admin/agent-tasks/424242"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("Agent task not found"))
                .andReturn()

            assertForwarded(HttpMethod.GET, "/api/scheduler/agent-tasks/424242", result, answer)
        }
    }

    // ==================== POST (create) ====================

    @Nested
    @DisplayName("POST /api/admin/agent-tasks")
    inner class CreateEndpoint {

        @Test
        fun `create forwards the body with the agent name this service resolves`() {
            answer = SUCCESS_SAMPLE

            val result = mockMvc.perform(
                post("/api/admin/agent-tasks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"Daily News","agentId":100,"prompt":"hi","cronExpression":"0 0 9 * * ?"}"""),
            )
                .andExpect(status().isOk)
                .andReturn()

            val request = takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/scheduler/agent-tasks", request.path)
            assertIdentity(request)
            val sent = objectMapper.readTree(request.body.readUtf8())
            assertEquals("News Agent", sent.path("agentName").asText(), "agent_task.agent_name is admin's data to name")
            assertEquals("Daily News", sent.path("name").asText(), "the rest of the body goes out as it came")
            assertEquals("hi", sent.path("prompt").asText())
            assertEquals("0 0 9 * * ?", sent.path("cronExpression").asText())
            assertRelayed(SUCCESS_SAMPLE, result)
        }

        /**
         * The scheduler cannot look an agent up any more, so an id this service does not know is forwarded with
         * no name and answered there with "Agent not found" — the sentence this endpoint has always used for
         * that case. Naming it here as well would put a second copy of a contract text in the stack.
         */
        @Test
        fun `an agent this service cannot resolve is forwarded unnamed and refused by the scheduler`() {
            answer = """{"code":500,"message":"Failed to create agent task: Agent not found","data":null,"timestamp":1704067200000}"""

            val result = mockMvc.perform(
                post("/api/admin/agent-tasks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"Ghost","agentId":999999,"prompt":"hi","cronExpression":"0 0 9 * * ?"}"""),
            )
                .andExpect(status().isOk)
                .andReturn()

            val sent = objectMapper.readTree(takeRequest().body.readUtf8())
            assertFalse(sent.has("agentName"), "nothing was resolved, so nothing may be claimed")
            assertRelayed(answer, result)
        }

        /**
         * A number sent as text used to reach a typed DTO and bind to a `Long`. The forward reads the raw tree
         * now, so the lenient read is what keeps this stamping rather than skipping it and answering a legal
         * create with "Agent not found".
         */
        @Test
        fun `an agentId carried as a string is still resolved and stamped`() {
            answer = SUCCESS_SAMPLE

            mockMvc.perform(
                post("/api/admin/agent-tasks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"Daily News","agentId":"100","prompt":"hi","cronExpression":"0 0 9 * * ?"}"""),
            ).andExpect(status().isOk)

            assertEquals("News Agent", objectMapper.readTree(takeRequest().body.readUtf8()).path("agentName").asText())
        }

        @Test
        fun `a body the other service rejects keeps its 400 code and its field-colon-message text`() {
            // HTTP 400 on the wire, because bean validation answers that way; the body is still the answer the
            // client reads, so the relay must not fold it into a transport error.
            answer = """{"code":400,"message":"prompt: Prompt is required","data":null,"timestamp":1704067200000}"""
            answerStatus = 400

            val result = mockMvc.perform(
                post("/api/admin/agent-tasks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"Daily News","agentId":100,"prompt":"","cronExpression":"0 0 9 * * ?"}"""),
            )
                .andExpect(status().isOk)
                .andReturn()

            assertRelayed(answer, result)
        }
    }

    // ==================== PUT /{id} (update) ====================

    @Nested
    @DisplayName("PUT /api/admin/agent-tasks/{id}")
    inner class UpdateEndpoint {

        /**
         * The rename case release 2's handoff named: an update that moves a task to another agent has to carry
         * that agent's name, because the scheduler has no `agent` table to read it from. Without this, a legal
         * edit is answered "Agent not found".
         */
        @Test
        fun `an update that changes agentId re-resolves and stamps the name`() {
            answer = SUCCESS_SAMPLE

            val result = mockMvc.perform(
                put("/api/admin/agent-tasks/7")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"agentId":200}"""),
            )
                .andExpect(status().isOk)
                .andReturn()

            val request = takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/api/scheduler/agent-tasks/7", request.path)
            assertIdentity(request)
            assertEquals("Weather Agent", objectMapper.readTree(request.body.readUtf8()).path("agentName").asText())
            assertRelayed(SUCCESS_SAMPLE, result)
        }

        @Test
        fun `an update that names no agent is forwarded exactly as it came`() {
            answer = SUCCESS_SAMPLE

            mockMvc.perform(
                put("/api/admin/agent-tasks/7")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"prompt":"New prompt"}"""),
            ).andExpect(status().isOk)

            assertEquals(
                objectMapper.readTree("""{"prompt":"New prompt"}"""),
                objectMapper.readTree(takeRequest().body.readUtf8()),
                "a prompt-only edit must not grow fields",
            )
        }

        /**
         * 40902 means "stored, but nothing reloaded", and the UI downgrades it to a warning instead of a failed
         * edit. That only works if the code arrives in the body of an OK response, which is how the webui's
         * catch branch reads `error.info.errorCode`.
         */
        @Test
        fun `the sync business code reaches the browser as a business code`() {
            answer = """
                {"code":40902,"message":"Task saved, but the scheduler did not reload: read timed out. The previous definition stays scheduled until a reconcile round converges it (the 60s cluster sweep runs one anyway).","data":null,"timestamp":1704067200000}
            """

            val result = mockMvc.perform(
                put("/api/admin/agent-tasks/7")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"prompt":"New prompt"}"""),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(40902))
                .andReturn()

            assertRelayed(answer, result)
        }
    }

    // ==================== DELETE /{id} ====================

    @Nested
    @DisplayName("DELETE /api/admin/agent-tasks/{id}")
    inner class DeleteEndpoint {

        @Test
        fun `delete forwards the id and relays the answer including the 40902 sentence`() {
            answer = SYNC_FAILED_SAMPLE

            val result = mockMvc.perform(delete("/api/admin/agent-tasks/7"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(40902))
                .andReturn()

            assertForwarded(HttpMethod.DELETE, "/api/scheduler/agent-tasks/7", result, SYNC_FAILED_SAMPLE)
        }

        @Test
        fun `the creator gate on a delete is the scheduler's answer now`() {
            answer = """{"code":400,"message":"Only the task creator can delete this task","data":null,"timestamp":1704067200000}"""

            val result = mockMvc.perform(delete("/api/admin/agent-tasks/7"))
                .andExpect(status().isOk)
                .andReturn()

            assertRelayed(answer, result)
        }
    }

    // ==================== scheduling verbs ====================

    @Nested
    @DisplayName("POST the scheduling verbs")
    inner class SchedulingEndpoints {

        /**
         * Table-driven because the four paths are the claim: `/agent-tasks/...` and not the older
         * `api/scheduler/tasks` surface, whose success answers carry the human string ("Task started") in `data`
         * and would hand a task-API client a payload shape it has never seen.
         */
        @ParameterizedTest(name = "{0}")
        @CsvSource(
            "start,/api/admin/agent-tasks/7/start,/api/scheduler/agent-tasks/7/start",
            "pause,/api/admin/agent-tasks/7/pause,/api/scheduler/agent-tasks/7/pause",
            "trigger,/api/admin/agent-tasks/7/trigger,/api/scheduler/agent-tasks/7/trigger",
        )
        fun `a verb is forwarded to the task surface and answered with no data payload`(
            name: String,
            adminPath: String,
            schedulerPath: String,
        ) {
            answer = SUCCESS_SAMPLE

            val result = mockMvc.perform(post(adminPath))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andReturn()

            assertForwarded(HttpMethod.POST, schedulerPath, result, SUCCESS_SAMPLE)
            assertFalse(
                result.response.contentAsString.contains("Task started"),
                "$name must not leak a payload this API never returned",
            )
        }

        @Test
        fun `a stop goes out by log id`() {
            answer = SUCCESS_SAMPLE

            val result = mockMvc.perform(post("/api/admin/agent-tasks/logs/77/stop"))
                .andExpect(status().isOk)
                .andReturn()

            assertForwarded(HttpMethod.POST, "/api/scheduler/agent-tasks/logs/77/stop", result, SUCCESS_SAMPLE)
        }

        @Test
        fun `a trigger that hit a live execution keeps the 40901 the client switches on`() {
            answer = """{"code":40901,"message":"Task execution is already in progress","data":null,"timestamp":1704067200000}"""

            val result = mockMvc.perform(post("/api/admin/agent-tasks/7/trigger"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(40901))
                .andReturn()

            assertRelayed(answer, result)
        }

        @Test
        fun `toggle forwards the status as a query parameter`() {
            answer = SUCCESS_SAMPLE

            val result = mockMvc.perform(post("/api/admin/agent-tasks/toggle/7").param("status", "1"))
                .andExpect(status().isOk)
                .andReturn()

            val request = takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/scheduler/agent-tasks/toggle/7?status=1", request.path)
            assertIdentity(request)
            assertRelayed(SUCCESS_SAMPLE, result)
        }

        /**
         * The status switch is the request a standby node refuses, and 40903 is the only thing an operator can
         * act on. The gate moved with the data, so the refusal arrives from the other service.
         */
        @Test
        fun `toggle keeps the inert-node code the UI switch reads`() {
            answer = """{"code":40903,"message":"Scheduling is disabled on this instance","data":null,"timestamp":1704067200000}"""

            val result = mockMvc.perform(post("/api/admin/agent-tasks/toggle/7").param("status", "0"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(40903))
                .andReturn()

            assertRelayed(answer, result)
        }

        @Test
        fun `a toggle of a task the caller may not see is the scheduler's not-found answer`() {
            answer = """{"code":400,"message":"Agent task not found","data":null,"timestamp":1704067200000}"""

            val result = mockMvc.perform(post("/api/admin/agent-tasks/toggle/99999999").param("status", "1"))
                .andExpect(status().isOk)
                .andReturn()

            assertForwarded(HttpMethod.POST, "/api/scheduler/agent-tasks/toggle/99999999?status=1", result, answer)
        }
    }

    // ==================== GET /{id}/logs ====================

    @Nested
    @DisplayName("GET /api/admin/agent-tasks/{id}/logs")
    inner class LogsEndpoint {

        /**
         * All seven log-screen controls in one request: the task this list belongs to is the path id, the rest
         * are carried through as named, in the order the endpoint declares them, and a timestamp filter keeps
         * its spaces and colons across the hop — which is the part only a real wire can answer.
         */
        @Test
        fun `the seven log filters go out unchanged and the page comes back untouched`() {
            answer = LOG_PAGE_SAMPLE

            val result = mockMvc.perform(
                get("/api/admin/agent-tasks/7/logs")
                    .param("taskName", "Daily News")
                    .param("status", "1")
                    .param("startTimeFrom", "2026-07-01 00:00:00")
                    .param("startTimeTo", "2026-07-31 23:59:59")
                    .param("keyword", "error rate")
                    .param("pageNum", "1")
                    .param("pageSize", "20"),
            )
                .andExpect(status().isOk)
                .andReturn()

            val request = takeRequest()
            val path = request.path!!
            assertEquals("GET", request.method)
            assertEquals("/api/scheduler/agent-tasks/7/logs", path.substringBefore('?'))
            assertEquals(
                listOf(
                    "taskName" to "Daily News",
                    "status" to "1",
                    "startTimeFrom" to "2026-07-01 00:00:00",
                    "startTimeTo" to "2026-07-31 23:59:59",
                    "keyword" to "error rate",
                    "pageNum" to "1",
                    "pageSize" to "20",
                ),
                queryOf(path),
                "seven parameters, same names, same values",
            )
            assertFalse(path.contains(' '), "the value has to travel encoded or it does not travel at all")
            assertIdentity(request)
            assertRelayed(LOG_PAGE_SAMPLE, result)
        }

        @Test
        fun `an absent filter is not sent`() {
            answer = LOG_PAGE_SAMPLE

            mockMvc.perform(get("/api/admin/agent-tasks/7/logs"))

            assertEquals(
                listOf("pageNum" to "1", "pageSize" to "10"),
                queryOf(takeRequest().path!!),
                "only the paging defaults this endpoint declares are carried",
            )
        }

        @Test
        fun `a failing log read keeps its sentence on the way out`() {
            answer = """{"code":500,"message":"Failed to query agent task logs: boom","data":null,"timestamp":1704067200000}"""

            val result = mockMvc.perform(get("/api/admin/agent-tasks/7/logs"))
                .andExpect(status().isOk)
                .andReturn()

            assertRelayed(answer, result)
        }
    }

    // ==================== GET /agents (not forwarded) ====================

    @Nested
    @DisplayName("GET /api/admin/agent-tasks/agents stays local")
    inner class AgentsEndpoint {

        @Test
        fun `the agent list is answered from this service and never forwarded`() {
            `when`(agentService.getActiveAgents()).thenReturn(
                listOf(
                    Agent().apply {
                        id = 1L
                        name = "Agent A"
                    },
                    Agent().apply {
                        id = 2L
                        name = "Agent B"
                    },
                ),
            )

            mockMvc.perform(get("/api/admin/agent-tasks/agents"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Agent A"))

            assertEquals(0, server.requestCount, "agents is admin's own domain; nothing may be forwarded for it")
        }

        @Test
        fun `an empty agent list and a local failure keep the answers this service gives`() {
            `when`(agentService.getActiveAgents()).thenReturn(emptyList())
            mockMvc.perform(get("/api/admin/agent-tasks/agents"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.length()").value(0))

            `when`(agentService.getActiveAgents()).thenThrow(RuntimeException("DB error"))
            mockMvc.perform(get("/api/admin/agent-tasks/agents"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("Failed to query agents list: DB error"))

            assertEquals(0, server.requestCount)
        }
    }

    // ==================== the identity header rule ====================

    @Test
    fun `a forward with nobody authenticated still goes out and names nobody`() {
        // Cold paths (a CLI with the shared secret, an internal caller) reach these endpoints with no end-user
        // in the context. Refusing in this service would be a new rule; the other service answers "Not logged
        // in" for a forward that carries no user, which is the sentence its clients already saw.
        SecurityContextHolder.clearContext()
        TenantContext.clear()
        answer = """{"code":500,"message":"Failed to query agent task list: Not logged in","data":null,"timestamp":1704067200000}"""

        val result = mockMvc.perform(request(HttpMethod.GET, "/api/admin/agent-tasks/page")).andReturn()

        val request = takeRequest()
        assertNull(request.getHeader("X-Forwarded-User"))
        assertNull(request.getHeader("X-Tenant-Id"))
        assertRelayed(answer, result)
    }
}

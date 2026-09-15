package com.agnetix.harnax.scheduler.controller

import com.agnetix.harnax.auth.InternalTokenProvider
import com.agnetix.harnax.scheduler.config.SchedulerWebConfig
import com.agnetix.harnax.scheduler.dto.AgentTaskCreateRequest
import com.agnetix.harnax.scheduler.dto.AgentTaskResponse
import com.agnetix.harnax.scheduler.dto.AgentTaskUpdateRequest
import com.agnetix.harnax.scheduler.dto.Page
import com.agnetix.harnax.scheduler.entity.AgentTask
import com.agnetix.harnax.scheduler.entity.AgentTaskLog
import com.agnetix.harnax.scheduler.health.SchedulerStatus
import com.agnetix.harnax.scheduler.service.AgentTaskCrudService
import com.agnetix.harnax.scheduler.service.AgentTaskLogQueryService
import com.agnetix.harnax.scheduler.service.SchedulerService
import com.agnetix.harnax.scheduler.support.SchedulerBizException
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime

/**
 * The eleven endpoints of the task surface as a client sees them, after `harnax-admin` stopped serving them.
 *
 * Every expectation here is a body `harnax-admin` answered today: the release-2 promise is that the
 * `/api/admin/agent-tasks` surface keeps producing the same JSON once it forwards, and a forwarding layer cannot
 * restore what this side does not send. That includes the shapes that look like accidents — a duplicate name
 * coming back wrapped in "Failed to create agent task: …" at code 500, because admin's create had no
 * business-code branch and this one has none either.
 *
 * The scheduling verbs run over a real [SchedulerController] with a mocked [SchedulerService], not a stubbed
 * controller: what is worth pinning is that the relayed answer *includes* this instance's own gate (40903 on
 * a node configured to stay inert) and the conflict a trigger reports (40901).
 */
class AgentTaskControllerTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var crudService: AgentTaskCrudService
    private lateinit var logService: AgentTaskLogQueryService
    private lateinit var schedulerService: SchedulerService
    private lateinit var task: AgentTask
    private val mapper = ObjectMapper()

    /**
     * The advice is registered because two endpoints (`stop`, and whatever `toggle` does not catch itself)
     * have no try/catch — admin's had none either, and its business exception landed in the global handler.
     * Same landing point, so the same body has to come out of it.
     */
    private fun mvcFor(controller: AgentTaskController): MockMvc = MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(SchedulerWebConfig(InternalTokenProvider("harnax-scheduler", SECRET, 300), mapper))
        .build()

    /** A controller over a scheduler node that either can or cannot take scheduling work. */
    private fun controller(enabled: Boolean): AgentTaskController = AgentTaskController(
        agentTaskCrudService = crudService,
        agentTaskLogQueryService = logService,
        schedulerController = SchedulerController(schedulerService, SchedulerStatus(enabled)),
    )

    @BeforeEach
    fun setUp() {
        crudService = mock()
        logService = mock()
        schedulerService = mock()
        task = AgentTask().apply {
            id = 1L
            tenantId = 1L
            name = "Daily News"
            agentId = 100L
            agentName = "News Agent"
            prompt = "Summarize today's news"
            cronExpression = "0 0 9 * * ?"
            description = "Daily news summary"
            creator = "admin"
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }
        mockMvc = mvcFor(controller(enabled = true))
    }

    private fun givenStartSucceeds(success: Boolean = true) {
        whenever(crudService.requireVisibleTask(1L)).thenReturn(task)
        whenever(schedulerService.startTask(1L)).thenReturn(success)
    }

    private fun ownedLog(id: Long): AgentTaskLog = AgentTaskLog().apply {
        this.id = id
        taskId = 1L
        status = 3
        creator = "admin"
    }

    /**
     * `data` has to stay empty on the four relays. Admin parsed this service's answer into a
     * `ResultVo<Void>`, so its clients have only ever read the code and the message off these endpoints — a
     * body that suddenly carries a string there is a new key on a contract the release promised not to touch.
     */
    private fun assertDataAbsent(body: String) {
        val data = mapper.readTree(body).path("data")
        assertTrue(
            data.isMissingNode || data.isNull,
            "`data` must stay empty for a client that never saw a payload, got: $body",
        )
    }

    // ==================== GET /page ====================

    @Nested
    @DisplayName("GET /api/scheduler/agent-tasks/page")
    inner class PageEndpoint {

        @Test
        fun `the page envelope and the record keys are the ones three clients read`() {
            val page = Page<AgentTask>(pageNum = 2, pageSize = 10, total = 25, records = listOf(task))
            whenever(crudService.page(null, null, null, 2, 10)).thenReturn(page)

            mockMvc.perform(get("/api/scheduler/agent-tasks/page").param("pageNum", "2").param("pageSize", "10"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(25))
                .andExpect(jsonPath("$.data.pages").value(3))
                .andExpect(jsonPath("$.data.hasPrevious").value(true))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.records[0].agentName").value("News Agent"))
                .andExpect(jsonPath("$.data.records[0].creator").value("admin"))
                .andExpect(jsonPath("$.data.records[0].timeoutSeconds").value(300))
        }

        @Test
        fun `every filter is handed to the service unchanged`() {
            whenever(crudService.page(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
                .thenReturn(Page<AgentTask>())

            mockMvc.perform(
                get("/api/scheduler/agent-tasks/page")
                    .param("name", "News")
                    .param("agentId", "100")
                    .param("taskStatus", "1")
                    .param("pageNum", "3")
                    .param("pageSize", "50"),
            ).andExpect(status().isOk)

            verify(crudService).page("News", 100L, 1, 3, 50)
        }

        @Test
        fun `a failing read still flattens to the sentence admin's client was shown`() {
            whenever(crudService.page(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
                .thenThrow(RuntimeException("db down"))

            mockMvc.perform(get("/api/scheduler/agent-tasks/page"))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("Failed to query agent task list: db down"))
        }
    }

    // ==================== GET /{id} ====================

    @Nested
    @DisplayName("GET /api/scheduler/agent-tasks/{id}")
    inner class GetByIdEndpoint {

        @Test
        fun `a visible task answers with the task DTO`() {
            whenever(crudService.getAgentTask(1L)).thenReturn(task)
            whenever(crudService.convertToResponse(task)).thenReturn(AgentTaskResponse.fromEntity(task))

            mockMvc.perform(get("/api/scheduler/agent-tasks/1"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("Daily News"))
                .andExpect(jsonPath("$.data.timeoutSeconds").value(300))
                .andExpect(jsonPath("$.data.active").value(1))
        }

        @Test
        fun `a task the caller may not see is the same not-found answer as no row at all`() {
            whenever(crudService.getAgentTask(2L)).thenReturn(null)

            mockMvc.perform(get("/api/scheduler/agent-tasks/2"))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("Agent task not found"))
        }
    }

    // ==================== POST / ====================

    @Nested
    @DisplayName("POST /api/scheduler/agent-tasks")
    inner class CreateEndpoint {

        private fun body(request: AgentTaskCreateRequest) = mapper.writeValueAsString(request)

        @Test
        fun `a stored task answers success`() {
            whenever(crudService.createAgentTask(any())).thenReturn(true)

            mockMvc.perform(
                post("/api/scheduler/agent-tasks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(AgentTaskCreateRequest(name = "New", agentId = 100L, agentName = "News Agent", prompt = "p", cronExpression = "0 0 9 * * ?"))),
            ).andExpect(jsonPath("$.code").value(200))

            verify(crudService).createAgentTask(any())
        }

        /**
         * The one refusal in this domain a client has always read second-hand: admin's create wrapped every
         * service refusal in a 500 sentence, so the webui shows the whole line. Unwrapping it here would
         * change a visible string, and the brief forbids that on a move.
         */
        @Test
        fun `a duplicate name keeps arriving wrapped in the create sentence`() {
            whenever(crudService.createAgentTask(any())).thenThrow(SchedulerBizException("Task name already exists"))

            mockMvc.perform(
                post("/api/scheduler/agent-tasks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(AgentTaskCreateRequest(name = "Daily News", agentId = 100L, agentName = "News Agent", prompt = "p", cronExpression = "0 0 9 * * ?"))),
            )
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("Failed to create agent task: Task name already exists"))
        }

        @Test
        fun `bean validation answers in admin's field-colon-message form`() {
            mockMvc.perform(
                post("/api/scheduler/agent-tasks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(AgentTaskCreateRequest(name = "", agentId = 100L, prompt = "p", cronExpression = "0 0 9 * * ?"))),
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("name: Task name is required"))
        }
    }

    // ==================== PUT /{id} and DELETE /{id} ====================

    @Nested
    @DisplayName("PUT / DELETE /api/scheduler/agent-tasks/{id}")
    inner class WriteEndpoints {

        @Test
        fun `an update that stored answers success`() {
            whenever(crudService.updateAgentTask(eq(1L), any())).thenReturn(true)

            mockMvc.perform(
                put("/api/scheduler/agent-tasks/1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(AgentTaskUpdateRequest(prompt = "New"))),
            ).andExpect(jsonPath("$.code").value(200))
        }

        @Test
        fun `40902 stays distinguishable from a lost edit instead of hiding in a generic failure`() {
            whenever(crudService.updateAgentTask(eq(1L), any())).thenThrow(
                SchedulerBizException(40902, "Task saved, but the scheduler did not reload: boom."),
            )

            mockMvc.perform(
                put("/api/scheduler/agent-tasks/1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(AgentTaskUpdateRequest(prompt = "New"))),
            )
                .andExpect(jsonPath("$.code").value(40902))
                .andExpect(jsonPath("$.message").value("Task saved, but the scheduler did not reload: boom."))
        }

        @Test
        fun `the creator gate on update reports the refusal rather than a silent save`() {
            whenever(crudService.updateAgentTask(eq(1L), any())).thenThrow(
                SchedulerBizException("Only the task creator can modify this task"),
            )

            mockMvc.perform(
                put("/api/scheduler/agent-tasks/1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(AgentTaskUpdateRequest(prompt = "hijacked"))),
            )
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("Only the task creator can modify this task"))
        }

        @Test
        fun `a delete that stored answers success and a refused one says who may delete`() {
            whenever(crudService.deleteAgentTask(1L)).thenReturn(true)
            mockMvc.perform(delete("/api/scheduler/agent-tasks/1")).andExpect(jsonPath("$.code").value(200))

            whenever(crudService.deleteAgentTask(2L)).thenThrow(
                SchedulerBizException("Only the task creator can delete this task"),
            )
            mockMvc.perform(delete("/api/scheduler/agent-tasks/2"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("Only the task creator can delete this task"))
        }

        @Test
        fun `40902 surfaces from a committed delete the same way`() {
            whenever(crudService.deleteAgentTask(1L)).thenThrow(
                SchedulerBizException(40902, "Task deleted, but the scheduler did not reload: boom."),
            )

            mockMvc.perform(delete("/api/scheduler/agent-tasks/1"))
                .andExpect(jsonPath("$.code").value(40902))
                .andExpect(jsonPath("$.message").value("Task deleted, but the scheduler did not reload: boom."))
        }
    }

    // ==================== The scheduling verbs ====================

    @Nested
    @DisplayName("POST /toggle /start /pause /trigger /stop")
    inner class SchedulingEndpoints {

        @Test
        fun `toggle on a running node delegates the switch and answers success`() {
            givenStartSucceeds()

            mockMvc.perform(post("/api/scheduler/agent-tasks/toggle/1").param("status", "1"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))

            verify(schedulerService).startTask(1L)
        }

        @Test
        fun `toggle forwards the reason a start failed instead of a bare failure`() {
            givenStartSucceeds()
            whenever(schedulerService.startTask(1L)).thenThrow(RuntimeException("CronExpression '0 0 0 * * *' is invalid."))

            val body = mockMvc.perform(post("/api/scheduler/agent-tasks/toggle/1").param("status", "1"))
                .andExpect(jsonPath("$.code").value(500))
                .andReturn().response.getContentAsString(StandardCharsets.UTF_8)

            assertTrue(
                body.contains("CronExpression '0 0 0 * * *' is invalid"),
                "the only party that can judge a cron is this service, and its reason has to reach the caller: $body",
            )
        }

        /**
         * `scheduler.enabled=false` means this node registers nothing, and admin's UI switch learned that
         * from the 40903 it forwarded — so the moved surface has to answer it too. That is why these endpoints
         * go through the controller that owns the gate instead of around it.
         */
        @Test
        fun `toggle on an inert node keeps the 40903 the UI switch reads`() {
            givenStartSucceeds()

            mvcFor(controller(enabled = false)).perform(post("/api/scheduler/agent-tasks/toggle/1").param("status", "1"))
                .andExpect(jsonPath("$.code").value(40903))
                .andExpect(jsonPath("$.message").value("Scheduling is disabled on this instance"))

            verify(schedulerService, never()).startTask(any())
        }

        /** The status the switch sends is the status the moved service read, and `0` is the pause branch. */
        @Test
        fun `toggle with status zero pauses`() {
            whenever(crudService.requireVisibleTask(1L)).thenReturn(task)
            whenever(schedulerService.pauseTask(1L)).thenReturn(true)

            mockMvc.perform(post("/api/scheduler/agent-tasks/toggle/1").param("status", "0"))
                .andExpect(jsonPath("$.code").value(200))

            verify(schedulerService).pauseTask(1L)
        }

        @Test
        fun `a toggle of a task the caller may not see never reaches the scheduler`() {
            whenever(crudService.requireVisibleTask(9L)).thenThrow(SchedulerBizException("Agent task not found"))

            mockMvc.perform(post("/api/scheduler/agent-tasks/toggle/9").param("status", "1"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("Agent task not found"))

            verify(schedulerService, never()).startTask(any())
            verify(schedulerService, never()).pauseTask(any())
        }

        /**
         * What a client has ever seen on this endpoint: `ResultVo.success("Task started")` keeps the shell's
         * own "success" message and carries the human string in `data`, and admin parsed the answer into a
         * `ResultVo<Void>` — so the string was dropped there and the message stayed. Both halves have to come
         * out the same way here, or the surface the release promised not to change grows a payload no client
         * parses while the message it does read goes quiet.
         */
        @Test
        fun `start relays the code and the shell message with no data payload`() {
            whenever(schedulerService.startTask(1L)).thenReturn(true)

            val body = mockMvc.perform(post("/api/scheduler/agent-tasks/1/start"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andReturn().response.getContentAsString(StandardCharsets.UTF_8)

            assertDataAbsent(body)
            assertTrue(
                !body.contains("Task started"),
                "the string this service puts in data never reached a task-API caller: $body",
            )
        }

        @Test
        fun `a pause that did not take still carries the code and message it always carried`() {
            whenever(schedulerService.pauseTask(1L)).thenReturn(false)

            mockMvc.perform(post("/api/scheduler/agent-tasks/1/pause"))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("Pause failed"))
        }

        /** 40901 is the one answer a manual trigger has to get right: the caller should poll, not retry. */
        @Test
        fun `trigger keeps the conflict code of a live execution`() {
            whenever(schedulerService.runTaskOnce(1L)).thenReturn(false)

            mockMvc.perform(post("/api/scheduler/agent-tasks/1/trigger"))
                .andExpect(jsonPath("$.code").value(40901))
                .andExpect(jsonPath("$.message").value("Task execution is already in progress"))
        }

        @Test
        fun `a stop of a log the caller does not own is refused before anything is interrupted`() {
            whenever(crudService.requireOwnedLog(99L)).thenThrow(SchedulerBizException("Agent task log not found"))

            mockMvc.perform(post("/api/scheduler/agent-tasks/logs/99/stop"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("Agent task log not found"))

            verify(schedulerService, never()).stopTask(any())
        }

        @Test
        fun `a stop of a log the caller owns reaches the stop path`() {
            whenever(crudService.requireOwnedLog(55L)).thenReturn(ownedLog(55L))
            whenever(schedulerService.stopTask(55L)).thenReturn(true)

            mockMvc.perform(post("/api/scheduler/agent-tasks/logs/55/stop"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))

            verify(schedulerService).stopTask(55L)
        }

        /**
         * Stopping is not gated by `scheduler.enabled` on this service's own surface either — a live execution
         * has to be stoppable from an inert node — so what a caller gets here is the stop path's own answer,
         * on a controller built with scheduling disabled.
         */
        @Test
        fun `a stop still runs on a node that refuses to schedule`() {
            whenever(crudService.requireOwnedLog(55L)).thenReturn(ownedLog(55L))
            whenever(schedulerService.stopTask(55L)).thenReturn(false)

            mvcFor(controller(enabled = false)).perform(post("/api/scheduler/agent-tasks/logs/55/stop"))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("Task is not running or already completed"))
        }
    }

    // ==================== GET /{id}/logs ====================

    @Nested
    @DisplayName("GET /api/scheduler/agent-tasks/{id}/logs")
    inner class LogsEndpoint {

        @Test
        fun `the path id is the task filter and the rest goes through unchanged`() {
            whenever(
                logService.page(
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                ),
            ).thenReturn(Page<AgentTaskLog>())

            mockMvc.perform(
                get("/api/scheduler/agent-tasks/7/logs")
                    .param("taskName", "News")
                    .param("status", "1")
                    .param("startTimeFrom", "2026-07-01 00:00:00")
                    .param("startTimeTo", "2026-07-31 23:59:59")
                    .param("keyword", "error")
                    .param("pageNum", "2")
                    .param("pageSize", "20"),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())

            verify(logService).page(7L, "News", 1, "2026-07-01 00:00:00", "2026-07-31 23:59:59", "error", 2, 20)
        }

        @Test
        fun `a failing log read keeps admin's sentence around it`() {
            whenever(
                logService.page(
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                    anyOrNull(),
                ),
            ).thenThrow(RuntimeException("boom"))

            mockMvc.perform(get("/api/scheduler/agent-tasks/7/logs"))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("Failed to query agent task logs: boom"))
        }
    }

    companion object {
        /** The advice's own collaborator needs a provider; 32 characters is the documented floor. */
        private const val SECRET = "agent-task-controller-test-secret-at-least-32"
    }
}

package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.AgentTaskCreateRequest
import com.agnetix.harnax.admin.dto.AgentTaskResponse
import com.agnetix.harnax.admin.dto.AgentTaskUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.AgentService
import com.agnetix.harnax.admin.service.AgentTaskLogService
import com.agnetix.harnax.admin.service.AgentTaskService
import com.agnetix.harnax.admin.service.impl.AgentTaskServiceImpl
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.entity.Agent
import com.agnetix.harnax.entity.AgentTask
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime

class AgentTaskControllerTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var agentTaskService: AgentTaskService
    private lateinit var agentTaskLogService: AgentTaskLogService
    private lateinit var agentService: AgentService
    private val objectMapper = ObjectMapper()

    private lateinit var testTask: AgentTask
    private lateinit var testResponse: AgentTaskResponse

    @BeforeEach
    fun setUp() {
        agentTaskService = mock(AgentTaskService::class.java)
        agentTaskLogService = mock(AgentTaskLogService::class.java)
        agentService = mock(AgentService::class.java)
        val controller = AgentTaskController(agentTaskService, agentTaskLogService, agentService)
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build()

        testTask = AgentTask().apply {
            id = 1L
            tenantId = 1L
            name = "Daily News"
            agentId = 100L
            agentName = "News Agent"
            prompt = "Summarize today's news"
            cronExpression = "0 0 9 * * ?"
            taskStatus = 0
            concurrent = 0
            timeoutSeconds = 300
            description = "Daily news summary"
            isPublic = 0
            creator = "admin"
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }

        testResponse = AgentTaskResponse.fromEntity(testTask)
    }

    private fun toJson(obj: Any): String = objectMapper.writeValueAsString(obj)

    // ==================== GET /api/admin/agent-tasks/page ====================

    @Nested
    @DisplayName("GET /api/admin/agent-tasks/page")
    inner class PageEndpoint {

        @Test
        fun `page should return paginated results`() {
            val page = Page<AgentTask>(
                total = 1L,
                pageNum = 1L,
                pageSize = 10L,
                records = listOf(testTask),
            )
            `when`(agentTaskService.page(null, null, null, 1, 10)).thenReturn(page)

            mockMvc.perform(
                get("/api/admin/agent-tasks/page")
                    .param("pageNum", "1")
                    .param("pageSize", "10"),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(200))
        }

        @Test
        fun `page should handle service error`() {
            `when`(agentTaskService.page(null, null, null, 1, 10))
                .thenThrow(RuntimeException("DB error"))

            mockMvc.perform(get("/api/admin/agent-tasks/page"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("Failed to query agent task list: DB error"))
        }

        @Test
        fun `page should pass filters correctly`() {
            val page = Page<AgentTask>(total = 0L, pageNum = 1L, pageSize = 10L, records = emptyList())
            `when`(agentTaskService.page("news", 100L, 1, 1, 10)).thenReturn(page)

            mockMvc.perform(
                get("/api/admin/agent-tasks/page")
                    .param("name", "news")
                    .param("agentId", "100")
                    .param("taskStatus", "1")
                    .param("pageNum", "1")
                    .param("pageSize", "10"),
            )
                .andExpect(status().isOk)
        }
    }

    // ==================== GET /api/admin/agent-tasks/{id} ====================

    @Nested
    @DisplayName("GET /api/admin/agent-tasks/{id}")
    inner class GetByIdEndpoint {

        @Test
        fun `getById should return task`() {
            `when`(agentTaskService.getAgentTask(1L)).thenReturn(testTask)
            `when`(agentTaskService.convertToResponse(testTask)).thenReturn(testResponse)

            mockMvc.perform(get("/api/admin/agent-tasks/1"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("Daily News"))
                .andExpect(jsonPath("$.data.agentId").value(100))
        }

        @Test
        fun `getById should return error when not found`() {
            `when`(agentTaskService.getAgentTask(999L)).thenReturn(null)

            mockMvc.perform(get("/api/admin/agent-tasks/999"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("Agent task not found"))
        }
    }

    // ==================== POST /api/admin/agent-tasks ====================

    @Nested
    @DisplayName("POST /api/admin/agent-tasks")
    inner class CreateEndpoint {

        @Test
        fun `create should return success`() {
            val request = AgentTaskCreateRequest(
                name = "New Task",
                agentId = 100L,
                prompt = "Do something",
                cronExpression = "0 0 9 * * ?",
            )
            `when`(agentTaskService.createAgentTask(any())).thenReturn(true)

            mockMvc.perform(
                post("/api/admin/agent-tasks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(request)),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(200))
        }

        @Test
        fun `create should return error on duplicate name`() {
            val request = AgentTaskCreateRequest(
                name = "Daily News",
                agentId = 100L,
                prompt = "Do something",
                cronExpression = "0 0 9 * * ?",
            )
            `when`(agentTaskService.createAgentTask(any()))
                .thenThrow(BizException("Task name already exists"))

            mockMvc.perform(
                post("/api/admin/agent-tasks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(request)),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("Failed to create agent task: Task name already exists"))
        }

        @Test
        fun `create should return error on invalid cron`() {
            val request = AgentTaskCreateRequest(
                name = "Bad Cron",
                agentId = 100L,
                prompt = "Do something",
                cronExpression = "bad-cron",
            )
            `when`(agentTaskService.createAgentTask(any()))
                .thenThrow(BizException("Invalid cron expression"))

            mockMvc.perform(
                post("/api/admin/agent-tasks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(request)),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("Failed to create agent task: Invalid cron expression"))
        }

        @Test
        fun `create should handle all optional fields`() {
            val request = AgentTaskCreateRequest(
                name = "Full Task",
                agentId = 100L,
                prompt = "Do something",
                cronExpression = "0 0 9 * * ?",
                concurrent = 1,
                timeoutSeconds = 600,
                description = "A task with all fields",
                isPublic = 1,
            )
            `when`(agentTaskService.createAgentTask(any())).thenReturn(true)

            mockMvc.perform(
                post("/api/admin/agent-tasks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(request)),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(200))
        }

        @Test
        fun `create should return error when agent not found`() {
            val request = AgentTaskCreateRequest(
                name = "No Agent",
                agentId = 999L,
                prompt = "Do something",
                cronExpression = "0 0 9 * * ?",
            )
            `when`(agentTaskService.createAgentTask(any()))
                .thenThrow(BizException("Agent not found"))

            mockMvc.perform(
                post("/api/admin/agent-tasks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(request)),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("Failed to create agent task: Agent not found"))
        }
    }

    // ==================== PUT /api/admin/agent-tasks/{id} ====================

    @Nested
    @DisplayName("PUT /api/admin/agent-tasks/{id}")
    inner class UpdateEndpoint {

        @Test
        fun `update should return success`() {
            val request = AgentTaskUpdateRequest(prompt = "Updated prompt")
            `when`(agentTaskService.updateAgentTask(any(), any())).thenReturn(true)

            mockMvc.perform(
                put("/api/admin/agent-tasks/1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(request)),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(200))
        }

        @Test
        fun `update should return error when not found`() {
            val request = AgentTaskUpdateRequest(prompt = "Updated prompt")
            `when`(agentTaskService.updateAgentTask(any(), any()))
                .thenThrow(BizException("Agent task not found"))

            mockMvc.perform(
                put("/api/admin/agent-tasks/999")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(request)),
            )
                .andExpect(status().isOk)
                // The service's business code survives; only unexpected failures become 500.
                .andExpect(jsonPath("$.code").value(400))
        }

        @Test
        fun `update should handle partial fields`() {
            val request = AgentTaskUpdateRequest(
                prompt = "Only prompt",
                // all other fields null
            )
            `when`(agentTaskService.updateAgentTask(any(), any())).thenReturn(true)

            mockMvc.perform(
                put("/api/admin/agent-tasks/1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(request)),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(200))
        }

        @Test
        fun `update should handle all fields`() {
            val request = AgentTaskUpdateRequest(
                name = "Updated Name",
                agentId = 200L,
                prompt = "Updated prompt",
                cronExpression = "0 0 10 * * ?",
                concurrent = 1,
                timeoutSeconds = 600,
                description = "Updated description",
                isPublic = 1,
            )
            `when`(agentTaskService.updateAgentTask(any(), any())).thenReturn(true)

            mockMvc.perform(
                put("/api/admin/agent-tasks/1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(request)),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(200))
        }

        @Test
        fun `update should return error on invalid cron`() {
            val request = AgentTaskUpdateRequest(cronExpression = "bad-cron")
            `when`(agentTaskService.updateAgentTask(any(), any()))
                .thenThrow(BizException("Invalid cron expression"))

            mockMvc.perform(
                put("/api/admin/agent-tasks/1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(request)),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("Invalid cron expression"))
        }

        @Test
        fun `update should return error on duplicate name`() {
            val request = AgentTaskUpdateRequest(name = "Existing Name")
            `when`(agentTaskService.updateAgentTask(any(), any()))
                .thenThrow(BizException("Task name already exists"))

            mockMvc.perform(
                put("/api/admin/agent-tasks/1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(request)),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("Task name already exists"))
        }

        /**
         * The reload broadcast leaves after the commit, so a failed one cannot be reported as "your
         * edit did not happen". Its business code has to reach the caller intact — flattening it to 500
         * here is what made the old failure invisible.
         */
        @Test
        fun `update reports the scheduler sync code instead of hiding it in a generic failure`() {
            val request = AgentTaskUpdateRequest(prompt = "Updated prompt")
            `when`(agentTaskService.updateAgentTask(any(), any())).thenThrow(
                BizException(
                    AgentTaskServiceImpl.CODE_SCHEDULER_SYNC_FAILED,
                    "Task saved, but the scheduler did not reload: scheduler boom.",
                ),
            )

            mockMvc.perform(
                put("/api/admin/agent-tasks/1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(request)),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(AgentTaskServiceImpl.CODE_SCHEDULER_SYNC_FAILED))
                .andExpect(jsonPath("$.message").value("Task saved, but the scheduler did not reload: scheduler boom."))
        }
    }

    // ==================== DELETE /api/admin/agent-tasks/{id} ====================

    @Nested
    @DisplayName("DELETE /api/admin/agent-tasks/{id}")
    inner class DeleteEndpoint {

        @Test
        fun `delete should return success`() {
            `when`(agentTaskService.deleteAgentTask(1L)).thenReturn(true)

            mockMvc.perform(delete("/api/admin/agent-tasks/1"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(200))
        }

        @Test
        fun `delete should return error when not found`() {
            `when`(agentTaskService.deleteAgentTask(999L))
                .thenThrow(BizException("Agent task not found"))

            mockMvc.perform(delete("/api/admin/agent-tasks/999"))
                .andExpect(status().isOk)
                // Business code, not a flattened 500: same rule as the update endpoint.
                .andExpect(jsonPath("$.code").value(400))
        }

        @Test
        fun `delete reports the scheduler sync code so a stale schedule is not a silent success`() {
            `when`(agentTaskService.deleteAgentTask(1L)).thenThrow(
                BizException(
                    AgentTaskServiceImpl.CODE_SCHEDULER_SYNC_FAILED,
                    "Task deleted, but the scheduler did not reload: scheduler boom.",
                ),
            )

            mockMvc.perform(delete("/api/admin/agent-tasks/1"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(AgentTaskServiceImpl.CODE_SCHEDULER_SYNC_FAILED))
                .andExpect(jsonPath("$.message").value("Task deleted, but the scheduler did not reload: scheduler boom."))
        }
    }

    // ==================== GET /api/admin/agent-tasks/{id}/logs ====================

    @Nested
    @DisplayName("GET /api/admin/agent-tasks/{id}/logs")
    inner class LogsEndpoint {

        @Test
        fun `logs should return paginated results`() {
            val page = Page<com.agnetix.harnax.entity.AgentTaskLog>(
                total = 0L,
                pageNum = 1L,
                pageSize = 10L,
                records = emptyList(),
            )
            `when`(agentTaskLogService.page(1L, null, null, null, null, null, 1, 10)).thenReturn(page)

            mockMvc.perform(
                get("/api/admin/agent-tasks/1/logs")
                    .param("pageNum", "1")
                    .param("pageSize", "10"),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(200))
        }

        @Test
        fun `logs should pass filters correctly`() {
            val page = Page<com.agnetix.harnax.entity.AgentTaskLog>(
                total = 0L,
                pageNum = 1L,
                pageSize = 10L,
                records = emptyList(),
            )
            `when`(agentTaskLogService.page(1L, "Daily", 1, null, null, null, 1, 10)).thenReturn(page)

            mockMvc.perform(
                get("/api/admin/agent-tasks/1/logs")
                    .param("taskName", "Daily")
                    .param("status", "1")
                    .param("pageNum", "1")
                    .param("pageSize", "10"),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(200))
        }

        @Test
        fun `logs should handle service error`() {
            // 可空的过滤参数要用 anyOrNull：mockito-kotlin 的 any() 不匹配 null
            `when`(
                agentTaskLogService.page(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any(), any()),
            ).thenThrow(RuntimeException("DB error"))

            mockMvc.perform(get("/api/admin/agent-tasks/1/logs"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(500))
        }

        @Test
        fun `logs should pass time range filters`() {
            val page = Page<com.agnetix.harnax.entity.AgentTaskLog>(
                total = 0L,
                pageNum = 1L,
                pageSize = 10L,
                records = emptyList(),
            )
            `when`(agentTaskLogService.page(1L, null, null, "2026-07-01 00:00:00", "2026-07-31 23:59:59", null, 1, 10)).thenReturn(page)

            mockMvc.perform(
                get("/api/admin/agent-tasks/1/logs")
                    .param("startTimeFrom", "2026-07-01 00:00:00")
                    .param("startTimeTo", "2026-07-31 23:59:59")
                    .param("pageNum", "1")
                    .param("pageSize", "10"),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(200))
        }

        @Test
        fun `logs should pass keyword filter`() {
            val page = Page<com.agnetix.harnax.entity.AgentTaskLog>(
                total = 0L,
                pageNum = 1L,
                pageSize = 10L,
                records = emptyList(),
            )
            `when`(agentTaskLogService.page(1L, null, null, null, null, "error", 1, 10)).thenReturn(page)

            mockMvc.perform(
                get("/api/admin/agent-tasks/1/logs")
                    .param("keyword", "error")
                    .param("pageNum", "1")
                    .param("pageSize", "10"),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(200))
        }

        @Test
        fun `logs should pass all filters combined`() {
            val page = Page<com.agnetix.harnax.entity.AgentTaskLog>(
                total = 0L,
                pageNum = 1L,
                pageSize = 10L,
                records = emptyList(),
            )
            `when`(agentTaskLogService.page(1L, "Daily", 1, "2026-07-01 00:00:00", "2026-07-31 23:59:59", "news", 1, 10)).thenReturn(page)

            mockMvc.perform(
                get("/api/admin/agent-tasks/1/logs")
                    .param("taskName", "Daily")
                    .param("status", "1")
                    .param("startTimeFrom", "2026-07-01 00:00:00")
                    .param("startTimeTo", "2026-07-31 23:59:59")
                    .param("keyword", "news")
                    .param("pageNum", "1")
                    .param("pageSize", "10"),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(200))
        }
    }

    // ==================== GET /api/admin/agent-tasks/agents ====================

    @Nested
    @DisplayName("GET /api/admin/agent-tasks/agents")
    inner class AgentsEndpoint {

        @Test
        fun `agents should return list of available agents`() {
            val agents = listOf(
                Agent().apply {
                    id = 1L
                    name = "Agent A"
                },
                Agent().apply {
                    id = 2L
                    name = "Agent B"
                },
            )
            `when`(agentService.getActiveAgents()).thenReturn(agents)

            mockMvc.perform(get("/api/admin/agent-tasks/agents"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray)
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Agent A"))
        }

        @Test
        fun `agents should return empty list when no agents`() {
            `when`(agentService.getActiveAgents()).thenReturn(emptyList())

            mockMvc.perform(get("/api/admin/agent-tasks/agents"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data").isArray)
                .andExpect(jsonPath("$.data.length()").value(0))
        }

        @Test
        fun `agents should return error on service failure`() {
            `when`(agentService.getActiveAgents())
                .thenThrow(RuntimeException("DB error"))

            mockMvc.perform(get("/api/admin/agent-tasks/agents"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(500))
        }
    }

    // ==================== POST /api/admin/agent-tasks/toggle/{id} ====================

    @Nested
    @DisplayName("POST /api/admin/agent-tasks/toggle/{id}")
    inner class ToggleEndpoint {

        @Test
        fun `toggle should update status and return success`() {
            `when`(agentTaskService.toggleTaskStatus(1L, 1)).thenReturn(true)

            mockMvc.perform(post("/api/admin/agent-tasks/toggle/1").param("status", "1"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(200))

            verify(agentTaskService).toggleTaskStatus(1L, 1)
        }

        @Test
        fun `toggle should return error when update fails`() {
            `when`(agentTaskService.toggleTaskStatus(1L, 0)).thenReturn(false)

            mockMvc.perform(post("/api/admin/agent-tasks/toggle/1").param("status", "0"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(500))
        }

        /**
         * The service forwards whatever the scheduler answered, and 40903 ("this instance has
         * `scheduler.enabled=false`") is the one code the UI can act on — the status switch is exactly the
         * request a standby node refuses. Flattening it to 500 made the toggle look broken here rather
         * than pointed at the wrong node, which is what update/delete already avoid.
         */
        @Test
        fun `toggle reports the scheduler business code instead of folding it into a 500`() {
            `when`(agentTaskService.toggleTaskStatus(1L, 1)).thenThrow(
                BizException(40903, "Scheduling is disabled on this instance"),
            )

            mockMvc.perform(post("/api/admin/agent-tasks/toggle/1").param("status", "1"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(40903))
                .andExpect(jsonPath("$.message").value("Scheduling is disabled on this instance"))
        }

        @Test
        fun `toggle still flattens an unexpected failure`() {
            `when`(agentTaskService.toggleTaskStatus(1L, 1)).thenThrow(RuntimeException("connection refused"))

            mockMvc.perform(post("/api/admin/agent-tasks/toggle/1").param("status", "1"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(500))
        }
    }

    // ==================== POST /api/admin/agent-tasks/{id}/start ====================

    @Nested
    @DisplayName("POST /api/admin/agent-tasks/{id}/start")
    inner class StartEndpoint {

        @Test
        fun `start should delegate to service and return success`() {
            `when`(agentTaskService.startTask(1L)).thenReturn(ResultVo.success())

            mockMvc.perform(post("/api/admin/agent-tasks/1/start"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(200))

            verify(agentTaskService).startTask(1L)
        }

        @Test
        fun `start should return error when scheduler unavailable`() {
            `when`(agentTaskService.startTask(1L))
                .thenReturn(ResultVo.error("Scheduler service unavailable"))

            mockMvc.perform(post("/api/admin/agent-tasks/1/start"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(500))
        }
    }

    // ==================== POST /api/admin/agent-tasks/{id}/pause ====================

    @Nested
    @DisplayName("POST /api/admin/agent-tasks/{id}/pause")
    inner class PauseEndpoint {

        @Test
        fun `pause should delegate to service and return success`() {
            `when`(agentTaskService.pauseTask(1L)).thenReturn(ResultVo.success())

            mockMvc.perform(post("/api/admin/agent-tasks/1/pause"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(200))

            verify(agentTaskService).pauseTask(1L)
        }

        @Test
        fun `pause should return error when scheduler unavailable`() {
            `when`(agentTaskService.pauseTask(1L))
                .thenReturn(ResultVo.error("Scheduler service unavailable"))

            mockMvc.perform(post("/api/admin/agent-tasks/1/pause"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(500))
        }
    }
}

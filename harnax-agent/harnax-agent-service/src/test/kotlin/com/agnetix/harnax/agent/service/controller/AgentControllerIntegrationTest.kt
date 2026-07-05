package com.agnetix.harnax.agent.service.controller

import com.agnetix.harnax.agent.adaptor.PlanNote
import com.agnetix.harnax.agent.adaptor.TaskState
import com.agnetix.harnax.agent.chat.AssistantMessageLog
import com.agnetix.harnax.agent.chat.UserMessageLog
import com.agnetix.harnax.agent.protocol.ChatAgentRequest
import com.agnetix.harnax.agent.protocol.ChatResponse
import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.agent.protocol.CommandResponse
import com.agnetix.harnax.agent.protocol.CommandType
import com.agnetix.harnax.agent.service.runner.AgentRunner
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.ObjectMapper

/**
 * Integration test for AgentController.
 *
 * Uses MockMvc to test the full HTTP layer: request deserialization,
 * controller delegation to AgentRunner, and response serialization.
 */
class AgentControllerIntegrationTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var agentRunner: AgentRunner
    private val objectMapper = ObjectMapper()

    @BeforeEach
    fun setUp() {
        agentRunner = mock(AgentRunner::class.java)
        val controller = AgentController(agentRunner)
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build()
    }

    private fun toJson(obj: Any): String = objectMapper.writeValueAsString(obj)

    // ==================== POST /api/agent/chat ====================

    @Nested
    inner class ChatEndpoint {
        @Test
        fun `chat returns aggregated response on success`() {
            val request = ChatAgentRequest(sessionId = "sess-1", message = "hello")
            val response = ChatResponse(sessionId = "sess-1", content = "Hi there!")
            `when`(agentRunner.process(request)).thenReturn(response)

            mockMvc.perform(
                post("/api/agent/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(request)),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sessionId").value("sess-1"))
                .andExpect(jsonPath("$.data.content").value("Hi there!"))
        }

        @Test
        fun `chat returns error when runner throws`() {
            val request = ChatAgentRequest(sessionId = "sess-err", message = "fail")
            `when`(agentRunner.process(any<ChatAgentRequest>())).thenThrow(RuntimeException("Agent crashed"))

            mockMvc.perform(
                post("/api/agent/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(request)),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.message").value("Agent crashed"))
        }

        @Test
        fun `chat with image urls passes through`() {
            val request = ChatAgentRequest(
                sessionId = "sess-img",
                message = "describe this",
                imageUrls = listOf("https://example.com/img.png"),
            )
            val response = ChatResponse(sessionId = "sess-img", content = "A cat.")
            `when`(agentRunner.process(any<ChatAgentRequest>())).thenReturn(response)

            mockMvc.perform(
                post("/api/agent/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(request)),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.content").value("A cat."))
        }
    }

    // ==================== POST /api/agent/command ====================

    @Nested
    inner class CommandEndpoint {
        @Test
        fun `command INTERRUPT returns success`() {
            val request = CommandAgentRequest(sessionId = "sess-1", command = CommandType.INTERRUPT)
            val cmdResponse = CommandResponse.success("sess-1", message = "Stream interrupted")
            `when`(agentRunner.executeCommand(any<CommandAgentRequest>())).thenReturn(cmdResponse)

            mockMvc.perform(
                post("/api/agent/command")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(request)),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.message").value("Stream interrupted"))
        }

        @Test
        fun `command CLEAR returns success`() {
            val request = CommandAgentRequest(sessionId = "sess-1", command = CommandType.CLEAR)
            val cmdResponse = CommandResponse.success("sess-1", message = "Session cleared")
            `when`(agentRunner.executeCommand(any<CommandAgentRequest>())).thenReturn(cmdResponse)

            mockMvc.perform(
                post("/api/agent/command")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(request)),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.success").value(true))
        }

        @Test
        fun `command returns error when runner throws`() {
            val request = CommandAgentRequest(sessionId = "sess-1", command = CommandType.STOP_SANDBOX)
            `when`(agentRunner.executeCommand(any<CommandAgentRequest>())).thenThrow(RuntimeException("No sandbox"))

            mockMvc.perform(
                post("/api/agent/command")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(request)),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.message").value("No sandbox"))
        }
    }

    // ==================== POST /api/agent/chat/interrupt/{sessionId} ====================

    @Nested
    inner class InterruptEndpoint {
        @Test
        fun `interrupt delegates to runner`() {
            mockMvc.perform(post("/api/agent/chat/interrupt/sess-1"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data").value("OK"))

            verify(agentRunner).interrupt("sess-1")
        }

        @Test
        fun `interrupt with special characters in sessionId`() {
            mockMvc.perform(post("/api/agent/chat/interrupt/sess-abc-123"))
                .andExpect(status().isOk)

            verify(agentRunner).interrupt("sess-abc-123")
        }
    }

    // ==================== GET /api/agent/chat/history/{sessionId} ====================

    @Nested
    inner class HistoryEndpoint {
        @Test
        fun `loadHistory returns message list`() {
            val messages = listOf(
                UserMessageLog(message = "hello"),
                AssistantMessageLog(thinking = "", text = "Hi!", toolUseLog = emptyList()),
            )
            `when`(agentRunner.loadHistory("sess-1")).thenReturn(messages)

            mockMvc.perform(get("/api/agent/chat/history/sess-1"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray)
                .andExpect(jsonPath("$.data.length()").value(2))
        }

        @Test
        fun `loadHistory returns empty list`() {
            `when`(agentRunner.loadHistory("sess-empty")).thenReturn(emptyList())

            mockMvc.perform(get("/api/agent/chat/history/sess-empty"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data").isArray)
                .andExpect(jsonPath("$.data.length()").value(0))
        }
    }

    // ==================== DELETE /api/agent/session/{sessionId} ====================

    @Nested
    inner class ClearSessionEndpoint {
        @Test
        fun `clearSession delegates to runner`() {
            mockMvc.perform(delete("/api/agent/session/sess-1"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data").value("OK"))

            verify(agentRunner).clearSession("sess-1")
        }

        @Test
        fun `clearSession returns error when runner throws`() {
            doThrow(RuntimeException("Clear failed")).`when`(agentRunner).clearSession("sess-err")

            mockMvc.perform(delete("/api/agent/session/sess-err"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.message").value("Clear failed"))
        }
    }

    // ==================== GET /api/agent/session/{sessionId}/plans ====================

    @Nested
    inner class PlansEndpoint {
        @Test
        fun `loadPlans returns plan list`() {
            val plans = listOf(
                PlanNote(
                    sessionId = "sess-1",
                    planId = "plan-1",
                    name = "Plan A",
                    description = "Do things",
                    createdAt = "2026-06-21T00:00:00",
                    finishedAt = null,
                    costTimeSeconds = 0L,
                    status = TaskState.TODO,
                ),
            )
            `when`(agentRunner.loadPlans("sess-1")).thenReturn(plans)

            mockMvc.perform(get("/api/agent/session/sess-1/plans"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data").isArray)
                .andExpect(jsonPath("$.data[0].name").value("Plan A"))
        }

        @Test
        fun `loadPlans returns empty list`() {
            `when`(agentRunner.loadPlans("sess-1")).thenReturn(emptyList())

            mockMvc.perform(get("/api/agent/session/sess-1/plans"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.length()").value(0))
        }
    }

    // ==================== GET /api/agent/session/{sessionId}/current-plan ====================

    @Nested
    inner class CurrentPlanEndpoint {
        @Test
        fun `loadCurrentPlan returns null when no active plan`() {
            `when`(agentRunner.loadCurrentPlan("sess-1")).thenReturn(null)

            mockMvc.perform(get("/api/agent/session/sess-1/current-plan"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data").isEmpty)
        }

        @Test
        fun `loadCurrentPlan returns active plan`() {
            val plan = PlanNote(
                sessionId = "sess-1",
                planId = "plan-2",
                name = "Active",
                description = "Working...",
                createdAt = "2026-06-21T00:00:00",
                finishedAt = null,
                costTimeSeconds = 0L,
                status = TaskState.IN_PROGRESS,
            )
            `when`(agentRunner.loadCurrentPlan("sess-1")).thenReturn(plan)

            mockMvc.perform(get("/api/agent/session/sess-1/current-plan"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.name").value("Active"))
        }
    }

    // ==================== GET /api/agent/health ====================

    @Nested
    inner class HealthEndpoint {
        @Test
        fun `health returns UP`() {
            mockMvc.perform(get("/api/agent/health"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data").value("UP"))
        }
    }
}

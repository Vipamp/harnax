package com.agnetix.harnax.agent.service.runner.impl

import com.agnetix.harnax.agent.chat.MessageLog
import com.agnetix.harnax.agent.chat.UserMessageLog
import com.agnetix.harnax.agent.protocol.ChatAgentRequest
import com.agnetix.harnax.agent.protocol.ChatEvent
import com.agnetix.harnax.agent.protocol.ChatResponse
import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.agent.protocol.CommandType
import com.agnetix.harnax.agent.protocol.ConfirmAgentRequest
import com.agnetix.harnax.agent.protocol.EndEventChatEvent
import com.agnetix.harnax.agent.protocol.ErrorChatEvent
import com.agnetix.harnax.agent.protocol.StreamTextChatEvent
import com.agnetix.harnax.agent.protocol.ToolInfo
import com.agnetix.harnax.common.error.HarnaxErrorCode
import com.agnetix.harnax.common.error.HarnaxException
import com.agnetix.harnax.entity.Session
import com.agnetix.harnax.harness.HarnessAgentLauncher
import com.agnetix.harnax.harness.HarnessAgentWrapper
import com.agnetix.harnax.harness.sandbox.KeepAliveSandboxManager
import com.agnetix.harnax.mapper.SessionMapper
import com.agnetix.harnax.mapper.SkillMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import tools.jackson.databind.ObjectMapper

class DefaultAgentRunnerTest {

    private lateinit var launcher: HarnessAgentLauncher
    private lateinit var sessionMapper: SessionMapper
    private lateinit var skillMapper: SkillMapper
    private lateinit var objectMapper: ObjectMapper
    private lateinit var runner: DefaultAgentRunner
    private lateinit var agentWrapper: HarnessAgentWrapper

    @BeforeEach
    fun setUp() {
        launcher = mock(HarnessAgentLauncher::class.java)
        sessionMapper = mock(SessionMapper::class.java)
        skillMapper = mock(SkillMapper::class.java)
        objectMapper = ObjectMapper()
        agentWrapper = mock(HarnessAgentWrapper::class.java)

        runner = DefaultAgentRunner(
            launcher = launcher,
            sessionMapper = sessionMapper,
            skillMapper = skillMapper,
            objectMapper = objectMapper,
            cacheMaxSize = 100L,
        )
    }

    private fun stubSession(): Session = Session().apply {
        agentId = 1L
        name = "TestAgent"
        description = "A test agent"
        systemPrompt = "You are a test assistant"
        modelId = 100L
        enableThink = 0
        enableSearch = 0
        enablePlan = 0
        mcpList = "[]"
        skillList = "[]"
    }

    private fun stubAgentCreation() {
        `when`(sessionMapper.selectBySessionIdAndStatus(any(), any<Int>())).thenReturn(stubSession())
        `when`(launcher.createSingleAgent(any(), any(), any<Boolean>(), any(), any())).thenReturn(agentWrapper)
    }

    // ==================== executeCommand ====================

    @Nested
    inner class ExecuteCommand {
        @Test
        fun `executeCommand INTERRUPT cancels active stream`() {
            val request = CommandAgentRequest(
                sessionId = "session-1",
                command = CommandType.INTERRUPT,
            )

            val response = runner.executeCommand(request)

            assertTrue(response.success)
            assertEquals("Stream interrupted", response.message)
        }

        @Test
        fun `executeCommand CLEAR clears session`() {
            val request = CommandAgentRequest(
                sessionId = "session-1",
                command = CommandType.CLEAR,
            )

            val response = runner.executeCommand(request)

            assertTrue(response.success)
            assertEquals("Session cleared", response.message)
            verify(launcher).clearSession("session-1")
        }

        @Test
        fun `executeCommand COMPACT returns not-implemented message`() {
            val request = CommandAgentRequest(
                sessionId = "session-1",
                command = CommandType.COMPACT,
                args = "500",
            )

            val response = runner.executeCommand(request)

            assertTrue(response.success)
            assertTrue(response.message?.contains("not yet implemented") == true)
        }

        @Test
        fun `executeCommand APPROVE returns not-implemented message`() {
            val request = CommandAgentRequest(
                sessionId = "session-1",
                command = CommandType.APPROVE,
            )

            val response = runner.executeCommand(request)

            assertTrue(response.success)
            assertTrue(response.message?.contains("not yet implemented") == true)
        }

        @Test
        fun `executeCommand STOP_SANDBOX succeeds when sandbox manager available`() {
            val sandboxManager = mock(KeepAliveSandboxManager::class.java)
            `when`(launcher.keepAliveSandboxManager).thenReturn(sandboxManager)

            val request = CommandAgentRequest(
                sessionId = "session-1",
                command = CommandType.STOP_SANDBOX,
            )

            val response = runner.executeCommand(request)

            assertTrue(response.success)
            assertEquals("Sandbox stopped", response.message)
            verify(sandboxManager).destroy("session-1")
        }

        @Test
        fun `executeCommand STOP_SANDBOX fails when sandbox manager is null`() {
            `when`(launcher.keepAliveSandboxManager).thenReturn(null)

            val request = CommandAgentRequest(
                sessionId = "session-1",
                command = CommandType.STOP_SANDBOX,
            )

            val response = runner.executeCommand(request)

            assertFalse(response.success)
            assertEquals("Sandbox manager not available", response.message)
        }
    }

    // ==================== interrupt ====================

    @Nested
    inner class Interrupt {
        @Test
        fun `interrupt does nothing when no active stream`() {
            assertDoesNotThrow { runner.interrupt("nonexistent-session") }
        }
    }

    // ==================== clearSession ====================

    @Nested
    inner class ClearSession {
        @Test
        fun `clearSession delegates to launcher`() {
            runner.clearSession("session-1")

            verify(launcher).clearSession("session-1")
        }

        @Test
        fun `clearSession also interrupts active stream`() {
            assertDoesNotThrow { runner.clearSession("session-1") }
        }
    }

    // ==================== loadHistory ====================

    @Nested
    inner class LoadHistory {
        @Test
        fun `loadHistory delegates to launcher and converts messages`() {
            `when`(launcher.loadSessionMessages("session-1")).thenReturn(emptyList())

            val history = runner.loadHistory("session-1")

            assertTrue(history.isEmpty())
            verify(launcher).loadSessionMessages("session-1")
        }
    }

    // ==================== loadPlans ====================

    @Nested
    inner class LoadPlans {
        @Test
        fun `loadPlans delegates to launcher`() {
            `when`(launcher.loadSessionHistoryPlan("session-1")).thenReturn(emptyList())

            val plans = runner.loadPlans("session-1")

            assertTrue(plans.isEmpty())
            verify(launcher).loadSessionHistoryPlan("session-1")
        }
    }

    // ==================== loadCurrentPlan ====================

    @Nested
    inner class LoadCurrentPlan {
        @Test
        fun `loadCurrentPlan delegates to launcher`() {
            `when`(launcher.loadSessionCurrentPlanNote("session-1")).thenReturn(null)

            val plan = runner.loadCurrentPlan("session-1")

            assertNull(plan)
        }
    }

    // ==================== streamProcess ====================

    @Nested
    inner class StreamProcess {
        @Test
        fun `streamProcess returns error events when session not found`() {
            `when`(sessionMapper.selectBySessionIdAndStatus(any(), any<Int>())).thenReturn(null)

            val request = ChatAgentRequest(
                sessionId = "invalid-session",
                message = "hello",
            )

            val result = runner.streamProcess(request)

            StepVerifier.create(result)
                .expectNextMatches { it is ErrorChatEvent }
                .expectNextMatches { it is EndEventChatEvent }
                .verifyComplete()
        }

        @Test
        fun `streamProcess returns agent stream on success`() {
            stubAgentCreation()
            val textEvent = StreamTextChatEvent("Hello!", false, null)
            val endEvent = EndEventChatEvent()
            `when`(agentWrapper.callStream(any<String>(), any())).thenReturn(Flux.just(textEvent, endEvent))

            val request = ChatAgentRequest(
                sessionId = "session-1",
                message = "hello",
            )

            val result = runner.streamProcess(request)

            StepVerifier.create(result)
                .expectNextCount(2)
                .verifyComplete()
        }
    }

    // ==================== process ====================

    @Nested
    inner class Process {
        @Test
        fun `process delegates to agent call`() {
            stubAgentCreation()
            val chatResponse = ChatResponse(sessionId = "session-1", content = "Hello!")
            `when`(agentWrapper.call(any<String>(), any())).thenReturn(chatResponse)

            val request = ChatAgentRequest(
                sessionId = "session-1",
                message = "hello",
            )

            val result = runner.process(request)

            assertEquals("Hello!", result.content)
        }

        @Test
        fun `process throws HarnaxException when session not found`() {
            `when`(sessionMapper.selectBySessionIdAndStatus(any(), any<Int>())).thenReturn(null)

            val request = ChatAgentRequest(
                sessionId = "invalid-session",
                message = "hello",
            )

            assertThrows(HarnaxException::class.java) {
                runner.process(request)
            }
        }
    }

    // ==================== confirm ====================

    @Nested
    inner class Confirm {
        @Test
        fun `confirm with isConfirmed true calls agent callStream`() {
            stubAgentCreation()
            `when`(agentWrapper.callStream(msg = null)).thenReturn(Flux.just(EndEventChatEvent()))

            val request = ConfirmAgentRequest(
                sessionId = "session-1",
                isConfirmed = true,
            )

            val result = runner.confirm(request)

            StepVerifier.create(result)
                .expectNextCount(1)
                .verifyComplete()
        }

        @Test
        fun `confirm with isConfirmed false sends cancel result to agent`() {
            stubAgentCreation()
            `when`(agentWrapper.callStream(any())).thenReturn(Flux.just(EndEventChatEvent()))

            val request = ConfirmAgentRequest(
                sessionId = "session-1",
                isConfirmed = false,
                toolInfoList = listOf(ToolInfo(toolId = "tool-1", toolName = "delete_file")),
            )

            val result = runner.confirm(request)

            StepVerifier.create(result)
                .expectNextCount(1)
                .verifyComplete()
        }
    }

    // ==================== initAgent / destroyAgent ====================

    @Nested
    inner class Lifecycle {
        @Test
        fun `initAgent does not throw`() {
            kotlinx.coroutines.runBlocking {
                assertDoesNotThrow { runner.initAgent(1L) }
            }
        }

        @Test
        fun `destroyAgent clears all cached agents`() {
            kotlinx.coroutines.runBlocking {
                runner.destroyAgent(1L)
            }
        }
    }

    // ==================== MCP list parsing edge cases ====================

    @Nested
    inner class McpListParsing {
        @Test
        fun `getOrCreateAgent handles invalid MCP JSON gracefully`() {
            val session = stubSession().apply { mcpList = "{invalid json" }
            `when`(sessionMapper.selectBySessionIdAndStatus(any(), any<Int>())).thenReturn(session)
            `when`(launcher.createSingleAgent(any(), any(), any<Boolean>(), any(), any())).thenReturn(agentWrapper)
            `when`(agentWrapper.call(any<String>(), any())).thenReturn(
                ChatResponse(sessionId = "s", content = "ok"),
            )

            val result = runner.process(ChatAgentRequest(sessionId = "s", message = "hi"))
            assertEquals("ok", result.content)
            verify(launcher).createSingleAgent(any(), any(), any<Boolean>(), any(), any())
        }

        @Test
        fun `getOrCreateAgent skips empty MCP list`() {
            val session = stubSession().apply { mcpList = "[]" }
            `when`(sessionMapper.selectBySessionIdAndStatus(any(), any<Int>())).thenReturn(session)
            `when`(launcher.createSingleAgent(any(), any(), any<Boolean>(), any(), any())).thenReturn(agentWrapper)
            `when`(agentWrapper.call(any<String>(), any())).thenReturn(
                ChatResponse(sessionId = "s", content = "ok"),
            )

            val result = runner.process(ChatAgentRequest(sessionId = "s", message = "hi"))
            assertEquals("ok", result.content)
        }

        @Test
        fun `getOrCreateAgent parses valid MCP list with enable_skip`() {
            val session = stubSession().apply {
                mcpList = """[{"id":1,"enable_skip":"true"},{"id":2}]"""
            }
            `when`(sessionMapper.selectBySessionIdAndStatus(any(), any<Int>())).thenReturn(session)
            `when`(launcher.createSingleAgent(any(), any(), any<Boolean>(), any(), any())).thenReturn(agentWrapper)
            `when`(agentWrapper.call(any<String>(), any())).thenReturn(
                ChatResponse(sessionId = "s", content = "ok"),
            )

            val result = runner.process(ChatAgentRequest(sessionId = "s", message = "hi"))
            assertEquals("ok", result.content)
        }
    }

    // ==================== Skill list parsing edge cases ====================

    @Nested
    inner class SkillListParsing {
        @Test
        fun `getOrCreateAgent handles non-numeric skill IDs gracefully`() {
            val session = stubSession().apply { skillList = "abc,def" }
            `when`(sessionMapper.selectBySessionIdAndStatus(any(), any<Int>())).thenReturn(session)
            `when`(launcher.createSingleAgent(any(), any(), any<Boolean>(), any(), any())).thenReturn(agentWrapper)
            `when`(agentWrapper.call(any<String>(), any())).thenReturn(
                ChatResponse(sessionId = "s", content = "ok"),
            )

            val result = runner.process(ChatAgentRequest(sessionId = "s", message = "hi"))
            assertEquals("ok", result.content)
        }

        @Test
        fun `getOrCreateAgent handles skill not found in DB`() {
            val session = stubSession().apply { skillList = "999" }
            `when`(sessionMapper.selectBySessionIdAndStatus(any(), any<Int>())).thenReturn(session)
            `when`(skillMapper.selectById(999L)).thenReturn(null)
            `when`(launcher.createSingleAgent(any(), any(), any<Boolean>(), any(), any())).thenReturn(agentWrapper)
            `when`(agentWrapper.call(any<String>(), any())).thenReturn(
                ChatResponse(sessionId = "s", content = "ok"),
            )

            val result = runner.process(ChatAgentRequest(sessionId = "s", message = "hi"))
            assertEquals("ok", result.content)
        }

        @Test
        fun `getOrCreateAgent skips empty skill list`() {
            val session = stubSession().apply { skillList = "[]" }
            `when`(sessionMapper.selectBySessionIdAndStatus(any(), any<Int>())).thenReturn(session)
            `when`(launcher.createSingleAgent(any(), any(), any<Boolean>(), any(), any())).thenReturn(agentWrapper)
            `when`(agentWrapper.call(any<String>(), any())).thenReturn(
                ChatResponse(sessionId = "s", content = "ok"),
            )

            val result = runner.process(ChatAgentRequest(sessionId = "s", message = "hi"))
            assertEquals("ok", result.content)
        }
    }

    // ==================== Agent caching ====================

    @Nested
    inner class AgentCaching {
        @Test
        fun `second call reuses cached agent without creating new one`() {
            stubAgentCreation()
            `when`(agentWrapper.call(any<String>(), any())).thenReturn(
                ChatResponse(sessionId = "session-1", content = "ok"),
            )

            runner.process(ChatAgentRequest(sessionId = "session-1", message = "first"))
            runner.process(ChatAgentRequest(sessionId = "session-1", message = "second"))

            verify(launcher, times(1)).createSingleAgent(any(), any(), any<Boolean>(), any(), any())
        }

        @Test
        fun `clearSession invalidates cache so next call creates new agent`() {
            stubAgentCreation()
            `when`(agentWrapper.call(any<String>(), any())).thenReturn(
                ChatResponse(sessionId = "session-1", content = "ok"),
            )

            runner.process(ChatAgentRequest(sessionId = "session-1", message = "first"))
            runner.clearSession("session-1")
            runner.process(ChatAgentRequest(sessionId = "session-1", message = "second"))

            verify(launcher, times(2)).createSingleAgent(any(), any(), any<Boolean>(), any(), any())
        }
    }

    // ==================== Confirm fallback ====================

    @Nested
    inner class ConfirmFallback {
        @Test
        fun `confirm creates agent when not in cache`() {
            stubAgentCreation()
            `when`(agentWrapper.callStream(msg = null)).thenReturn(Flux.just(EndEventChatEvent()))

            val request = ConfirmAgentRequest(sessionId = "session-1", isConfirmed = true)
            val result = runner.confirm(request)

            StepVerifier.create(result)
                .expectNextCount(1)
                .verifyComplete()
            verify(launcher).createSingleAgent(any(), any(), any<Boolean>(), any(), any())
        }

        @Test
        fun `confirm with empty toolInfoList and isConfirmed false`() {
            stubAgentCreation()
            `when`(agentWrapper.callStream(any())).thenReturn(Flux.just(EndEventChatEvent()))

            val request = ConfirmAgentRequest(
                sessionId = "session-1",
                isConfirmed = false,
                toolInfoList = emptyList(),
            )
            val result = runner.confirm(request)

            StepVerifier.create(result)
                .expectNextCount(1)
                .verifyComplete()
        }
    }
}

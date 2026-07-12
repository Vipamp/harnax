package com.agnetix.harnax.agent.service.runner.impl

import com.agnetix.harnax.agent.AgentSpec
import com.agnetix.harnax.agent.ChatSpecBuilder
import com.agnetix.harnax.agent.protocol.ChatAgentRequest
import com.agnetix.harnax.agent.protocol.ChatResponse
import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.agent.protocol.CommandType
import com.agnetix.harnax.agent.protocol.ConfirmAgentRequest
import com.agnetix.harnax.agent.protocol.EndEventChatEvent
import com.agnetix.harnax.agent.protocol.ErrorChatEvent
import com.agnetix.harnax.agent.protocol.StreamTextChatEvent
import com.agnetix.harnax.agent.protocol.ToolInfo
import com.agnetix.harnax.agent.service.runner.AgentSpecResolver
import com.agnetix.harnax.common.error.HarnaxException
import com.agnetix.harnax.entity.Channel
import com.agnetix.harnax.entity.Session
import com.agnetix.harnax.harness.HarnessAgentLauncher
import com.agnetix.harnax.harness.HarnessAgentWrapper
import com.agnetix.harnax.harness.sandbox.KeepAliveSandboxManager
import com.agnetix.harnax.mapper.ChannelMapper
import com.agnetix.harnax.mapper.SessionMapper
import io.agentscope.core.message.ToolUseBlock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import reactor.core.publisher.Flux
import reactor.test.StepVerifier

class DefaultAgentRunnerTest {

    private lateinit var launcher: HarnessAgentLauncher
    private lateinit var agentSpecResolver: AgentSpecResolver
    private lateinit var sessionMapper: SessionMapper
    private lateinit var channelMapper: ChannelMapper
    private lateinit var runner: DefaultAgentRunner
    private lateinit var agentWrapper: HarnessAgentWrapper

    @BeforeEach
    fun setUp() {
        launcher = mock(HarnessAgentLauncher::class.java)
        agentSpecResolver = mock(AgentSpecResolver::class.java)
        sessionMapper = mock(SessionMapper::class.java)
        channelMapper = mock(ChannelMapper::class.java)
        agentWrapper = mock(HarnessAgentWrapper::class.java)

        runner = DefaultAgentRunner(
            launcher = launcher,
            agentSpecResolver = agentSpecResolver,
            sessionMapper = sessionMapper,
            channelMapper = channelMapper,
            cacheMaxSize = 100L,
        )
    }

    private fun stubAgentSpec(agentId: Long = 1L, name: String = "TestAgent") {
        val agentSpec = AgentSpec.builder()
            .id(agentId).name(name).description("A test agent")
            .systemPrompt("You are a test assistant").chatModelId(100L)
            .build()
        val chatSpec = ChatSpecBuilder().build()
        `when`(agentSpecResolver.resolve(any())).thenReturn(agentSpec to chatSpec)
    }

    private fun stubAgentCreation() {
        stubAgentSpec()
        `when`(launcher.createSingleAgent(any(), any(), any<Boolean>(), any(), any())).thenReturn(agentWrapper)
    }

    private fun stubPendingToolCalls() {
        `when`(agentWrapper.getPendingToolCalls()).thenReturn(
            listOf(
                ToolUseBlock("tool-1", "delete_file", emptyMap()),
            ),
        )
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
        fun `executeCommand APPROVE delegates to confirm and collects stream`() {
            stubAgentCreation()
            stubPendingToolCalls()
            `when`(agentWrapper.callStream(msg = any())).thenReturn(
                Flux.just(
                    StreamTextChatEvent("Tools approved, continuing...", false, null),
                    EndEventChatEvent(),
                ),
            )

            val request = CommandAgentRequest(
                sessionId = "session-1",
                command = CommandType.APPROVE,
            )

            val response = runner.executeCommand(request)

            assertTrue(response.success)
            assertTrue(response.message?.contains("Tools approved") == true)
        }

        @Test
        fun `executeCommand DENY delegates to confirm and collects stream`() {
            stubAgentCreation()
            stubPendingToolCalls()
            `when`(agentWrapper.callStream(msg = any())).thenReturn(
                Flux.just(EndEventChatEvent()),
            )

            val request = CommandAgentRequest(
                sessionId = "session-1",
                command = CommandType.DENY,
            )

            val response = runner.executeCommand(request)

            assertTrue(response.success)
            assertTrue(response.message?.contains("Tools denied") == true)
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

        @Test
        fun `executeCommand ENABLE search toggles enableSearch`() {
            val session = Session().apply {
                id = 1L
                sessionId = "session-1"
                enableSearch = 0
                enableThink = 0
                enablePlan = 0
            }
            `when`(sessionMapper.selectBySessionIdAndStatus("session-1", 1)).thenReturn(session)

            val request = CommandAgentRequest(
                sessionId = "session-1",
                command = CommandType.ENABLE,
                args = "search",
            )

            val response = runner.executeCommand(request)

            assertTrue(response.success)
            assertEquals("Search enabled", response.message)
            assertEquals(1, session.enableSearch)
            verify(sessionMapper).updateById(session)
        }

        @Test
        fun `executeCommand DISABLE thinking toggles enableThink`() {
            val session = Session().apply {
                id = 1L
                sessionId = "session-1"
                enableSearch = 0
                enableThink = 1
                enablePlan = 0
            }
            `when`(sessionMapper.selectBySessionIdAndStatus("session-1", 1)).thenReturn(session)

            val request = CommandAgentRequest(
                sessionId = "session-1",
                command = CommandType.DISABLE,
                args = "thinking",
            )

            val response = runner.executeCommand(request)

            assertTrue(response.success)
            assertEquals("Thinking disabled", response.message)
            assertEquals(0, session.enableThink)
            verify(sessionMapper).updateById(session)
        }

        @Test
        fun `executeCommand ENABLE plan toggles enablePlan`() {
            val session = Session().apply {
                id = 1L
                sessionId = "session-1"
                enableSearch = 0
                enableThink = 0
                enablePlan = 0
            }
            `when`(sessionMapper.selectBySessionIdAndStatus("session-1", 1)).thenReturn(session)

            val request = CommandAgentRequest(
                sessionId = "session-1",
                command = CommandType.ENABLE,
                args = "plan",
            )

            val response = runner.executeCommand(request)

            assertTrue(response.success)
            assertEquals("Plan enabled", response.message)
            assertEquals(1, session.enablePlan)
        }

        @Test
        fun `executeCommand ENABLE with unknown capability fails`() {
            val request = CommandAgentRequest(
                sessionId = "session-1",
                command = CommandType.ENABLE,
                args = "unknown",
            )

            val response = runner.executeCommand(request)

            assertFalse(response.success)
            assertTrue(response.message?.contains("Unknown capability") == true)
        }

        @Test
        fun `executeCommand ENABLE for channel session updates channel table`() {
            val channel = Channel().apply {
                id = 1L
                sessionId = "chn-test-123"
                enableSearch = 0
                enableThink = 0
                enablePlan = 0
            }
            `when`(channelMapper.selectBySessionId("chn-test-123")).thenReturn(channel)

            val request = CommandAgentRequest(
                sessionId = "chn-test-123",
                command = CommandType.ENABLE,
                args = "search",
            )

            val response = runner.executeCommand(request)

            assertTrue(response.success)
            assertEquals("Search enabled", response.message)
            assertEquals(1, channel.enableSearch)
            verify(channelMapper).updateById(channel)
        }

        @Test
        fun `executeCommand ENABLE for task session fails`() {
            val request = CommandAgentRequest(
                sessionId = "task-123-run",
                command = CommandType.ENABLE,
                args = "search",
            )

            val response = runner.executeCommand(request)

            assertFalse(response.success)
            assertTrue(response.message?.contains("not supported") == true)
        }

        @Test
        fun `executeCommand ENABLE with missing session fails`() {
            `when`(sessionMapper.selectBySessionIdAndStatus("session-1", 1)).thenReturn(null)

            val request = CommandAgentRequest(
                sessionId = "session-1",
                command = CommandType.ENABLE,
                args = "search",
            )

            val response = runner.executeCommand(request)

            assertFalse(response.success)
            assertTrue(response.message?.contains("Session not found") == true)
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
        fun `streamProcess returns error events when agent spec resolution fails`() {
            `when`(agentSpecResolver.resolve(any())).thenThrow(RuntimeException("Session not found"))

            val request = ChatAgentRequest(
                sessionId = "web-invalid",
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
        fun `process throws HarnaxException when agent spec resolution fails`() {
            `when`(agentSpecResolver.resolve(any())).thenThrow(RuntimeException("Session not found"))

            val request = ChatAgentRequest(
                sessionId = "web-invalid",
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
            stubPendingToolCalls()
            `when`(agentWrapper.callStream(msg = any())).thenReturn(Flux.just(EndEventChatEvent()))

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
            stubPendingToolCalls()
            `when`(agentWrapper.callStream(msg = any())).thenReturn(Flux.just(EndEventChatEvent()))

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
                runner.initAgent(1L)
            }
        }

        @Test
        fun `destroyAgent clears all cached agents`() {
            kotlinx.coroutines.runBlocking {
                runner.destroyAgent(1L)
            }
        }
    }

    // ==================== Agent spec resolver delegation ====================

    @Nested
    inner class AgentSpecResolverDelegation {
        @Test
        fun `process delegates to agentSpecResolver with correct sessionId`() {
            stubAgentCreation()
            `when`(agentWrapper.call(any<String>(), any())).thenReturn(
                ChatResponse(sessionId = "web-123", content = "ok"),
            )

            runner.process(ChatAgentRequest(sessionId = "web-123", message = "hi"))
            verify(agentSpecResolver).resolve("web-123")
        }

        @Test
        fun `task sessionId is passed to agentSpecResolver`() {
            stubAgentCreation()
            `when`(agentWrapper.call(any<String>(), any())).thenReturn(
                ChatResponse(sessionId = "task-1-abc", content = "ok"),
            )

            runner.process(ChatAgentRequest(sessionId = "task-1-abc", message = "hi"))
            verify(agentSpecResolver).resolve("task-1-abc")
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
            stubPendingToolCalls()
            `when`(agentWrapper.callStream(msg = any())).thenReturn(Flux.just(EndEventChatEvent()))

            val request = ConfirmAgentRequest(sessionId = "session-1", isConfirmed = true)
            val result = runner.confirm(request)

            StepVerifier.create(result)
                .expectNextCount(1)
                .verifyComplete()
            verify(launcher).createSingleAgent(any(), any(), any<Boolean>(), any(), any())
        }

        @Test
        fun `confirm returns error when no pending tool calls`() {
            stubAgentCreation()
            // getPendingToolCalls() returns empty list by default (Mockito)

            val request = ConfirmAgentRequest(
                sessionId = "session-1",
                isConfirmed = false,
                toolInfoList = emptyList(),
            )
            val result = runner.confirm(request)

            StepVerifier.create(result)
                .expectNextMatches { it is ErrorChatEvent }
                .expectNextMatches { it is EndEventChatEvent }
                .verifyComplete()
            verify(agentWrapper, never()).callStream(msg = any())
        }
    }
}

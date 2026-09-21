package com.agnetix.harnax.agent.service.runner.impl

import com.agnetix.harnax.agent.AgentSpec
import com.agnetix.harnax.agent.ChatSpecBuilder
import com.agnetix.harnax.agent.protocol.ChatAgentRequest
import com.agnetix.harnax.agent.protocol.ChatEvent
import com.agnetix.harnax.agent.protocol.ChatResponse
import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.agent.protocol.CommandType
import com.agnetix.harnax.agent.protocol.ConfirmAgentRequest
import com.agnetix.harnax.agent.protocol.EndEventChatEvent
import com.agnetix.harnax.agent.protocol.ErrorChatEvent
import com.agnetix.harnax.agent.protocol.StreamTextChatEvent
import com.agnetix.harnax.agent.protocol.ToolConfirmResult
import com.agnetix.harnax.agent.protocol.ToolInfo
import com.agnetix.harnax.agent.service.client.AdminApiClient
import com.agnetix.harnax.agent.service.client.AgentSpecContextHolder
import com.agnetix.harnax.agent.service.runner.AgentSpecResolver
import com.agnetix.harnax.agent.service.runner.TeamHistoryReplay
import com.agnetix.harnax.common.error.HarnaxErrorCode
import com.agnetix.harnax.common.error.HarnaxException
import com.agnetix.harnax.entity.dto.AgentSpecInfoResponse
import com.agnetix.harnax.harness.HarnessAgentLauncher
import com.agnetix.harnax.harness.HarnessAgentWrapper
import com.agnetix.harnax.harness.config.HarnessConfig
import com.agnetix.harnax.harness.sandbox.KeepAliveSandboxManager
import com.agnetix.harnax.harness.team.ConfirmationOutcome
import com.agnetix.harnax.harness.team.TeamArtifactGateway
import com.agnetix.harnax.harness.team.TeamOrchestrator
import com.agnetix.harnax.harness.team.TeamRuntimeSpec
import com.agnetix.harnax.tools.sdk.UserIdentifier
import io.agentscope.core.message.ToolUseBlock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.reactivestreams.Subscription
import org.springframework.beans.factory.ObjectProvider
import org.springframework.test.util.ReflectionTestUtils
import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DefaultAgentRunnerTest {

    private lateinit var launcher: HarnessAgentLauncher
    private lateinit var agentSpecResolver: AgentSpecResolver
    private lateinit var specContextHolder: AgentSpecContextHolder
    private lateinit var adminApiClient: AdminApiClient
    private lateinit var teamArtifactGateways: ObjectProvider<TeamArtifactGateway>
    private lateinit var runner: DefaultAgentRunner
    private lateinit var agentWrapper: HarnessAgentWrapper

    @Suppress("UNCHECKED_CAST")
    @BeforeEach
    fun setUp() {
        launcher = mock(HarnessAgentLauncher::class.java)
        agentSpecResolver = mock(AgentSpecResolver::class.java)
        specContextHolder = mock(AgentSpecContextHolder::class.java)
        agentWrapper = mock(HarnessAgentWrapper::class.java)
        adminApiClient = mock(AdminApiClient::class.java)
        // getIfAvailable() stays null: the same shape as a deployment without MinIO.
        teamArtifactGateways = mock(ObjectProvider::class.java) as ObjectProvider<TeamArtifactGateway>

        runner = DefaultAgentRunner(
            launcher = launcher,
            agentSpecResolver = agentSpecResolver,
            specContextHolder = specContextHolder,
            adminApiClient = adminApiClient,
            teamArtifactGateways = teamArtifactGateways,
            // Real object over the same mocks: no member child session is stubbed, so history stays
            // lead-only in these tests, which is the ordinary-session shape.
            teamHistoryReplay = TeamHistoryReplay(launcher, adminApiClient),
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
        fun `executeCommand INTERRUPT reports the miss instead of claiming success`() {
            val response = runner.executeCommand(
                CommandAgentRequest(sessionId = "session-1", command = CommandType.INTERRUPT),
            )

            assertFalse(response.success)
            assertEquals("No live execution for this session on this instance", response.message)
        }

        @Test
        fun `executeCommand INTERRUPT reports the hit and reaches the agent`() {
            stubAgentCreation()
            val insideCall = CountDownLatch(1)
            val letCallFinish = CountDownLatch(1)
            `when`(agentWrapper.call(any<String>(), any())).thenAnswer {
                insideCall.countDown()
                assertTrue(letCallFinish.await(5, TimeUnit.SECONDS), "test must let the call finish")
                ChatResponse(sessionId = "session-1", content = "done")
            }
            val caller = Thread {
                runner.process(ChatAgentRequest(sessionId = "session-1", message = "hello"))
            }
            caller.start()
            assertTrue(insideCall.await(5, TimeUnit.SECONDS), "the call should have started")

            val response = runner.executeCommand(
                CommandAgentRequest(sessionId = "session-1", command = CommandType.INTERRUPT),
            )

            letCallFinish.countDown()
            caller.join(5_000)

            assertTrue(response.success)
            assertEquals("Stream interrupted", response.message)
            verify(agentWrapper).interrupt()
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
        fun `executeCommand STOP_SANDBOX also destroys member child sandboxes`() {
            val sandboxManager = mock(KeepAliveSandboxManager::class.java)
            `when`(launcher.keepAliveSandboxManager).thenReturn(sandboxManager)
            // A member runs in a container of its own, and the cached orchestrator only knows the members
            // this instance built, so the stop command has to reach the rest through the state store.
            `when`(launcher.memberSessionIds("session-1")).thenReturn(listOf("team-session-1-m2"))

            val response = runner.executeCommand(
                CommandAgentRequest(sessionId = "session-1", command = CommandType.STOP_SANDBOX),
            )

            assertTrue(response.success)
            verify(sandboxManager).destroy("team-session-1-m2")
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
        fun `executeCommand ENABLE search delegates to adminApiClient`() {
            `when`(adminApiClient.toggleCapability("session-1", "search", true)).thenReturn(true)

            val request = CommandAgentRequest(
                sessionId = "session-1",
                command = CommandType.ENABLE,
                args = "search",
            )

            val response = runner.executeCommand(request)

            assertTrue(response.success)
            assertEquals("Search enabled", response.message)
            verify(adminApiClient).toggleCapability("session-1", "search", true)
        }

        @Test
        fun `executeCommand DISABLE thinking delegates to adminApiClient`() {
            `when`(adminApiClient.toggleCapability("session-1", "thinking", false)).thenReturn(true)

            val request = CommandAgentRequest(
                sessionId = "session-1",
                command = CommandType.DISABLE,
                args = "thinking",
            )

            val response = runner.executeCommand(request)

            assertTrue(response.success)
            assertEquals("Thinking disabled", response.message)
            verify(adminApiClient).toggleCapability("session-1", "thinking", false)
        }

        @Test
        fun `executeCommand ENABLE plan delegates to adminApiClient`() {
            `when`(adminApiClient.toggleCapability("session-1", "plan", true)).thenReturn(true)

            val request = CommandAgentRequest(
                sessionId = "session-1",
                command = CommandType.ENABLE,
                args = "plan",
            )

            val response = runner.executeCommand(request)

            assertTrue(response.success)
            assertEquals("Plan enabled", response.message)
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
        fun `executeCommand ENABLE for channel session delegates to adminApiClient`() {
            `when`(adminApiClient.toggleCapability("chn-test-123", "search", true)).thenReturn(true)

            val request = CommandAgentRequest(
                sessionId = "chn-test-123",
                command = CommandType.ENABLE,
                args = "search",
            )

            val response = runner.executeCommand(request)

            assertTrue(response.success)
            assertEquals("Search enabled", response.message)
            verify(adminApiClient).toggleCapability("chn-test-123", "search", true)
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

        /**
         * Contract C1 made a task session id one segment longer. This consumer classifies it by prefix
         * alone, so the extra segment has to change nothing — including that the refusal still happens
         * before anything is asked of admin.
         */
        @Test
        fun `executeCommand ENABLE for a four-segment task session is still refused as a task session`() {
            val request = CommandAgentRequest(
                sessionId = "task-123-45-6f0b1a2c3d4e5f60718293a4b5c6d7e8",
                command = CommandType.ENABLE,
                args = "search",
            )

            val response = runner.executeCommand(request)

            assertFalse(response.success)
            assertTrue(response.message?.contains("not supported") == true)
            verifyNoInteractions(adminApiClient)
        }

        @Test
        fun `executeCommand ENABLE fails when admin API returns false`() {
            `when`(adminApiClient.toggleCapability("session-1", "search", true)).thenReturn(false)

            val request = CommandAgentRequest(
                sessionId = "session-1",
                command = CommandType.ENABLE,
                args = "search",
            )

            val response = runner.executeCommand(request)

            assertFalse(response.success)
            assertTrue(response.message?.contains("Failed to toggle") == true)
        }

        @Test
        fun `executeCommand PERMISSION delegates to adminApiClient`() {
            `when`(adminApiClient.updatePermissionMode("session-1", "BYPASS")).thenReturn(true)

            val request = CommandAgentRequest(
                sessionId = "session-1",
                command = CommandType.PERMISSION,
                args = "BYPASS",
            )

            val response = runner.executeCommand(request)

            assertTrue(response.success)
            assertEquals("Permission mode set to BYPASS", response.message)
            verify(adminApiClient).updatePermissionMode("session-1", "BYPASS")
        }

        @Test
        fun `executeCommand PERMISSION with invalid mode fails`() {
            val request = CommandAgentRequest(
                sessionId = "session-1",
                command = CommandType.PERMISSION,
                args = "HACK",
            )

            val response = runner.executeCommand(request)

            assertFalse(response.success)
            assertTrue(response.message?.contains("Invalid permission mode") == true)
        }

        @Test
        fun `executeCommand PERMISSION for task session fails`() {
            val request = CommandAgentRequest(
                sessionId = "task-123-run",
                command = CommandType.PERMISSION,
                args = "DEFAULT",
            )

            val response = runner.executeCommand(request)

            assertFalse(response.success)
            assertTrue(response.message?.contains("not supported") == true)
        }

        /** The other `task-` branch in the runner, and the same C1 claim: a longer id is still a task session. */
        @Test
        fun `executeCommand PERMISSION for a four-segment task session is still refused as a task session`() {
            val request = CommandAgentRequest(
                sessionId = "task-123-45-6f0b1a2c3d4e5f60718293a4b5c6d7e8",
                command = CommandType.PERMISSION,
                args = "DEFAULT",
            )

            val response = runner.executeCommand(request)

            assertFalse(response.success)
            assertTrue(response.message?.contains("not supported") == true)
            verifyNoInteractions(adminApiClient)
        }

        @Test
        fun `executeCommand REFRESH invalidates cached agent`() {
            stubAgentCreation()
            `when`(agentWrapper.call(any<String>(), any())).thenReturn(
                ChatResponse(sessionId = "session-1", content = "ok"),
            )

            // First, create and cache the agent
            runner.process(ChatAgentRequest(sessionId = "session-1", message = "hi"))

            // Now refresh it
            val request = CommandAgentRequest(
                sessionId = "session-1",
                command = CommandType.REFRESH,
            )

            val response = runner.executeCommand(request)

            assertTrue(response.success)
            assertTrue(response.message?.contains("refreshed") == true)
        }

        @Test
        fun `executeCommand REFRESH succeeds when no cached agent`() {
            val request = CommandAgentRequest(
                sessionId = "session-no-cache",
                command = CommandType.REFRESH,
            )

            val response = runner.executeCommand(request)

            assertTrue(response.success)
            assertTrue(response.message?.contains("No active agent") == true)
        }

        @Test
        fun `executeCommand REFRESH then next call re-creates agent`() {
            stubAgentCreation()
            `when`(agentWrapper.call(any<String>(), any())).thenReturn(
                ChatResponse(sessionId = "session-1", content = "ok"),
            )

            // First call creates agent
            runner.process(ChatAgentRequest(sessionId = "session-1", message = "first"))

            // Refresh invalidates cache
            runner.executeCommand(CommandAgentRequest(sessionId = "session-1", command = CommandType.REFRESH))

            // Next call should re-create the agent
            runner.process(ChatAgentRequest(sessionId = "session-1", message = "second"))

            verify(launcher, times(2)).createSingleAgent(any(), any(), any<Boolean>(), any(), any())
        }

        @Test
        fun `executeCommand ENABLE bypass delegates to adminApiClient`() {
            `when`(adminApiClient.toggleCapability("session-1", "bypass", true)).thenReturn(true)

            val request = CommandAgentRequest(
                sessionId = "session-1",
                command = CommandType.ENABLE,
                args = "bypass",
            )

            val response = runner.executeCommand(request)

            assertTrue(response.success)
            assertEquals("Bypass enabled", response.message)
            verify(adminApiClient).toggleCapability("session-1", "bypass", true)
        }

        @Test
        fun `executeCommand DISABLE fails when admin API returns false`() {
            `when`(adminApiClient.toggleCapability("session-1", "thinking", false)).thenReturn(false)

            val request = CommandAgentRequest(
                sessionId = "session-1",
                command = CommandType.DISABLE,
                args = "thinking",
            )

            val response = runner.executeCommand(request)

            assertFalse(response.success)
            assertTrue(response.message?.contains("Failed to toggle") == true)
        }

        @Test
        fun `executeCommand PERMISSION fails when admin API returns false`() {
            `when`(adminApiClient.updatePermissionMode("session-1", "DEFAULT")).thenReturn(false)

            val request = CommandAgentRequest(
                sessionId = "session-1",
                command = CommandType.PERMISSION,
                args = "DEFAULT",
            )

            val response = runner.executeCommand(request)

            assertFalse(response.success)
            assertTrue(response.message?.contains("Failed to update") == true)
        }

        @Test
        fun `executeCommand PERMISSION for channel session delegates to adminApiClient`() {
            `when`(adminApiClient.updatePermissionMode("chn-test-123", "BYPASS")).thenReturn(true)

            val request = CommandAgentRequest(
                sessionId = "chn-test-123",
                command = CommandType.PERMISSION,
                args = "BYPASS",
            )

            val response = runner.executeCommand(request)

            assertTrue(response.success)
            assertEquals("Permission mode set to BYPASS", response.message)
            verify(adminApiClient).updatePermissionMode("chn-test-123", "BYPASS")
        }

        @Test
        fun `executeCommand ENABLE thinking fails when model does not support reasoning`() {
            val specInfo = com.agnetix.harnax.entity.dto.AgentSpecInfoResponse(
                agentId = 1L,
                agentName = "Test",
                description = "",
                systemPrompt = "",
                modelId = 1L,
                modelSupportReasoning = 0,
            )
            `when`(adminApiClient.getAgentSpec("session-1")).thenReturn(specInfo)

            val request = CommandAgentRequest(
                sessionId = "session-1",
                command = CommandType.ENABLE,
                args = "thinking",
            )

            val response = runner.executeCommand(request)

            assertFalse(response.success)
            assertTrue(response.message?.contains("does not support") == true)
        }

        @Test
        fun `executeCommand ENABLE search fails when model does not support internet`() {
            val specInfo = com.agnetix.harnax.entity.dto.AgentSpecInfoResponse(
                agentId = 1L,
                agentName = "Test",
                description = "",
                systemPrompt = "",
                modelId = 1L,
                modelSupportInternet = 0,
            )
            `when`(adminApiClient.getAgentSpec("session-1")).thenReturn(specInfo)

            val request = CommandAgentRequest(
                sessionId = "session-1",
                command = CommandType.ENABLE,
                args = "search",
            )

            val response = runner.executeCommand(request)

            assertFalse(response.success)
            assertTrue(response.message?.contains("does not support") == true)
        }

        @Test
        fun `executeCommand ENABLE search fails on a team session when the lead model lacks internet`() {
            // 团队主管没有 agent 行：能力校验只能读 /team-spec 的 lead，读 /agent-spec 会被 admin 拒掉
            val lead = com.agnetix.harnax.entity.dto.AgentSpecInfoResponse(
                agentId = 0L,
                agentName = "Research",
                description = "",
                systemPrompt = "coordinate",
                modelId = 100L,
                modelSupportInternet = 0,
            )
            `when`(agentSpecResolver.isTeamSession("session-1")).thenReturn(true)
            `when`(adminApiClient.getTeamSpec("session-1")).thenReturn(
                com.agnetix.harnax.entity.dto.TeamSpecInfoResponse(
                    teamId = 7L,
                    tenantId = 1L,
                    teamName = "Research",
                    lead = lead,
                ),
            )

            val request = CommandAgentRequest(
                sessionId = "session-1",
                command = CommandType.ENABLE,
                args = "search",
            )

            val response = runner.executeCommand(request)

            assertFalse(response.success)
            assertTrue(response.message?.contains("does not support") == true)
            verify(adminApiClient, never()).toggleCapability("session-1", "search", true)
        }

        @Test
        fun `executeCommand ENABLE thinking proceeds when model validation fails with exception`() {
            // Fail-open: admin API throws during model validation, toggle should still proceed
            `when`(adminApiClient.getAgentSpec("session-1")).thenThrow(RuntimeException("Connection timeout"))
            `when`(adminApiClient.toggleCapability("session-1", "thinking", true)).thenReturn(true)

            val request = CommandAgentRequest(
                sessionId = "session-1",
                command = CommandType.ENABLE,
                args = "thinking",
            )

            val response = runner.executeCommand(request)

            assertTrue(response.success)
            verify(adminApiClient).toggleCapability("session-1", "thinking", true)
        }
    }

    // ==================== interrupt ====================

    @Nested
    inner class Interrupt {
        @Test
        fun `interrupt reports a miss when neither an agent nor a stream is live`() {
            // The miss is the whole point: a caller must be able to tell "stopped" from "already gone".
            assertFalse(runner.interrupt("nonexistent-session"))
        }

        @Test
        fun `a session whose agent is still being built counts as a live execution`() {
            // process() registers the call before it builds the agent, because assembly plus sandbox
            // creation takes seconds and an INTERRUPT landing in that window is addressed to a run that
            // really is in flight. Answering "no live execution" here would let the caller finalise the
            // row while this thread goes on to create a sandbox nobody owns.
            stubAgentSpec()
            val insideBuild = CountDownLatch(1)
            val letBuildFinish = CountDownLatch(1)
            `when`(launcher.createSingleAgent(any(), any(), any<Boolean>(), any(), any())).thenAnswer {
                insideBuild.countDown()
                assertTrue(
                    letBuildFinish.await(5, TimeUnit.SECONDS),
                    "test must let the agent build finish",
                )
                agentWrapper
            }
            val caller = Thread {
                runCatching {
                    runner.process(ChatAgentRequest(sessionId = "session-building", message = "hello"))
                }
            }
            caller.start()
            assertTrue(insideBuild.await(5, TimeUnit.SECONDS), "the agent build should have started")

            val live = runner.interrupt("session-building")

            letBuildFinish.countDown()
            caller.join(5_000)

            assertTrue(live, "a session mid-build is an execution in flight, not a miss")
        }

        @Test
        fun `an agent left in the cache with nothing running is not a live execution`() {
            // Was: `a cached agent alone counts as a live execution`, asserting assertTrue here. That
            // was wrong: agentCache is a 30-minute TTL cache, so a cache hit says only that this
            // instance once served the session — not that anything is progressing it. Answering
            // "hit" to a stop request on that basis left the scheduler node with no owning process
            // and no final status, and the row was eventually labelled a timeout by the reaper.
            // Now: still a miss even though interrupt() does reach into the cached wrapper.
            stubAgentCreation()
            `when`(agentWrapper.call(any<String>(), any())).thenReturn(
                ChatResponse(sessionId = "session-cached", content = "ok"),
            )
            runner.process(ChatAgentRequest(sessionId = "session-cached", message = "hi"))

            assertFalse(runner.interrupt("session-cached"))
            verify(agentWrapper).interrupt()
        }

        @Test
        fun `an active stream is cancelled and counts as a live execution`() {
            // The arm the old test name promised but never actually exercised.
            val subscription = mock(Subscription::class.java)

            @Suppress("UNCHECKED_CAST")
            val activeStreams = ReflectionTestUtils.getField(runner, "activeStreams") as MutableMap<String, Subscription>
            activeStreams["session-stream"] = subscription

            assertTrue(runner.interrupt("session-stream"))

            verify(subscription).cancel()
            assertFalse(activeStreams.containsKey("session-stream"))
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

        @Test
        fun `clearSession clears every member child session even with nothing cached`() {
            // The state store is the source: this instance may have restarted, or another node served the
            // run, and a member that kept its conversation would otherwise remember what was just erased.
            `when`(launcher.memberSessionIds("session-1")).thenReturn(listOf("team-session-1-m2", "team-session-1-m3"))

            runner.clearSession("session-1")

            verify(launcher).clearSession("team-session-1-m2")
            verify(launcher).clearSession("team-session-1-m3")
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
        fun `process defers agent release until the blocking call returns`() {
            stubAgentCreation()
            val insideCall = CountDownLatch(1)
            val letCallFinish = CountDownLatch(1)
            `when`(agentWrapper.call(any<String>(), any())).thenAnswer {
                insideCall.countDown()
                assertTrue(letCallFinish.await(5, TimeUnit.SECONDS), "test must let the call finish")
                ChatResponse(sessionId = "session-1", content = "done")
            }

            val caller = Thread {
                runner.process(ChatAgentRequest(sessionId = "session-1", message = "hello"))
            }
            caller.start()
            assertTrue(insideCall.await(5, TimeUnit.SECONDS), "the call should have started")

            // REFRESH invalidates the cache, which is what reaches the removal listener
            runner.executeCommand(
                CommandAgentRequest(sessionId = "session-1", command = CommandType.REFRESH),
            )

            // `after` gives the listener (which Caffeine runs on its own thread) a chance to
            // release early — releasing here would tear the agent's MCP tools down mid-call.
            verify(agentWrapper, after(300).never()).release()

            letCallFinish.countDown()
            caller.join(5_000)

            verify(agentWrapper, timeout(5_000)).release()
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

    // ==================== team ====================

    @Nested
    inner class Team {
        private val orchestrator = mock(TeamOrchestrator::class.java)

        /**
         * Puts a team session in front of the runner: admin reports the session as a team before any spec
         * is resolved (its `/agent-spec` refuses team sessions), and the lead wrapper carries the
         * orchestrator that build produced.
         */
        private fun stubTeamSession(sessionId: String) {
            val specInfo = AgentSpecInfoResponse(
                agentId = 0L,
                agentName = "Research",
                description = "the team",
                systemPrompt = "coordinate",
                modelId = 100L,
            )
            `when`(agentSpecResolver.isTeamSession(sessionId)).thenReturn(true)
            `when`(
                agentSpecResolver.resolveTeam(sessionId),
            ).thenReturn(
                TeamRuntimeSpec(
                    teamId = 7L,
                    tenantId = 1L,
                    teamName = "Research",
                    rootSessionId = sessionId,
                    leadAgentSpec = AgentSpec.builder().id(0L).name("Research").chatModelId(100L).build(),
                    leadChatSpec = ChatSpecBuilder().build(),
                    leadSpecInfo = specInfo,
                    members = emptyList(),
                ),
            )
            `when`(launcher.harnessConfig).thenReturn(HarnessConfig())
            `when`(launcher.createTeamLead(any(), any(), any(), any(), any())).thenReturn(agentWrapper)
            `when`(agentWrapper.teamOrchestrator).thenReturn(orchestrator)
            `when`(agentWrapper.call(any<String>(), any())).thenReturn(
                ChatResponse(sessionId = sessionId, content = "done"),
            )
        }

        /** Warms the agent cache the way a first message would, so later calls see a live team agent. */
        private fun cachedTeamAgent(sessionId: String) {
            stubTeamSession(sessionId)
            runner.process(ChatAgentRequest(sessionId = sessionId, message = "go"))
        }

        @Test
        fun `a session with a team is built as a lead, not as the single agent its agentId names`() {
            stubTeamSession("web-team")

            runner.process(ChatAgentRequest(sessionId = "web-team", message = "go"))

            verify(launcher).createTeamLead(any(), any(), any(), any(), any())
            verify(launcher, never()).createSingleAgent(any(), any(), any<Boolean>(), any(), any())
            // Admin refuses the agent-spec call for a team session, so making it would fail the chat.
            verify(agentSpecResolver, never()).resolve(any())
        }

        @Test
        fun `a member confirmation answers that run and adds no stream of its own`() {
            cachedTeamAgent("web-team")
            `when`(orchestrator.answerConfirmation("run-1", true)).thenReturn(ConfirmationOutcome.APPROVED)

            val result = runner.confirm(
                ConfirmAgentRequest(sessionId = "web-team", isConfirmed = true, childRunId = "run-1"),
            )

            // The lead's stream is still open and carries the resumed output, so this response only ends.
            StepVerifier.create(result).expectNextMatches { it is EndEventChatEvent }.verifyComplete()
            verify(orchestrator).answerConfirmation("run-1", true)
            verify(agentWrapper, never()).getPendingToolCalls()
            verify(launcher, times(1)).createTeamLead(any(), any(), any(), any(), any())
        }

        @Test
        fun `a mixed per-tool answer denies the member run instead of executing the unchecked tools`() {
            cachedTeamAgent("web-team")
            `when`(orchestrator.answerConfirmation("run-1", false)).thenReturn(ConfirmationOutcome.DENIED)

            runner.confirm(
                ConfirmAgentRequest(
                    sessionId = "web-team",
                    isConfirmed = true,
                    childRunId = "run-1",
                    toolResults = listOf(
                        ToolConfirmResult(toolId = "t-1", toolName = "read_file", confirmed = true),
                        ToolConfirmResult(toolId = "t-2", toolName = "delete_file", confirmed = false),
                    ),
                ),
            )

            verify(orchestrator).answerConfirmation("run-1", false)
        }

        @Test
        fun `a member confirmation with no live team agent is refused without building one`() {
            val result = runner.confirm(
                ConfirmAgentRequest(sessionId = "web-gone", isConfirmed = true, childRunId = "run-1"),
            )

            StepVerifier.create(result)
                .expectNextMatches { it is ErrorChatEvent && it.code == HarnaxErrorCode.RESOURCE_NOT_FOUND.code }
                .expectNextMatches { it is EndEventChatEvent }
                .verifyComplete()
            verify(agentSpecResolver, never()).resolveTeam(any())
        }

        @Test
        fun `stopping a team session stops the members before the lead`() {
            cachedTeamAgent("web-team")

            runner.interrupt("web-team")

            val order = inOrder(orchestrator, agentWrapper)
            order.verify(orchestrator).stop(false)
            order.verify(agentWrapper).interrupt()
        }

        @Test
        fun `member events reach the user on the lead's stream`() {
            cachedTeamAgent("web-team")
            val memberEvent = StreamTextChatEvent("member working", false, null)
            `when`(orchestrator.openEventStream()).thenReturn(Flux.just(memberEvent))
            `when`(agentWrapper.callStream(any<String>(), any())).thenReturn(
                Flux.just(StreamTextChatEvent("lead thinking", false, null), EndEventChatEvent()),
            )

            val events = runner.streamProcess(ChatAgentRequest(sessionId = "web-team", message = "go"))
                .collectList().block()!!

            assertTrue(events.contains(memberEvent), "member events must be merged into the root stream")
            verify(orchestrator).closeEventStream()
        }

        @Test
        fun `a lead resumed by confirmation keeps carrying member events`() {
            cachedTeamAgent("web-team")
            stubPendingToolCalls()
            val memberEvent = StreamTextChatEvent("member awaiting a decision", false, null)
            `when`(orchestrator.openEventStream()).thenReturn(Flux.just(memberEvent))
            `when`(agentWrapper.callStream(msg = any())).thenReturn(
                Flux.just(EndEventChatEvent()),
            )

            val events = runner.confirm(ConfirmAgentRequest(sessionId = "web-team", isConfirmed = true))
                .collectList().block()!!

            // The delegate this confirmation approves parks a member on a confirmation of its own, and this
            // resumed stream is the only reader that can deliver that card and its heartbeats.
            assertTrue(events.contains(memberEvent), "the resumed lead must carry the member events it delegated to")
            verify(orchestrator).closeEventStream()
        }

        @Test
        fun `a new team run is refused while the previous one still owns the event stream`() {
            cachedTeamAgent("web-team")
            `when`(orchestrator.openEventStream()).thenReturn(null)
            var leadSubscribed = false
            `when`(agentWrapper.callStream(any<String>(), any())).thenReturn(
                Flux.just<ChatEvent>(EndEventChatEvent()).doOnSubscribe { leadSubscribed = true },
            )

            val result = runner.streamProcess(ChatAgentRequest(sessionId = "web-team", message = "go"))

            StepVerifier.create(result)
                .expectNextMatches { it is ErrorChatEvent && it.code == HarnaxErrorCode.RESOURCE_LOCKED.code }
                .expectNextMatches { it is EndEventChatEvent }
                .verifyComplete()
            assertFalse(leadSubscribed, "a refused run must not start the lead")
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

    // ==================== End-user identity ====================

    @Nested
    inner class UserIdentity {
        @Test
        fun `process builds the agent with the request user`() {
            stubAgentCreation()
            `when`(agentWrapper.call(any<String>(), any())).thenReturn(
                ChatResponse(sessionId = "session-1", content = "ok"),
            )

            runner.process(ChatAgentRequest(sessionId = "session-1", message = "hi", userId = 42L))

            val captor = argumentCaptor<UserIdentifier>()
            verify(launcher).createSingleAgent(any(), any(), any<Boolean>(), any(), captor.capture())
            assertEquals(42L, captor.firstValue.userId)
        }

        @Test
        fun `confirm rebuild builds the agent with the request user`() {
            stubAgentCreation()
            stubPendingToolCalls()
            `when`(agentWrapper.callStream(msg = any())).thenReturn(Flux.just(EndEventChatEvent()))

            val result = runner.confirm(
                ConfirmAgentRequest(sessionId = "session-1", isConfirmed = true, userId = 42L),
            )
            StepVerifier.create(result).expectNextCount(1).verifyComplete()

            val captor = argumentCaptor<UserIdentifier>()
            verify(launcher).createSingleAgent(any(), any(), any<Boolean>(), any(), captor.capture())
            assertEquals(42L, captor.firstValue.userId)
        }

        @Test
        fun `same session under a different user rebuilds the agent instead of reusing it`() {
            stubAgentCreation()
            `when`(agentWrapper.call(any<String>(), any())).thenReturn(
                ChatResponse(sessionId = "session-1", content = "ok"),
            )

            runner.process(ChatAgentRequest(sessionId = "session-1", message = "a", userId = 1L))
            runner.process(ChatAgentRequest(sessionId = "session-1", message = "b", userId = 2L))

            val captor = argumentCaptor<UserIdentifier>()
            verify(launcher, times(2)).createSingleAgent(any(), any(), any<Boolean>(), any(), captor.capture())
            assertEquals(listOf(1L, 2L), captor.allValues.map { it.userId })
        }

        @Test
        fun `a caller with no end user leaves the agent user null instead of a fake id`() {
            stubAgentCreation()
            `when`(agentWrapper.call(any<String>(), any())).thenReturn(
                ChatResponse(sessionId = "session-1", content = "ok"),
            )

            runner.process(ChatAgentRequest(sessionId = "session-1", message = "hi"))

            val captor = argumentCaptor<UserIdentifier>()
            verify(launcher).createSingleAgent(any(), any(), any<Boolean>(), any(), captor.capture())
            assertNull(captor.firstValue.userId)
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

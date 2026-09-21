package com.agnetix.harnax.harness.team

import com.agnetix.harnax.agent.AgentSpec
import com.agnetix.harnax.agent.ChatSpecBuilder
import com.agnetix.harnax.agent.protocol.ChatEvent
import com.agnetix.harnax.agent.protocol.EndEventChatEvent
import com.agnetix.harnax.agent.protocol.ErrorChatEvent
import com.agnetix.harnax.agent.protocol.EventSource
import com.agnetix.harnax.agent.protocol.KeepAliveChatEvent
import com.agnetix.harnax.agent.protocol.PendingCallTool
import com.agnetix.harnax.agent.protocol.StreamTextChatEvent
import com.agnetix.harnax.agent.protocol.ToolConfirmChatEvent
import com.agnetix.harnax.entity.TeamArtifact
import com.agnetix.harnax.entity.dto.AgentSpecInfoResponse
import com.agnetix.harnax.harness.HarnessAgentWrapper
import com.agnetix.harnax.harness.config.TeamConfig
import io.agentscope.core.event.ConfirmResult
import io.agentscope.core.message.Msg
import io.agentscope.core.message.ToolUseBlock
import io.agentscope.harness.agent.sandbox.ExecResult
import io.agentscope.harness.agent.sandbox.Sandbox
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import reactor.core.publisher.Flux
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Behaviour of one team run: who may be delegated to, what a human's answer decides, and what a member is
 * allowed to move in and out of the shared artifact set.
 *
 * The delegation budget and the artifact path checks are runtime limits rather than prompt instructions
 * (design 9.3, 8.3), so the cases below assert the refusal itself, not just a message that mentions it.
 */
class TeamOrchestratorTest {

    private val workspaceRoot = "/workspace"
    private val rootSession = "web-root"

    /** member agent id -> the wrapper the orchestrator's factory hands out, so tests can stub its turns. */
    private val memberWrappers = mutableMapOf<Long, HarnessAgentWrapper>()
    private val builtSessions = mutableListOf<Pair<Long, String>>()
    private val destroyed = mutableListOf<String>()
    private val released = mutableListOf<Long>()
    private val turnCounts = mutableMapOf<Long, AtomicInteger>()

    private fun member(
        id: Long = 2L,
        name: String = "Analyst",
        delegation: String = "",
    ): TeamMemberSpec = TeamMemberSpec(
        memberAgentId = id,
        agentName = name,
        description = "$name default description",
        delegationDescription = delegation,
        agentSpec = AgentSpec.builder().id(id).name(name).chatModelId(10L + id).build(),
        chatSpec = ChatSpecBuilder().build(),
        specInfo = AgentSpecInfoResponse(
            agentId = id,
            agentName = name,
            description = name,
            systemPrompt = "you are $name",
            modelId = 10L + id,
        ),
    )

    private fun newOrchestrator(
        members: List<TeamMemberSpec>,
        config: TeamConfig = TeamConfig(),
        gateway: TeamArtifactGateway? = null,
        sandbox: Sandbox? = null,
    ): TeamOrchestrator = TeamOrchestrator(
        spec = TeamRuntimeSpec(
            teamId = 7L,
            tenantId = 1L,
            teamName = "Research",
            rootSessionId = rootSession,
            leadAgentSpec = AgentSpec.builder().id(1L).name("Lead").chatModelId(100L).build(),
            leadChatSpec = ChatSpecBuilder().build(),
            leadSpecInfo = AgentSpecInfoResponse(
                agentId = 1L,
                agentName = "Lead",
                description = "the lead",
                systemPrompt = "coordinate",
                modelId = 100L,
            ),
            members = members,
        ),
        config = config,
        memberFactory = { spec, childSessionId, _ ->
            builtSessions += spec.memberAgentId to childSessionId
            wrapperFor(spec.memberAgentId)
        },
        artifactGateway = gateway,
        sandboxProvider = { sandbox },
        sandboxDestroyer = { destroyed += it },
        sandboxWorkspaceRoot = workspaceRoot,
    )

    private fun wrapperFor(memberAgentId: Long): HarnessAgentWrapper = memberWrappers.getOrPut(memberAgentId) {
        mock<HarnessAgentWrapper>().also { wrapper ->
            whenever(wrapper.release()).thenAnswer { released += memberAgentId }
        }
    }

    private fun stubPendingTools(memberAgentId: Long) {
        whenever(wrapperFor(memberAgentId).getPendingToolCalls()).thenReturn(listOf(mock<ToolUseBlock>()))
    }

    /** Every member turn replays [turns]. */
    private fun stubTurns(memberAgentId: Long, vararg turns: ChatEvent) {
        stubTurnFlows(memberAgentId, Flux.fromIterable(turns.toList()))
    }

    /** Member turn *n* replays `turns[n]`, and the last entry repeats once they run out. */
    private fun stubTurnFlows(memberAgentId: Long, vararg turns: Flux<ChatEvent>) {
        val counter = turnCounts.getOrPut(memberAgentId) { AtomicInteger() }
        whenever(wrapperFor(memberAgentId).callStream(msg = any())).thenAnswer {
            val index = counter.getAndIncrement()
            if (index < turns.size) turns[index] else turns.last()
        }
    }

    private fun text(message: String): ChatEvent = StreamTextChatEvent(message, isLast = true, tokenUsage = null)

    private fun confirmOf(vararg toolNames: String): ChatEvent = ToolConfirmChatEvent(
        pendingCallTools = toolNames.map { PendingCallTool("id-$it", it, emptyMap(), isDangerous = false) },
        tokenUsage = null,
    )

    private fun newExecutor(): ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "team-test-delegate").apply { isDaemon = true }
    }

    private fun <T> Future<T>.getDone(): T = get(30, TimeUnit.SECONDS)

    /**
     * Delegates on another thread because delegation blocks until the member reports back.
     *
     * [approved] null leaves a confirmation unanswered so the timeout path runs; otherwise every round the
     * member asks for is answered with that decision, which is also what bounds the repeat-asking case.
     */
    private fun delegateAndWait(
        orchestrator: TeamOrchestrator,
        memberAgentId: Long,
        approved: Boolean? = null,
        task: String = "do the thing",
    ): String {
        val executor = newExecutor()
        return try {
            val future = executor.submit<String> { orchestrator.delegate(memberAgentId, task) }
            if (approved != null) {
                val deadline = System.currentTimeMillis() + 20_000
                while (System.currentTimeMillis() < deadline && !future.isDone) {
                    orchestrator.pendingConfirmations()
                        .forEach { orchestrator.answerConfirmation(it.childRunId, approved) }
                    Thread.sleep(5)
                }
            }
            future.getDone()
        } finally {
            executor.shutdownNow()
        }
    }

    /** Blocks until some member run is waiting for a human, so an answer or a stop lands mid-wait. */
    private fun awaitPending(
        orchestrator: TeamOrchestrator,
        timeoutMs: Long = 5_000,
    ): List<TeamOrchestrator.PendingConfirmation> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            orchestrator.pendingConfirmations().takeIf { it.isNotEmpty() }?.let { return it }
            Thread.sleep(10)
        }
        return emptyList()
    }

    @Nested
    inner class Delegation {

        @Test
        fun `an unknown member is refused and the real roster is named back`() {
            val orchestrator = newOrchestrator(listOf(member(2L, "Analyst", "crunch numbers")))

            val report = orchestrator.delegate(999L, "do something")

            assertTrue(report.contains("没有找到 agentId=999"), report)
            assertTrue(report.contains("agentId=2 Analyst"), "roster should be recoverable from the refusal: $report")
        }

        @Test
        fun `a blank task costs no delegation`() {
            val orchestrator = newOrchestrator(listOf(member(2L, "Analyst")))

            assertTrue(orchestrator.delegate(2L, "   ").contains("缺少任务说明"))

            assertTrue(builtSessions.isEmpty(), "a refused task must not build a member agent")
        }

        @Test
        fun `a member is not built until it is delegated to, and is reused after that`() {
            val orchestrator = newOrchestrator(listOf(member(2L, "Analyst")))
            stubTurns(2L, text("working"))
            orchestrator.openEventStream()

            orchestrator.delegate(2L, "first")
            orchestrator.delegate(2L, "second")

            assertEquals(1, builtSessions.size, "the second delegation must reuse the cached member runtime")
            verify(wrapperFor(2L), times(2)).callStream(msg = any())
            assertTrue(builtSessions.all { it.second == "team-$rootSession-m2" }, "$builtSessions")
        }

        @Test
        fun `a second task is refused while the member still owns the first one`() {
            val orchestrator = newOrchestrator(listOf(member(2L, "Analyst")))
            val wrapper = wrapperFor(2L)
            val started = CountDownLatch(1)
            val gate = CountDownLatch(1)
            whenever(wrapper.callStream(msg = any())).thenAnswer {
                started.countDown()
                gate.await(5, TimeUnit.SECONDS)
                Flux.just(text("done"))
            }

            val executor = newExecutor()
            val first = executor.submit<String> { orchestrator.delegate(2L, "slow task") }
            assertTrue(started.await(5, TimeUnit.SECONDS), "first delegation never started")

            val second = orchestrator.delegate(2L, "another task")
            gate.countDown()
            val firstReport = first.getDone()
            executor.shutdownNow()

            assertTrue(second.contains("正在执行上一次委派"), second)
            assertTrue(firstReport.contains("done"), firstReport)
        }

        @Test
        fun `delegation stops at the budget instead of trusting the lead to stop asking`() {
            val orchestrator = newOrchestrator(
                members = listOf(member(2L, "Analyst")),
                config = TeamConfig(maxDelegations = 1, memberTurnTimeoutSeconds = 5),
            )
            stubTurns(2L, text("working"))
            orchestrator.openEventStream()

            orchestrator.delegate(2L, "first")
            val second = orchestrator.delegate(2L, "second")

            assertTrue(second.contains("已达到 1 次委派上限"), second)
            assertTrue(second.contains("任务没有执行"), "a spent budget must not read as a partial success: $second")
        }

        @Test
        fun `a member that errors comes back as a failure with whatever it finished`() {
            val orchestrator = newOrchestrator(listOf(member(2L, "Analyst")))
            stubTurns(2L, text("half an answer"), ErrorChatEvent(code = "E1", message = "model exploded"))

            val report = orchestrator.delegate(2L, "go")

            assertTrue(report.contains("未完成"), report)
            assertTrue(report.contains("model exploded"), report)
            assertTrue(report.contains("half an answer"), "partial work must survive the failure: $report")
        }

        @Test
        fun `a member that says nothing is reported as nothing rather than as done`() {
            val orchestrator = newOrchestrator(listOf(member(2L, "Analyst")))
            stubTurns(2L, EndEventChatEvent())

            assertTrue(orchestrator.delegate(2L, "go").contains("没有返回内容"))
        }

        @Test
        fun `a finished member run comes back with its text and the budget left`() {
            val orchestrator = newOrchestrator(listOf(member(2L, "Analyst")))
            stubTurns(2L, text("analysis done"))

            val report = orchestrator.delegate(2L, "go")

            assertTrue(report.contains("analysis done"), report)
            assertFalse(report.contains("未完成"), report)
            assertTrue(report.contains("已委派 1 次"), report)
        }

        @Test
        fun `a stopped orchestrator refuses work before touching a member`() {
            val orchestrator = newOrchestrator(listOf(member(2L, "Analyst")))
            orchestrator.stop(destroySandboxes = false)

            assertTrue(orchestrator.delegate(2L, "go").contains("团队已停止"))
            assertTrue(builtSessions.isEmpty())
        }
    }

    @Nested
    inner class EventStream {

        @Test
        fun `a second root call cannot take the stream while the first still owns it`() {
            val orchestrator = newOrchestrator(listOf(member(2L, "Analyst")))

            assertNotNull(orchestrator.openEventStream())
            assertNull(orchestrator.openEventStream(), "a member waiting on a confirmation keeps the first call open")

            orchestrator.closeEventStream()
            assertNotNull(orchestrator.openEventStream(), "closing must hand the stream to the next call")
        }

        @Test
        fun `member events carry their run and the end marker is left to the lead`() {
            val orchestrator = newOrchestrator(listOf(member(2L, "Analyst")))
            stubTurns(2L, text("working"), EndEventChatEvent())
            val received = mutableListOf<ChatEvent>()
            val subscription = requireNotNull(orchestrator.openEventStream()).subscribe { received += it }

            orchestrator.delegate(2L, "go")

            val forwarded = received.filterIsInstance<StreamTextChatEvent>()
            assertEquals(1, forwarded.size, "the member's EndEvent must not close the root stream")
            val source = requireNotNull(forwarded.single().source)
            assertEquals(7L, source.teamId)
            assertEquals(2L, source.memberAgentId)
            assertEquals("team-$rootSession-m2", source.childSessionId)
            assertNotNull(source.childRunId)
            subscription.dispose()
        }

        @Test
        fun `a member token reaches the user while the member is still working`() {
            val orchestrator = newOrchestrator(listOf(member(2L, "Analyst")))
            val received = mutableListOf<ChatEvent>()
            val subscription = requireNotNull(orchestrator.openEventStream()).subscribe { received += it }
            stubTurnFlows(
                2L,
                Flux.just(text("first")).concatWith(
                    Flux.defer {
                        if (received.filterIsInstance<StreamTextChatEvent>().isEmpty()) {
                            Flux.error(AssertionError("the root stream was still empty while the member was running"))
                        } else {
                            Flux.just(text("second"))
                        }
                    },
                ),
            )

            orchestrator.delegate(2L, "go")

            assertEquals(listOf("first", "second"), received.filterIsInstance<StreamTextChatEvent>().map { it.message })
            subscription.dispose()
        }
    }

    @Nested
    inner class Confirmation {

        @Test
        fun `an approval resumes the same run and lets the member finish`() {
            val orchestrator = newOrchestrator(listOf(member(2L, "Analyst")))
            val resumed = mutableListOf<Msg>()
            val wrapper = wrapperFor(2L)
            whenever(wrapper.callStream(msg = any())).thenAnswer {
                resumed += it.getArgument<Msg>(0)
                if (resumed.size == 1) Flux.just(confirmOf("write_file")) else Flux.just(text("wrote it"))
            }
            stubPendingTools(2L)

            val report = delegateAndWait(orchestrator, 2L, approved = true)

            assertEquals(2, resumed.size, "the member must be continued, not restarted")
            val results = confirmResultsOf(resumed.last())
            assertEquals(1, results.size)
            assertTrue(results.single().isConfirmed)
            assertTrue(report.contains("wrote it"), report)
        }

        @Test
        fun `a denial reaches the member as a denial, not as silence`() {
            val orchestrator = newOrchestrator(listOf(member(2L, "Analyst")))
            val resumed = mutableListOf<Msg>()
            val wrapper = wrapperFor(2L)
            whenever(wrapper.callStream(msg = any())).thenAnswer {
                resumed += it.getArgument<Msg>(0)
                if (resumed.size == 1) Flux.just(confirmOf("rm_file")) else Flux.just(text("understood"))
            }
            stubPendingTools(2L)

            delegateAndWait(orchestrator, 2L, approved = false)

            assertEquals(2, resumed.size, "a denial still has to get back to the member")
            assertFalse(confirmResultsOf(resumed.last()).single().isConfirmed, "the tool must stay unexecuted")
        }

        @Test
        fun `an unanswered confirmation fails the task rather than approving it`() {
            val orchestrator = newOrchestrator(
                members = listOf(member(2L, "Analyst")),
                config = TeamConfig(confirmTimeoutSeconds = 1, memberTurnTimeoutSeconds = 5),
            )
            stubTurns(2L, confirmOf("write_file"))
            stubPendingTools(2L)

            val report = delegateAndWait(orchestrator, 2L, approved = null)

            assertTrue(report.contains("未完成"), report)
            assertTrue(report.contains("工具没有执行"), "a timeout must never read as consent: $report")
            verify(wrapperFor(2L), times(1)).callStream(msg = any())
        }

        @Test
        fun `a wait for confirmation keeps the root stream alive`() {
            val orchestrator = newOrchestrator(
                members = listOf(member(2L, "Analyst")),
                config = TeamConfig(
                    confirmTimeoutSeconds = 30,
                    confirmHeartbeatSeconds = 1,
                    memberTurnTimeoutSeconds = 5,
                ),
            )
            stubTurnFlows(2L, Flux.just(confirmOf("write_file")), Flux.just(text("wrote it")))
            stubPendingTools(2L)
            val emitted = CopyOnWriteArrayList<ChatEvent>()
            val executor = newExecutor()

            try {
                orchestrator.openEventStream()?.subscribe { emitted += it }
                val future = executor.submit<String> { orchestrator.delegate(2L, "go") }
                val runId = awaitPending(orchestrator).single().childRunId
                Thread.sleep(2_500)

                val waiting = emitted.filterIsInstance<KeepAliveChatEvent>()
                assertTrue(
                    waiting.isNotEmpty(),
                    "session-router kills a stream after 120s of silence, so the wait must not be silent: $emitted",
                )
                assertEquals(runId, waiting.first().source?.childRunId, "the keep-alive says which run is waiting")

                assertEquals(ConfirmationOutcome.APPROVED, orchestrator.answerConfirmation(runId, true))
                assertTrue(future.getDone().contains("wrote it"))
                val answered = emitted.count { it is KeepAliveChatEvent }
                Thread.sleep(1_500)
                assertEquals(answered, emitted.count { it is KeepAliveChatEvent }, "a finished wait stops beating")
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `a member that keeps asking is cut off instead of looping forever`() {
            val orchestrator = newOrchestrator(listOf(member(2L, "Analyst")))
            stubTurns(2L, confirmOf("write_file"))
            stubPendingTools(2L)

            val report = delegateAndWait(orchestrator, 2L, approved = true)

            assertTrue(report.contains("反复请求确认"), report)
            assertEquals(6, turnCounts.getValue(2L).get(), "one turn per round plus the refusal: ${turnCounts.getValue(2L).get()}")
        }

        @Test
        fun `an answer for a run outside this team is refused`() {
            val orchestrator = newOrchestrator(listOf(member(2L, "Analyst")))

            assertEquals(
                ConfirmationOutcome.NOT_IN_THIS_TEAM,
                orchestrator.answerConfirmation("someone-else's-run", true),
            )
        }

        @Test
        fun `answering the same run twice decides it once`() {
            val orchestrator = newOrchestrator(listOf(member(2L, "Analyst")))
            stubTurnFlows(2L, Flux.just(confirmOf("write_file")), Flux.just(text("wrote it")))
            stubPendingTools(2L)
            val executor = newExecutor()

            try {
                val future = executor.submit<String> { orchestrator.delegate(2L, "go") }
                val runId = awaitPending(orchestrator).single().childRunId
                assertEquals(ConfirmationOutcome.APPROVED, orchestrator.answerConfirmation(runId, true))
                // Wait the run out first: a second answer must not be able to decide a *later* round.
                assertTrue(future.getDone().contains("wrote it"))
                assertEquals(ConfirmationOutcome.ALREADY_ANSWERED, orchestrator.answerConfirmation(runId, true))
            } finally {
                executor.shutdownNow()
            }
            verify(wrapperFor(2L), times(2)).callStream(msg = any())
        }

        @Test
        fun `stopping releases a waiting member without approving it`() {
            val orchestrator = newOrchestrator(listOf(member(2L, "Analyst")))
            stubTurns(2L, confirmOf("write_file"))
            stubPendingTools(2L)
            val executor = newExecutor()

            try {
                val future = executor.submit<String> { orchestrator.delegate(2L, "go") }
                val runId = awaitPending(orchestrator).single().childRunId
                orchestrator.stop(destroySandboxes = false)

                val report = future.getDone()
                assertTrue(report.contains("未完成"), report)
                assertTrue(report.contains("工具没有执行"), "a stop is not a denial the member gets to retry: $report")
                assertEquals(ConfirmationOutcome.STOPPED, orchestrator.answerConfirmation(runId, true))
                verify(wrapperFor(2L), times(1)).callStream(msg = any())
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Nested
    inner class Lifecycle {

        @Test
        fun `stopping destroys member sandboxes only when the caller says so`() {
            val orchestrator = newOrchestrator(listOf(member(2L, "Analyst")))
            stubTurns(2L, text("working"))
            orchestrator.delegate(2L, "go")

            orchestrator.stop(destroySandboxes = false)
            assertTrue(destroyed.isEmpty(), "a plain stop keeps the sandbox for the next turn")

            orchestrator.stop(destroySandboxes = true)
            assertEquals(listOf("team-$rootSession-m2"), destroyed)
        }

        @Test
        fun `releasing lets go of every member agent the run built`() {
            val orchestrator = newOrchestrator(listOf(member(2L, "Analyst"), member(3L, "Writer")))
            stubTurns(2L, text("a"))
            stubTurns(3L, text("b"))
            orchestrator.delegate(2L, "go")
            orchestrator.delegate(3L, "go")

            orchestrator.releaseAll()

            assertEquals(listOf(2L, 3L), released.sorted())
            assertTrue(orchestrator.delegate(2L, "again").contains("团队已停止"))
        }

        @Test
        fun `each member's child session is keyed by the root session and its own agent id`() {
            assertEquals(
                listOf("team-$rootSession-m2", "team-$rootSession-m3"),
                listOf(2L, 3L).map { TeamSessions.childSessionId(rootSession, it) },
            )
        }

        @Test
        fun `a child session id stays free of slashes because it keys a container and a path`() {
            assertTrue(listOf(2L, 3L).none { TeamSessions.childSessionId(rootSession, it).contains('/') })
        }

        @Test
        fun `a failed run evicts the member runtime that cannot be trusted any more`() {
            val orchestrator = newOrchestrator(listOf(member(2L, "Analyst")))
            whenever(wrapperFor(2L).callStream(msg = any())).thenThrow(RuntimeException("agent poisoned"))

            val report = orchestrator.delegate(2L, "go")

            assertTrue(report.contains("执行失败"), report)
            assertTrue(released.contains(2L), "the poisoned member agent must be released")
        }

        /**
         * A browser that closes ends the root stream without the user stopping the team, and the delegation
         * thread stays parked on its confirmation holding the member's agent. The next root call must not
         * drive a second turn through that agent, while a member whose run finished keeps the agent it is
         * meant to reuse.
         */
        @Test
        fun `an abandoned run releases the member agent it still holds`() {
            val orchestrator = newOrchestrator(listOf(member(2L, "Analyst"), member(3L, "Writer")))
            stubTurnFlows(2L, Flux.just(confirmOf("write_file")), Flux.just(text("reported")))
            stubPendingTools(2L)
            stubTurns(3L, text("polished"))
            val executor = newExecutor()
            try {
                val stream = requireNotNull(orchestrator.openEventStream()).subscribe()
                delegateAndWait(orchestrator, 3L)
                val parked = executor.submit<String> { orchestrator.delegate(2L, "go") }
                assertTrue(awaitPending(orchestrator).isNotEmpty(), "the member never reached its confirmation")

                stream.dispose()
                orchestrator.closeEventStream()
                orchestrator.openEventStream()

                assertEquals(listOf(2L), released, "only the member the abandoned run still holds is released")
                val abandoned = parked.getDone()
                assertTrue(abandoned.contains("未完成"), "a cancelled wait is a failure, never an approval: $abandoned")

                delegateAndWait(orchestrator, 2L)
                assertEquals(2, builtSessions.count { it.first == 2L }, "the next delegation builds a fresh agent")
                assertEquals(1, builtSessions.count { it.first == 3L }, "a finished member keeps its agent")
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Nested
    inner class ArtifactBoundaries {

        private fun run(): TeamChildRun = TeamChildRun(
            childRunId = "run-1",
            member = member(2L, "Analyst"),
            childSessionId = "team-$rootSession-m2",
            source = EventSource(7L, "Research", 2L, "Analyst", "run-1", "team-$rootSession-m2"),
        )

        private fun artifact(
            fileId: String = "f-1",
            size: Long = 128L,
        ): TeamArtifact = TeamArtifact().apply {
            this.fileId = fileId
            tenantId = 1L
            sessionId = rootSession
            teamId = 7L
            memberAgentId = 2L
            childSessionId = "team-$rootSession-m2"
            fileName = "report.csv"
            mimeType = "text/csv"
            sizeBytes = size
            objectKey = "team-artifacts/1/$rootSession/$fileId"
        }

        /** A sandbox whose size probe answers [size] and whose base64 read answers [base64]. */
        private fun sandboxAt(
            size: String,
            base64: String = "",
        ): Sandbox {
            val sandbox = mock<Sandbox>()
            whenever(sandbox.exec(anyOrNull(), any(), any())).thenAnswer { invocation ->
                val command = invocation.getArgument<String>(1)
                val out = when {
                    command.contains("stat -c") -> size
                    command.startsWith("base64 ") -> base64
                    else -> ""
                }
                ExecResult(0, out, "", false)
            }
            return sandbox
        }

        @Test
        fun `publish refuses a path that leaves the member workspace`() {
            val orchestrator = newOrchestrator(
                members = listOf(member()),
                gateway = mock<TeamArtifactGateway>(),
                sandbox = sandboxAt("128"),
            )

            val refusal = orchestrator.publishArtifact(run(), "../etc/passwd")

            assertTrue(refusal.contains("只能发布自己工作区内的文件"), refusal)
            assertTrue(refusal.contains(".."))
        }

        @Test
        fun `publish refuses a name that would break out of the sandbox command`() {
            val gateway = mock<TeamArtifactGateway>()
            val sandbox = sandboxAt("128")
            val orchestrator = newOrchestrator(
                members = listOf(member()),
                gateway = gateway,
                sandbox = sandbox,
            )

            val refusal = orchestrator.publishArtifact(run(), "out/';touch /tmp/pwn;'.csv")

            assertTrue(refusal.contains("只能发布自己工作区内的文件"), refusal)
            verify(sandbox, never()).exec(anyOrNull(), any(), any())
            verify(gateway, never()).publish(any(), any(), any(), any(), any(), any(), any(), any())
        }

        @Test
        fun `publish accepts an ordinary name with spaces and non-ascii characters`() {
            val publishedNames = mutableListOf<String>()
            val gateway = mock<TeamArtifactGateway>()
            whenever(gateway.publish(any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer { invocation ->
                publishedNames += invocation.getArgument<String>(5)
                artifact()
            }
            val orchestrator = newOrchestrator(
                members = listOf(member()),
                gateway = gateway,
                sandbox = sandboxAt("128", "eA=="),
            )

            val report = orchestrator.publishArtifact(run(), "out/月度 报告.csv")

            assertTrue(report.contains("已发布"), report)
            assertEquals(listOf("月度 报告.csv"), publishedNames)
        }

        @Test
        fun `publish refuses an over-cap file before reading it`() {
            val gateway = mock<TeamArtifactGateway>()
            val orchestrator = newOrchestrator(
                members = listOf(member()),
                config = TeamConfig(maxArtifactBytes = 64),
                gateway = gateway,
                sandbox = sandboxAt("128"),
            )

            val refusal = orchestrator.publishArtifact(run(), "out/report.csv")

            assertTrue(refusal.contains("超过产物大小上限"), refusal)
            verify(gateway, never()).publish(any(), any(), any(), any(), any(), any(), any(), any())
        }

        @Test
        fun `publish says storage is off instead of falling back to a public link`() {
            val orchestrator = newOrchestrator(listOf(member()), sandbox = sandboxAt("128"))

            val refusal = orchestrator.publishArtifact(run(), "out/report.csv")

            assertTrue(refusal.contains("不要用公共链接"), "the model must not be handed an unguarded channel: $refusal")
        }

        @Test
        fun `publish without a live sandbox does not pretend the file is gone`() {
            val orchestrator = newOrchestrator(listOf(member()), gateway = mock<TeamArtifactGateway>(), sandbox = null)

            val refusal = orchestrator.publishArtifact(run(), "out/report.csv")

            assertTrue(refusal.contains("没有运行中的沙箱"), refusal)
        }

        @Test
        fun `publish records the file against the run that made it, not its session`() {
            val gateway = mock<TeamArtifactGateway>()
            whenever(gateway.publish(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(artifact())
            val orchestrator = newOrchestrator(
                members = listOf(member()),
                gateway = gateway,
                sandbox = sandboxAt("128", "eA=="),
            )
            val run = run()

            val report = orchestrator.publishArtifact(run, "out/report.csv")

            assertTrue(report.contains("fileId=f-1"), report)
            assertEquals(listOf("f-1"), run.publishedArtifacts.map { it.fileId })
        }

        @Test
        fun `fetch refuses a fileId the gateway does not own for this tenant and session`() {
            val gateway = mock<TeamArtifactGateway>()
            val orchestrator = newOrchestrator(listOf(member()), gateway = gateway, sandbox = sandboxAt("128"))

            val refusal = orchestrator.fetchArtifact(run(), "f-1", "in/report.csv")

            assertTrue(refusal.contains("找不到属于本会话的产物"), refusal)
            verify(gateway).findOwned("f-1", 1L, rootSession)
            verify(gateway, never()).read(any())
        }

        @Test
        fun `fetch refuses a write path that escapes the member workspace`() {
            val gateway = mock<TeamArtifactGateway>()
            whenever(gateway.findOwned(any(), any(), any())).thenReturn(artifact())
            val orchestrator = newOrchestrator(listOf(member()), gateway = gateway, sandbox = sandboxAt("128"))

            val refusal = orchestrator.fetchArtifact(run(), "f-1", "../../etc/cron.d/evil")

            assertTrue(refusal.contains("只能写入自己工作区内的路径"), refusal)
            verify(gateway, never()).read(any())
        }

        @Test
        fun `fetch writes the bytes it read into the resolved workspace path`() {
            val gateway = mock<TeamArtifactGateway>()
            whenever(gateway.findOwned(any(), any(), any())).thenReturn(artifact())
            whenever(gateway.read(any())).thenReturn("hi".toByteArray())
            val sandbox = mock<Sandbox>()
            val writes = mutableListOf<String>()
            whenever(sandbox.exec(anyOrNull(), any(), any())).thenAnswer { invocation ->
                val command = invocation.getArgument<String>(1)
                if (command.startsWith("base64 -d")) writes += command
                ExecResult(0, "", "", false)
            }
            val orchestrator = newOrchestrator(listOf(member()), gateway = gateway, sandbox = sandbox)

            val report = orchestrator.fetchArtifact(run(), "f-1", "in/report.csv")

            assertTrue(report.contains("已获取"), report)
            assertEquals(1, writes.size, "$writes")
            assertTrue(writes.single().contains("$workspaceRoot/in/report.csv"), writes.toString())
        }

        @Test
        fun `a failing artifact listing is told to the model as a failure`() {
            val gateway = mock<TeamArtifactGateway>()
            whenever(gateway.list(any())).thenThrow(RuntimeException("minio down"))
            val orchestrator = newOrchestrator(listOf(member()), gateway = gateway)

            val listing = orchestrator.describeArtifacts()

            assertTrue(listing.contains("读取产物列表失败"), listing)
            assertFalse(listing.isBlank(), "an empty answer reads to the model as 'nobody produced a file'")
        }

        @Test
        fun `listing artifacts without storage says so rather than returning nothing`() {
            val orchestrator = newOrchestrator(listOf(member()), gateway = null)

            assertTrue(orchestrator.describeArtifacts().contains("产物存储未启用"))
        }
    }

    @Nested
    inner class Roster {

        @Test
        fun `the roster falls back to the agent description when no delegation note exists`() {
            val orchestrator = newOrchestrator(
                listOf(member(2L, "Analyst", delegation = "numbers only"), member(3L, "Writer")),
            )

            val roster = orchestrator.describeMembers()

            assertTrue(roster.contains("agentId=2 Analyst：numbers only"), roster)
            assertTrue(roster.contains("agentId=3 Writer：Writer default description"), roster)
        }
    }

    private fun confirmResultsOf(msg: Msg): List<ConfirmResult> {
        val results = msg.getMetadata()?.get(Msg.METADATA_CONFIRM_RESULTS)
        @Suppress("UNCHECKED_CAST")
        return (results as? List<ConfirmResult>).orEmpty()
    }
}

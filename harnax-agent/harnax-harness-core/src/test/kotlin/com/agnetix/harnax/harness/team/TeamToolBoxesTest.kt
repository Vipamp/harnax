package com.agnetix.harnax.harness.team

import com.agnetix.harnax.tools.sdk.SessionMetaContext
import com.agnetix.harnax.tools.sdk.ToolBox
import com.agnetix.harnax.tools.sdk.UserIdentifier
import com.agnetix.harnax.tools.sdk.adaptor.ToolCallInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * What the lead's and a member's own tools accept, and what they refuse before the orchestrator is reached.
 *
 * These are the seams where a model's argument becomes a delegation, so the cases below care about two
 * things: a bad reference must come back as text the model can act on rather than as an exception that
 * ends the run, and nothing may reach the orchestrator until the arguments are usable.
 */
class TeamToolBoxesTest {

    private val orchestrator = mock<TeamOrchestrator>()
    private val delegation = mock<TeamChildRun>()
    private val leadCalls = mutableListOf<ToolCallInfo>()
    private val memberCalls = mutableListOf<ToolCallInfo>()

    private val lead = TeamLeadToolBox(orchestrator).wiredInto(leadCalls)
    private val member = TeamMemberToolBox(orchestrator, memberAgentId = 2L).wiredInto(memberCalls)

    /** The framework initializes every toolbox it registers; a refusal has to be logged like any result. */
    private fun <T : ToolBox> T.wiredInto(emitted: MutableList<ToolCallInfo>): T = also {
        init(
            { emitted += it },
            SessionMetaContext(agentId = 1L, sessionId = "web-root"),
            UserIdentifier(userId = 9L),
        )
    }

    @Nested
    inner class Lead {

        @Test
        fun `an empty roster is stated instead of coming back blank`() {
            whenever(orchestrator.describeMembers()).thenReturn("")

            assertEquals("这个团队没有成员。", lead.teamMembers())
        }

        @Test
        fun `the roster is handed to the lead as it is`() {
            whenever(orchestrator.describeMembers()).thenReturn("- agentId=2 Analyst：numbers")

            assertTrue(lead.teamMembers().contains("agentId=2 Analyst"))
        }

        @Test
        fun `a member id that is not one of the roster's numbers is refused without delegating`() {
            val refusal = lead.teamDelegate("Analyst", "analyze", null)

            assertTrue(refusal.contains("必须是 team_members 列出的数字 id"), refusal)
            assertTrue(refusal.contains("'Analyst'"), "the model has to see what it sent: $refusal")
            verify(orchestrator, never()).delegate(any(), any(), any())
        }

        @Test
        fun `a missing member id is refused the same way`() {
            assertTrue(lead.teamDelegate(null, "analyze", null).contains("member_agent_id"))

            verify(orchestrator, never()).delegate(any(), any(), any())
        }

        @Test
        fun `a delegation is passed on with the task as written`() {
            whenever(orchestrator.delegate(2L, "analyze this", emptyList())).thenReturn("done")

            assertEquals("done", lead.teamDelegate("2", "analyze this", null))
        }

        @Test
        fun `file references survive commas spaces and newlines`() {
            whenever(orchestrator.delegate(any(), any(), any())).thenReturn("ok")

            lead.teamDelegate(" 2 ", "analyze", "f-1, f-2\nf-3 ,")

            verify(orchestrator).delegate(eq(2L), eq("analyze"), eq(listOf("f-1", "f-2", "f-3")))
        }

        @Test
        fun `no file references means an empty list, not a list with one empty string`() {
            whenever(orchestrator.delegate(any(), any(), any())).thenReturn("ok")

            lead.teamDelegate("2", "analyze", "  ")

            verify(orchestrator).delegate(eq(2L), eq("analyze"), eq(emptyList()))
        }

        @Test
        fun `the artifact listing the lead sees is the one the run produced`() {
            whenever(orchestrator.describeArtifacts()).thenReturn("- fileId=f-1 report.csv")

            assertTrue(lead.teamArtifacts().contains("fileId=f-1"))
        }

        @Test
        fun `a refusal is logged as a tool call like any other result`() {
            lead.teamDelegate("not-an-id", "analyze", null)

            // The log key is the Kotlin method, as ToolBox takes it off the stack; the model sees it as team_delegate.
            assertEquals(listOf("team-lead-tool-box::teamDelegate"), leadCalls.map { it.toolName })
            assertTrue(leadCalls.single().success)
        }
    }

    @Nested
    inner class MemberTools {

        @Test
        fun `publishing outside a delegated task is refused and stores nothing`() {
            whenever(orchestrator.currentRunOf(2L)).thenReturn(null)

            val refusal = member.teamArtifactPublish("out/report.csv")

            assertTrue(refusal.contains("不在任何委派任务中"), refusal)
            verify(orchestrator, never()).publishArtifact(any(), any())
        }

        @Test
        fun `fetching outside a delegated task is refused too`() {
            whenever(orchestrator.currentRunOf(2L)).thenReturn(null)

            assertTrue(member.teamArtifactFetch("f-1", "in/report.csv").contains("不在任何委派任务中"))
            verify(orchestrator, never()).fetchArtifact(any(), any(), any())
        }

        @Test
        fun `a member publishes into the run it is executing, never one it names`() {
            whenever(orchestrator.currentRunOf(2L)).thenReturn(delegation)
            whenever(orchestrator.publishArtifact(delegation, "out/report.csv")).thenReturn("fileId=f-1")

            assertEquals("fileId=f-1", member.teamArtifactPublish("out/report.csv"))
        }

        @Test
        fun `a blank path still reaches the orchestrator's own check`() {
            whenever(orchestrator.currentRunOf(2L)).thenReturn(delegation)
            whenever(orchestrator.publishArtifact(delegation, "")).thenReturn("只能发布自己工作区内的文件")

            assertTrue(member.teamArtifactPublish(null).contains("只能发布自己工作区内的文件"))
        }

        @Test
        fun `a fetched reference is trimmed before it is resolved`() {
            whenever(orchestrator.currentRunOf(2L)).thenReturn(delegation)
            whenever(orchestrator.fetchArtifact(delegation, "f-1", "in/report.csv")).thenReturn("已获取")

            assertTrue(member.teamArtifactFetch(" f-1 ", "in/report.csv").contains("已获取"))
        }

        @Test
        fun `a member can list the team's artifacts without being in a run`() {
            whenever(orchestrator.describeArtifacts()).thenReturn("- fileId=f-1 report.csv")

            assertTrue(member.teamArtifacts().contains("fileId=f-1"))
        }
    }

    @Nested
    inner class Assembly {

        @Test
        fun `the names the framework allow-lists are the names on the tools`() {
            assertEquals(setOf("team_members", "team_delegate", "team_artifacts"), TeamLeadToolBox.TOOL_NAMES)
            assertEquals(
                setOf("team_artifact_publish", "team_artifact_fetch", "team_artifacts"),
                TeamMemberToolBox.TOOL_NAMES,
            )
        }
    }
}

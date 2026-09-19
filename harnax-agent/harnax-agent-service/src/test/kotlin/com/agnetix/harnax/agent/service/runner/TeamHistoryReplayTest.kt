package com.agnetix.harnax.agent.service.runner

import com.agnetix.harnax.agent.chat.AssistantMessageLog
import com.agnetix.harnax.agent.chat.Role
import com.agnetix.harnax.agent.chat.ToolResultMessageLog
import com.agnetix.harnax.agent.chat.ToolUseLog
import com.agnetix.harnax.agent.chat.UserMessageLog
import com.agnetix.harnax.agent.protocol.EventSource
import com.agnetix.harnax.agent.service.client.AdminApiClient
import com.agnetix.harnax.entity.dto.AgentSpecInfoResponse
import com.agnetix.harnax.entity.dto.TeamMemberSpecDto
import com.agnetix.harnax.entity.dto.TeamSpecInfoResponse
import com.agnetix.harnax.harness.HarnessAgentLauncher
import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * Team history replay: who spoke has to survive a reload, and it must not reshuffle the lead's own turns.
 */
class TeamHistoryReplayTest {

    private lateinit var launcher: HarnessAgentLauncher
    private lateinit var adminApiClient: AdminApiClient
    private lateinit var replay: TeamHistoryReplay

    private val root = "web-root-1"
    private val child = "team-web-root-1-m11"

    @BeforeEach
    fun setUp() {
        launcher = mock(HarnessAgentLauncher::class.java)
        adminApiClient = mock(AdminApiClient::class.java)
        replay = TeamHistoryReplay(launcher, adminApiClient)
    }

    // ─── merge ───

    @Test
    fun `an ordinary session never asks admin for a roster`() {
        `when`(launcher.memberSessionIds(root)).thenReturn(emptyList())
        val lead = listOf(assistant("hi", 10))

        assertEquals(lead, replay.merge(root, lead))
        verify(adminApiClient, never()).getTeamSpec(root)
    }

    @Test
    fun `a member conversation comes back stamped with who spoke`() {
        stubRoster()
        `when`(launcher.loadSessionMessages(child)).thenReturn(listOf(assistantMsg("classified 4 errors")))

        val merged = replay.merge(root, listOf(user("分析日志", 10), assistant("交给日志专家", 20)))

        val replayed = merged.filter { it.source != null }
        assertEquals(1, replayed.size)
        val source = replayed[0].source!!
        assertEquals(7L, source.teamId)
        assertEquals("数据分析团队", source.teamName)
        assertEquals(11L, source.memberAgentId)
        assertEquals("日志专家", source.memberAgentName)
        // Nothing persisted a run id, so the child session is what groups one replayed bubble.
        assertEquals(child, source.childRunId)
        assertEquals(child, source.childSessionId)
    }

    @Test
    fun `the task brief a member was given stays out of the timeline`() {
        stubRoster()
        // A child session opens with the brief the lead handed over; live, the member bubble never shows it.
        `when`(launcher.loadSessionMessages(child)).thenReturn(
            listOf(
                Msg.builder().name("user").role(MsgRole.USER).textContent("【团队】数据分析团队").build(),
                assistantMsg("done"),
            ),
        )

        val merged = replay.merge(root, listOf(user("分析日志", 10)))

        assertTrue(merged.any { it.source != null })
        assertTrue(merged.filter { it.source != null }.none { it.role == Role.USER })
    }

    @Test
    fun `history still loads when the team roster can no longer be read`() {
        `when`(launcher.memberSessionIds(root)).thenReturn(listOf(child))
        `when`(adminApiClient.getTeamSpec(root)).thenThrow(RuntimeException("admin down"))
        val lead = listOf(assistant("hi", 10))

        assertEquals(lead, replay.merge(root, lead))
    }

    // ─── interleave ───

    @Test
    fun `member logs surface after the lead turn that delegated them`() {
        val lead = listOf(
            user("分析日志", 100),
            assistant("先委派", 200).withDelegate(),
            toolResult("team_delegate", 500),
            assistant("汇总完成", 600),
        )
        val member = listOf(assistant("归类结果", 300).copy(source = source()))

        val merged = replay.interleave(lead, member)

        assertEquals(listOf(100L, 200L, 500L, 300L, 600L), merged.map { it.timestamp })
        assertEquals(listOf(null, null, null, "日志专家", null), merged.map { it.source?.memberAgentName })
    }

    @Test
    fun `an assistant message is never split from its own tool result`() {
        val lead = listOf(assistant("先委派", 200).withDelegate(), toolResult("team_delegate", 900))
        // Both member logs land inside the window of that one lead turn.
        val member = listOf(
            assistant("第一步", 300).copy(source = source()),
            assistant("第二步", 800).copy(source = source()),
        )

        val merged = replay.interleave(lead, member)

        val assistantIndex = merged.indexOfFirst { it.role == Role.ASSISTANT && it.source == null }
        assertEquals(assistantIndex + 1, merged.indexOfFirst { it.role == Role.TOOL })
        assertEquals(200L, merged[assistantIndex].timestamp)
    }

    @Test
    fun `a member run outliving the lead last message still shows up`() {
        val lead = listOf(assistant("委派了但没汇总", 200))
        val member = listOf(assistant("还在跑", 900).copy(source = source()))

        val merged = replay.interleave(lead, member)

        assertEquals(listOf(200L, 900L), merged.map { it.timestamp })
        assertEquals("日志专家", merged.last().source?.memberAgentName)
    }

    @Test
    fun `no member logs leaves the lead history as it was`() {
        val lead = listOf(user("q", 100), assistant("a", 200))

        assertEquals(lead, replay.interleave(lead, emptyList()))
    }

    @Test
    fun `concurrent members never split a tool call from its own result`() {
        val lead = listOf(
            assistant("委派给两个成员", 200).withDelegate(),
            toolResult("team_delegate", 2000),
            assistant("汇总完成", 3000),
        )
        // Member A's tool result lands after member B started, which a flat timestamp sort would sever.
        val member = listOf(
            assistant("A 调用工具", 300).copy(source = source()),
            memberToolResult("A 的工具结果", 700),
            assistant("B 说话", 500).copy(source = source()),
        )

        val merged = replay.interleave(lead, member)

        val aCall = merged.indexOfFirst { it.role == Role.ASSISTANT && (it as AssistantMessageLog).text == "A 调用工具" }
        val aResult = merged.indexOfFirst { it.role == Role.TOOL && (it as ToolResultMessageLog).result == "A 的工具结果" }
        assertEquals(aCall + 1, aResult)
        assertEquals("B 说话", (merged[aResult + 1] as AssistantMessageLog).text)
    }

    @Test
    fun `member logs are ordered by time even if one session was read last`() {
        val member = listOf(
            assistant("晚", 800).copy(source = source()),
            assistant("早", 300).copy(source = source()),
        )

        val merged = replay.interleave(listOf(assistant("lead", 200)), member)

        assertEquals(listOf(200L, 300L, 800L), merged.map { it.timestamp })
    }

    // ─── fixtures ───

    private fun stubRoster() {
        `when`(launcher.memberSessionIds(root)).thenReturn(listOf(child))
        `when`(launcher.memberSessionId(root, 11L)).thenReturn(child)
        `when`(adminApiClient.getTeamSpec(root)).thenReturn(teamSpec())
    }

    private fun teamSpec() = TeamSpecInfoResponse(
        teamId = 7L,
        tenantId = 1L,
        teamName = "数据分析团队",
        instructions = "",
        lead = specInfo(3L, "主管"),
        members = listOf(
            TeamMemberSpecDto(
                memberAgentId = 11L,
                agentName = "日志专家",
                delegationDescription = "负责日志归类",
                spec = specInfo(11L, "日志专家"),
            ),
        ),
    )

    private fun specInfo(id: Long, name: String) = AgentSpecInfoResponse(
        agentId = id,
        agentName = name,
        description = "",
        systemPrompt = "",
        modelId = 1L,
    )

    private fun source() = EventSource(
        teamId = 7L,
        teamName = "数据分析团队",
        memberAgentId = 11L,
        memberAgentName = "日志专家",
        childRunId = child,
        childSessionId = child,
    )

    private fun user(
        text: String,
        ts: Long,
    ) = UserMessageLog(text, ts)

    private fun assistant(
        text: String,
        ts: Long,
    ) = AssistantMessageLog("", text, emptyList(), ts)

    private fun AssistantMessageLog.withDelegate() = copy(toolUseLog = listOf(com.agnetix.harnax.agent.chat.ToolUseLog("team_delegate", mapOf("member_agent_id" to "11"))))

    private fun toolResult(
        name: String,
        ts: Long,
    ) = ToolResultMessageLog(name, "成员「日志专家」的结果", ts)

    private fun memberToolResult(
        result: String,
        ts: Long,
    ) = ToolResultMessageLog("shell", result, ts, source())

    private fun assistantMsg(text: String): Msg = Msg.builder().name("assistant").role(MsgRole.ASSISTANT).textContent(text).build()
}

package com.agnetix.harnax.harness.team

import com.agnetix.harnax.agent.AgentSpec
import com.agnetix.harnax.agent.ChatSpec
import com.agnetix.harnax.entity.dto.AgentSpecInfoResponse

/**
 * A configured team member, already resolved to the full runtime specification it runs with.
 *
 * Admin delivers the whole configuration (model, tools, MCP, skills, CLI) rather than a set of ids, so
 * the member does not depend on any of the lead's adaptors to interpret its own bindings (design D5).
 *
 * @param specInfo The admin response [agentSpec] and [chatSpec] were built from. The adaptors that turn
 *   those ids into a model client, MCP config, tool config and skill content read it per build, so a
 *   member assembled later needs its own copy rather than the lead's.
 */
data class TeamMemberSpec(
    val memberAgentId: Long,
    val agentName: String,
    val description: String,
    val delegationDescription: String,
    val agentSpec: AgentSpec,
    val chatSpec: ChatSpec,
    val specInfo: AgentSpecInfoResponse,
)

/**
 * The trusted team configuration behind one lead agent instance.
 *
 * Everything here came from admin for the root session that is already authorized, and nothing here is
 * reachable by a model: a member can only be named by [members] id, and the storage scope is fixed by
 * [tenantId] plus [rootSessionId] (design sections 6.2 and 8.3).
 */
data class TeamRuntimeSpec(
    val teamId: Long,
    val tenantId: Long,
    val teamName: String,
    val rootSessionId: String,
    val leadAgentSpec: AgentSpec,
    val leadChatSpec: ChatSpec,
    /** What the lead's own adaptors read from the spec context while the lead is being built. */
    val leadSpecInfo: AgentSpecInfoResponse,
    val members: List<TeamMemberSpec>,
) {
    fun memberOf(memberAgentId: Long): TeamMemberSpec? = members.firstOrNull { it.memberAgentId == memberAgentId }
}

/**
 * The session key namespace a team occupies inside one root session.
 *
 * [childSessionId] is the state-store key, the sandbox container name and a workspace path segment, so
 * both the runtime that creates it and the history replay that reads it back have to spell it the same
 * way — hence one owner for the string rather than two copies.
 */
internal object TeamSessions {
    fun childSessionId(
        rootSessionId: String,
        memberAgentId: Long,
    ): String = "${prefix(rootSessionId)}$memberAgentId"

    /** Shared prefix of every member child session of this root session. */
    fun prefix(rootSessionId: String): String = "team-$rootSessionId-m"
}

/**
 * Framing injected into the lead's prompt on top of its own system prompt.
 *
 * This is the *description* of the boundary, not the boundary itself: [com.agnetix.harnax.harness.HarnessAgentLauncher]
 * builds the lead without business tools, MCP or a sandbox (its skills do load), so a lead that ignores
 * this text still has nothing to execute with (design section 5).
 */
internal fun leadOrchestrationPrompt(spec: TeamRuntimeSpec): String {
    val roster = spec.members.joinToString("\n") { member ->
        "- agentId=${member.memberAgentId} ${member.agentName}：" +
            member.delegationDescription.ifBlank { member.description.ifBlank { "（未填写分工）" } }
    }
    return """
        ## 团队模式
        你现在是团队「${spec.teamName}」的主管，负责拆解目标、委派成员、验收结果并向用户汇总。

        ## 你的成员
        $roster

        ## 工作方式
        1. 需要具体执行（查资料、跑代码、处理文件、生成报告）时，调用 team_delegate 把任务交给对应成员，
           一次一件，等成员返回后再决定下一步。
        2. 你自己不执行这些工作：你没有业务工具、MCP，也没有执行沙箱。不要因为做不到就自己硬试，
           应该把这件事委派出去。
        3. 委派时写清目标、交付标准和必要上下文；成员拿不到本会话的历史，也不知道用户是谁。
        4. 成员产出的文件以 fileId 引用形式交回给你。需要下游成员继续处理时，把 fileId 写进下一次委派
           的任务里；成员会自行取用，你不要试图读取文件内容。
        5. 最终回复给用户时，只汇总成员的结论与产出文件引用（team_artifacts 可查），用团队语言重述，
           不要粘贴成员的原始输出。
        6. 成员失败或超时，如实说明并给出已完成的部分，由你决定是否重新委派。不要把失败包装成成功。
    """.trimIndent()
}

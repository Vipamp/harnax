package com.agnetix.harnax.harness.team

import com.agnetix.harnax.tools.sdk.ToolBox
import io.agentscope.core.tool.Tool
import io.agentscope.core.tool.ToolParam

/**
 * The lead's own tools: look at the roster, delegate one task, look at what has been produced.
 *
 * This is the whole capability set a lead gets, and it is a tool set rather than a prompt because the
 * lead is assembled with no business tools, MCP, skills or sandbox of its own (design section 5). Each
 * method returns text the lead reads as a tool result, so a refusal is something the model can act on
 * instead of an exception that ends the run.
 *
 * One instance belongs to one lead agent instance, holding that instance's orchestrator.
 */
class TeamLeadToolBox(
    private val orchestrator: TeamOrchestrator,
) : ToolBox() {

    @Tool(
        name = "team_members",
        description = "列出团队成员的 agentId、名称和分工。委派前先用它确认成员 id。",
        readOnly = true,
    )
    fun teamMembers(): String = execute { orchestrator.describeMembers().ifBlank { "这个团队没有成员。" } }

    @Tool(
        name = "team_delegate",
        description = "把一个任务交给指定成员执行，等它完成后返回结果。一次只做一件事，拿到结果再决定下一步。",
    )
    fun teamDelegate(
        @ToolParam(name = "member_agent_id", description = "成员的 agentId，见 team_members")
        memberAgentId: String?,
        @ToolParam(name = "task", description = "交给成员的任务：目标、交付标准、必要上下文")
        task: String?,
        @ToolParam(
            name = "file_ids",
            description = "交给该成员处理的产物 fileId，用逗号分隔（可选）",
            required = false,
        )
        fileIds: String?,
    ): String = execute("member_agent_id" to memberAgentId, "task" to task) {
        val id = memberAgentId?.trim()?.toLongOrNull()
            ?: return@execute "member_agent_id 必须是 team_members 列出的数字 id，收到的是 '$memberAgentId'。"
        orchestrator.delegate(
            memberAgentId = id,
            task = task.orEmpty(),
            fileIds = fileIds?.split(',', ' ', '\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
        )
    }

    @Tool(
        name = "team_artifacts",
        description = "列出本次会话中成员已发布的产出文件及其 fileId。",
        readOnly = true,
    )
    fun teamArtifacts(): String = execute { orchestrator.describeArtifacts() }

    override fun name(): String = NAME

    companion object {
        const val NAME = "team-lead-tool-box"

        /** Tool names the lead is assembled with; the framework allow-list keys on these. */
        val TOOL_NAMES = setOf("team_members", "team_delegate", "team_artifacts")
    }
}

/**
 * The artifact tools one member of a team gets, bound to that member.
 *
 * A member never names its own run: [orchestrator] resolves the delegation this member is currently
 * executing, and the publish/fetch scope comes from that run and from the trusted team spec. So the
 * model can only ever move files between its own workspace and artifacts of its own team session
 * (design sections 8.2 and 8.3).
 */
class TeamMemberToolBox(
    private val orchestrator: TeamOrchestrator,
    private val memberAgentId: Long,
) : ToolBox() {

    @Tool(
        name = "team_artifact_publish",
        description = "把工作区内的一个文件发布为团队产物，返回 fileId 交给主管或其他成员。",
    )
    fun teamArtifactPublish(
        @ToolParam(name = "path", description = "相对工作区的文件路径，例如 output/report.pdf")
        path: String?,
    ): String = execute("path" to path) {
        val run = currentRun() ?: return@execute OUTSIDE_RUN
        orchestrator.publishArtifact(run, path.orEmpty())
    }

    @Tool(
        name = "team_artifact_fetch",
        description = "用 fileId 把团队产物取到自己的工作区，之后才能读取或处理它。",
    )
    fun teamArtifactFetch(
        @ToolParam(name = "file_id", description = "产物 fileId")
        fileId: String?,
        @ToolParam(name = "dest_path", description = "写入的相对路径，例如 input/report.pdf")
        destPath: String?,
    ): String = execute("file_id" to fileId, "dest_path" to destPath) {
        val run = currentRun() ?: return@execute OUTSIDE_RUN
        orchestrator.fetchArtifact(run, fileId.orEmpty().trim(), destPath.orEmpty())
    }

    @Tool(
        name = "team_artifacts",
        description = "列出本次会话中已发布的团队产物及其 fileId。",
        readOnly = true,
    )
    fun teamArtifacts(): String = execute { orchestrator.describeArtifacts() }

    private fun currentRun() = orchestrator.currentRunOf(memberAgentId)

    override fun name(): String = NAME

    companion object {
        const val NAME = "team-member-tool-box"
        val TOOL_NAMES = setOf("team_artifact_publish", "team_artifact_fetch", "team_artifacts")
        private const val OUTSIDE_RUN = "当前不在任何委派任务中，无法操作团队产物。"
    }
}

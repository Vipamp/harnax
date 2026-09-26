package com.agnetix.harnax.harness.team

import com.agnetix.harnax.agent.protocol.ChatEvent
import com.agnetix.harnax.agent.protocol.EndEventChatEvent
import com.agnetix.harnax.agent.protocol.ErrorChatEvent
import com.agnetix.harnax.agent.protocol.EventSource
import com.agnetix.harnax.agent.protocol.KeepAliveChatEvent
import com.agnetix.harnax.agent.protocol.StreamTextChatEvent
import com.agnetix.harnax.agent.protocol.ToolConfirmChatEvent
import com.agnetix.harnax.agent.protocol.withSource
import com.agnetix.harnax.entity.TeamArtifact
import com.agnetix.harnax.harness.HarnessAgentWrapper
import com.agnetix.harnax.harness.config.TeamConfig
import com.agnetix.harnax.harness.sandbox.SandboxFileWriter
import io.agentscope.core.event.ConfirmResult
import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import io.agentscope.harness.agent.sandbox.Sandbox
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * What a human answered for one member tool call, or why nobody did.
 *
 * [CANCELLED] and a timeout are deliberately not [DENIED]: a denied run keeps going and lets the member
 * deal with the refusal, while a lost or stopped wait abandons the task where it stands. Neither one ever
 * runs the tool, and neither one is reported to the lead as a success (design section 9.2).
 */
enum class ConfirmDecision { APPROVED, DENIED, CANCELLED }

/** Why a confirmation could not be recorded, so the answer can say so instead of being dropped. */
enum class ConfirmationOutcome { APPROVED, DENIED, NO_PENDING, ALREADY_ANSWERED, NOT_IN_THIS_TEAM, STOPPED }

/**
 * One delegated task: a member, the child session it ran in, and the confirmation it may be waiting on.
 *
 * The id exists because a member can be delegated to more than once in a root run, and the user's answer
 * has to reach the run that asked (design section 9.1).
 */
class TeamChildRun internal constructor(
    val childRunId: String,
    val member: TeamMemberSpec,
    val childSessionId: String,
    val source: EventSource,
) {
    private val pending = AtomicReference<CompletableFuture<ConfirmDecision>?>()
    private val awaiting = AtomicReference<List<String>?>()
    private val finished = AtomicBoolean(false)

    /**
     * Files this delegation published. The child session outlives one task, so reporting by session would
     * hand the lead the same file twice on a follow-up.
     */
    private val published = CopyOnWriteArrayList<TeamArtifact>()

    val publishedArtifacts: List<TeamArtifact> get() = published.toList()

    internal fun recordPublished(artifact: TeamArtifact) {
        published += artifact
    }

    /**
     * Records that this run is waiting on a human and returns the future the answer completes.
     *
     * A run that is already finished, or already waiting, never gets a new wait: the caller is handed a
     * cancelled decision instead of a future nobody will ever complete.
     */
    internal fun awaitConfirmation(toolIds: List<String>): CompletableFuture<ConfirmDecision> {
        val future = CompletableFuture<ConfirmDecision>()
        if (finished.get() || !pending.compareAndSet(null, future)) {
            return CompletableFuture.completedFuture(ConfirmDecision.CANCELLED)
        }
        awaiting.set(toolIds)
        return future
    }

    /** The tools a human still has to answer for, or null while no answer is outstanding. */
    val pendingToolCallIds: List<String>? get() = awaiting.get()

    /** Completes the wait exactly once, so a duplicate submission cannot decide anything twice. */
    internal fun answer(approved: Boolean): ConfirmationOutcome {
        awaiting.set(null)
        val future = pending.getAndSet(null) ?: return if (finished.get()) {
            ConfirmationOutcome.ALREADY_ANSWERED
        } else {
            ConfirmationOutcome.NO_PENDING
        }
        val delivered = future.complete(if (approved) ConfirmDecision.APPROVED else ConfirmDecision.DENIED)
        return when {
            !delivered -> ConfirmationOutcome.ALREADY_ANSWERED
            approved -> ConfirmationOutcome.APPROVED
            else -> ConfirmationOutcome.DENIED
        }
    }

    internal fun cancelWait() {
        awaiting.set(null)
        pending.getAndSet(null)?.complete(ConfirmDecision.CANCELLED)
    }

    internal fun finish() {
        finished.set(true)
        cancelWait()
    }

    internal val isFinished: Boolean get() = finished.get()
}

/**
 * Runs a team: it holds the members' child runs, delegates work to them one task at a time, forwards
 * their events into the root session's stream, and hands the result back to the lead as text.
 *
 * One instance belongs to one lead agent instance, which is why this state needs no session-wide
 * registry: nothing outside the lead's own tools can reach these member wrappers.
 *
 * Two seams are worth knowing about before reading the code:
 * - Members are *not* AgentScope native subagents. The built-in subagent path inherits the parent's
 *   toolkit and always adds a general-purpose entry, which would leak the lead's capabilities into a
 *   member and the other way round; a member needs its own model, tools, MCP, skills, CLI and sandbox
 *   instead (design section 5). Members are built by the same factory an ordinary agent uses.
 * - Delegation is foreground and sequential: the calling tool thread waits for the member. That is what
 *   makes a member confirmation resumable in place, and it is why one member never runs two tasks at once.
 */
class TeamOrchestrator(
    val spec: TeamRuntimeSpec,
    private val config: TeamConfig,
    /**
     * Builds one member's agent. It receives this orchestrator because a member's tools have to be
     * registered against it, and the caller cannot pass an object it is still constructing.
     */
    private val memberFactory: (TeamMemberSpec, String, TeamOrchestrator) -> HarnessAgentWrapper,
    private val artifactGateway: TeamArtifactGateway?,
    private val sandboxProvider: (String) -> Sandbox?,
    private val sandboxDestroyer: (String) -> Unit,
    private val sandboxWorkspaceRoot: String,
) {

    private val log = LoggerFactory.getLogger(TeamOrchestrator::class.java)
    private val delegations = AtomicInteger()
    private val runs = ConcurrentHashMap<String, TeamChildRun>()

    /**
     * member agent id -> the run holding it. Delegation is foreground, so a member has one at a time, and
     * claiming it atomically is what stops two `team_delegate` calls in one lead turn from sharing the
     * member's session, sandbox and pending tool calls underneath it.
     */
    private val busyMembers = ConcurrentHashMap<Long, String>()

    /**
     * member agent id -> the child session that member keeps for this root session, and the agent built on
     * it. Reused across delegations so a follow-up to the same member continues its own conversation,
     * while another root session, user or member never shares it (design sections 6.2 and 7).
     */
    private val members = ConcurrentHashMap<Long, MemberRuntime>()

    @Volatile
    private var stopped = false

    /** Whether one root call currently owns this orchestrator's event stream. */
    private val rootCall = AtomicBoolean(false)

    @Volatile
    private var eventSink: Sinks.Many<ChatEvent>? = null

    private class MemberRuntime(
        val childSessionId: String,
        val wrapper: HarnessAgentWrapper,
    )

    /**
     * Starts a root call's member event stream. The returned flux is merged into that call's response, so
     * the user sees one conversation; [closeEventStream] ends it.
     *
     * Returns null when a previous root call is still running. A member waiting on a confirmation keeps
     * that call open, and taking the sink from it would send its events into an unrelated response.
     */
    fun openEventStream(): Flux<ChatEvent>? {
        if (!rootCall.compareAndSet(false, true)) return null
        // A stop ended the previous call, not this session: the budgets and the run ledger start over.
        stopped = false
        delegations.set(0)
        // A client that disappears mid-run leaves its delegation thread parked on a confirmation or still
        // streaming, and that thread holds the member's agent. Reusing the agent from this cache would
        // drive two turns through one child session at once, so anything still claimed is released first;
        // the next delegation to that member builds a fresh agent on the same child session.
        runs.values.forEach { it.cancelWait() }
        busyMembers.keys.forEach { dropMember(it, "abandoned by the previous root call") }
        runs.clear()
        busyMembers.clear()
        val sink = Sinks.many().unicast().onBackpressureBuffer<ChatEvent>()
        eventSink = sink
        return sink.asFlux()
    }

    fun closeEventStream() {
        if (!rootCall.getAndSet(false)) return
        val sink = eventSink
        eventSink = null
        sink?.tryEmitComplete()
    }

    val isStopped: Boolean get() = stopped

    /** Starts a delegated task and blocks until the member reports back; the return value is the lead's input. */
    fun delegate(
        memberAgentId: Long,
        task: String,
        fileIds: List<String> = emptyList(),
    ): String {
        if (stopped) return "团队已停止，本次委派没有执行。"
        val member = spec.memberOf(memberAgentId)
            ?: return "没有找到 agentId=$memberAgentId 这个成员。可用成员：${describeMembers()}"
        if (task.isBlank()) return "委派缺少任务说明。"
        // Delegation is foreground, so a member with an unfinished run is still working on the lead's
        // previous task; a second concurrent turn would share its session and sandbox underneath it.
        val childRunId = UUID.randomUUID().toString()
        val occupied = busyMembers.putIfAbsent(member.memberAgentId, childRunId)
        if (occupied != null) {
            return "成员「${member.agentName}」正在执行上一次委派（run=$occupied），请等待它返回或先停止这次运行。"
        }
        val started = delegations.incrementAndGet()
        if (started > config.maxDelegations) {
            delegations.decrementAndGet()
            busyMembers.remove(member.memberAgentId, childRunId)
            // The budget is a runtime limit, not a suggestion in the prompt (design section 9.3).
            return "本次团队运行已达到 ${config.maxDelegations} 次委派上限，任务没有执行。请直接汇总已有结果回复用户。"
        }

        val childSessionId = childSessionFor(member)
        val run = TeamChildRun(
            childRunId = childRunId,
            member = member,
            childSessionId = childSessionId,
            source = EventSource(
                teamId = spec.teamId,
                teamName = spec.teamName,
                memberAgentId = member.memberAgentId,
                memberAgentName = member.agentName,
                childRunId = childRunId,
                childSessionId = childSessionId,
            ),
        )
        runs[run.childRunId] = run
        log.info(
            "[team] Delegating to member: team={}, member={}, run={}, session={}, attempt={}",
            spec.teamId,
            member.memberAgentId,
            run.childRunId,
            run.childSessionId,
            started,
        )
        return try {
            runTask(run, buildTaskBrief(member, task, fileIds))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            run.finish()
            evict(run)
            "成员「${member.agentName}」的任务被取消。"
        } catch (e: Exception) {
            run.finish()
            evict(run)
            log.error(
                "[team] Member run failed: team={}, member={}, run={}",
                spec.teamId,
                member.memberAgentId,
                run.childRunId,
                e,
            )
            "成员「${member.agentName}」执行失败：${e.message ?: e.javaClass.simpleName}。"
        } finally {
            if (!run.isFinished) run.finish()
            busyMembers.remove(member.memberAgentId, childRunId)
        }
    }

    /**
     * Drives one member task: run it, and if it stops to ask for confirmation, wait for the human answer
     * and continue the *same* run with it. A member that fails is reported as failed, never as done.
     */
    private fun runTask(
        run: TeamChildRun,
        brief: String,
    ): String {
        val wrapper = memberWrapper(run)
        val report = StringBuilder()
        var next: Msg? = userMsg(brief)
        var confirmRounds = 0
        while (next != null) {
            val events = collectTurn(run, wrapper, next)
            events.filterIsInstance<StreamTextChatEvent>().forEach { report.append(it.message) }
            val failed = events.filterIsInstance<ErrorChatEvent>().firstOrNull()
            val asked = events.filterIsInstance<ToolConfirmChatEvent>().lastOrNull()
            next = when {
                failed != null -> return failReport(run, report, "执行出错：${failed.message}")
                stopped -> return failReport(run, report, "用户在执行中停止了这次运行")
                asked == null -> null
                ++confirmRounds > MAX_CONFIRM_ROUNDS -> {
                    return failReport(run, report, "成员反复请求确认，已中止这次委派")
                }
                else -> waitForConfirmation(run, wrapper, asked)
                    ?: return failReport(
                        run,
                        report,
                        "等待用户确认超时或已被取消，工具没有执行，这次任务未完成",
                    )
            }
        }
        val artifacts = run.publishedArtifacts
        val text = report.toString().trim()
        if (text.isEmpty() && artifacts.isEmpty()) {
            return "成员「${run.member.agentName}」没有返回内容。请检查任务描述，或改派其他成员。"
        }
        return buildString {
            append("成员「").append(run.member.agentName).append("」的结果：\n")
            append(text.ifEmpty { "（无文字结论）" })
            if (artifacts.isNotEmpty()) {
                append("\n\n产出文件（把 fileId 交给其他成员，你自己不要读取文件内容）：\n")
                artifacts.forEach { append("- fileId=").append(it.fileId).append(' ').append(it.fileName).append('\n') }
            }
            append("\n（本次运行已委派 ").append(delegations.get()).append(" 次，上限 ").append(config.maxDelegations).append("）")
        }
    }

    /**
     * Subscribes to one member turn, forwarding its events to the root stream as they arrive, and waits for
     * it to end.
     *
     * Forwarding has to happen live: the caller's stream has an idle timeout, and a member that thinks for
     * two minutes without the root stream emitting anything would otherwise take the user's conversation
     * down with it.
     */
    private fun collectTurn(
        run: TeamChildRun,
        wrapper: HarnessAgentWrapper,
        msg: Msg,
    ): List<ChatEvent> {
        val events = mutableListOf<ChatEvent>()
        wrapper.callStream(msg)
            .timeout(Duration.ofSeconds(config.memberTurnTimeoutSeconds))
            .doOnNext { event ->
                if (event !is EndEventChatEvent) {
                    events.add(event)
                    publish(event.withSource(run.source))
                }
            }
            .blockLast()
        return events
    }

    /**
     * Waits for the human answer and returns the message that resumes the member with it, or null when
     * nobody answered. A timeout never becomes an approval, and the tool stays unexecuted.
     */
    private fun waitForConfirmation(
        run: TeamChildRun,
        wrapper: HarnessAgentWrapper,
        asked: ToolConfirmChatEvent,
    ): Msg? {
        val future = run.awaitConfirmation(asked.pendingCallTools.map { it.toolId })
        // The card the user answers is the one [collectTurn] already forwarded, source and all; this only
        // opens the wait for that run's tools (design section 4.2).
        log.info(
            "[team] Member run awaits confirmation: team={}, member={}, run={}, tools={}",
            spec.teamId,
            run.member.memberAgentId,
            run.childRunId,
            asked.pendingCallTools.map { it.toolName },
        )
        val decision = awaitDecision(run, future) ?: return null
        if (decision == ConfirmDecision.CANCELLED) return null
        // The framework resumes a paused run from METADATA_CONFIRM_RESULTS carried on the next message,
        // which is the same contract the ordinary agent path uses in DefaultAgentRunner.confirm.
        val pendingTools = wrapper.getPendingToolCalls()
        if (pendingTools.isEmpty()) {
            log.warn("[team] Member run has nothing to resume (agent rebuilt mid-wait?): run={}", run.childRunId)
            return null
        }
        return Msg.builder()
            .name("user")
            .role(MsgRole.USER)
            .textContent("[confirm]")
            .metadata(
                mapOf(
                    Msg.METADATA_CONFIRM_RESULTS to pendingTools.map {
                        ConfirmResult(decision == ConfirmDecision.APPROVED, it, null)
                    },
                ),
            )
            .build()
    }

    /**
     * Blocks until the human answers or the confirm window closes, returning null when nobody did.
     *
     * Waiting in [TeamConfig.confirmHeartbeatSeconds] slices is the only reason this is a loop: the whole
     * window would otherwise be silent, and a silent stream is killed by session-router at 120s and by
     * channel-service at 180s — both tearing down a run that was one answer away from continuing. Each
     * slice that goes unanswered publishes a keep-alive, which every consumer drops on the floor.
     */
    private fun awaitDecision(
        run: TeamChildRun,
        future: CompletableFuture<ConfirmDecision>,
    ): ConfirmDecision? {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(config.confirmTimeoutSeconds)
        val heartbeatMillis = TimeUnit.SECONDS.toMillis(config.confirmHeartbeatSeconds.coerceAtLeast(1))
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                log.warn("[team] Confirmation timed out after {}s: run={}", config.confirmTimeoutSeconds, run.childRunId)
                run.cancelWait()
                return null
            }
            try {
                return future.get(minOf(remaining, heartbeatMillis), TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                publish(KeepAliveChatEvent(source = run.source))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                run.cancelWait()
                return null
            } catch (e: Exception) {
                log.warn("[team] Confirmation wait failed: run={}, {}", run.childRunId, e.message)
                run.cancelWait()
                return null
            }
        }
    }

    /** Records an answer for one child run. Only the orchestrator of that run's root session holds it. */
    fun answerConfirmation(
        childRunId: String,
        approved: Boolean,
    ): ConfirmationOutcome {
        val run = runs[childRunId] ?: return ConfirmationOutcome.NOT_IN_THIS_TEAM
        if (stopped) return ConfirmationOutcome.STOPPED
        val outcome = run.answer(approved)
        log.info("[team] Confirmation {} for run={} (member={})", outcome, childRunId, run.member.agentName)
        return outcome
    }

    /** Runs of this root session that are waiting for a human, so the UI can be re-asked or expired. */
    fun pendingConfirmations(): List<PendingConfirmation> = runs.values
        .mapNotNull { run ->
            val tools = run.pendingToolCallIds ?: return@mapNotNull null
            PendingConfirmation(
                childRunId = run.childRunId,
                memberAgentId = run.member.memberAgentId,
                memberAgentName = run.member.agentName,
                tools = tools,
            )
        }

    data class PendingConfirmation(
        val childRunId: String,
        val memberAgentId: Long,
        val memberAgentName: String,
        val tools: List<String>,
    )

    /**
     * The user stopped the root run: refuse further delegation, release anyone waiting on a confirmation
     * without approving them, and interrupt whatever is executing.
     */
    fun stop(destroySandboxes: Boolean) {
        stopped = true
        runs.values.forEach { run ->
            run.cancelWait()
            members[run.member.memberAgentId]?.wrapper?.interrupt()
        }
        if (destroySandboxes) {
            members.values.forEach { sandboxDestroyer(it.childSessionId) }
        }
        log.info(
            "[team] Team run stopped: team={}, session={}, sandboxesDestroyed={}",
            spec.teamId,
            spec.rootSessionId,
            destroySandboxes,
        )
    }

    /** Releases what this orchestrator built. The lead agent's own release does not reach in here. */
    fun releaseAll() {
        stopped = true
        runs.values.forEach { it.cancelWait() }
        members.values.forEach { runtime ->
            // Release is only safe on a quiescent agent, so interrupt first the way evict() does, and keep
            // going if one fails: the rest still own MCP processes.
            runCatching { runtime.wrapper.interrupt() }
            runCatching { runtime.wrapper.release() }
        }
        members.clear()
        runs.clear()
        busyMembers.clear()
        closeEventStream()
    }

    /** Text summary of the roster, for the lead's `team_members` tool. */
    fun describeMembers(): String = spec.members.joinToString("\n") { member ->
        "- agentId=${member.memberAgentId} ${member.agentName}：${member.delegationDescription.ifBlank { member.description }}"
    }

    /** Text summary of the files already published in this root session, for the lead and for members. */
    fun describeArtifacts(): String {
        val gateway = artifactGateway
            ?: return "产物存储未启用（MinIO 未配置），成员无法发布文件。"
        return try {
            val names = spec.members.associate { it.memberAgentId to it.agentName }
            gateway.list(spec.rootSessionId).joinToString("\n") { artifact ->
                "- fileId=${artifact.fileId} ${artifact.fileName}（${artifact.sizeBytes} 字节，由 " +
                    (names[artifact.memberAgentId] ?: "agentId=${artifact.memberAgentId}") + " 产出）"
            }.ifBlank { "这个会话还没有已发布的文件。" }
        } catch (e: Exception) {
            // Say it out loud: a silent empty list reads to the model as "nobody produced a file".
            log.warn("[team] Artifact list failed for session={}: {}", spec.rootSessionId, e.message)
            "读取产物列表失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Publishes one file from [run]'s own sandbox into the root session's artifact set. */
    fun publishArtifact(
        run: TeamChildRun,
        path: String,
    ): String {
        val gateway = artifactGateway
            ?: return "产物存储未启用（MinIO 未配置），无法发布文件。不要用公共链接或宿主目录代替。"
        val relative = SandboxFileWriter.safeRelativePath(path)
            ?: return "只能发布自己工作区内的文件，且路径不能包含 ..：$path"
        val sandbox = sandboxProvider(run.childSessionId)
            ?: return "当前没有运行中的沙箱，无法读取 $relative。"
        val absolute = "$sandboxWorkspaceRoot/$relative"
        val size = sandboxSize(sandbox, absolute)
            ?: return "文件不存在或不是普通文件：$relative"
        if (size > config.maxArtifactBytes) {
            return "文件 $relative 有 $size 字节，超过产物大小上限 ${config.maxArtifactBytes} 字节，未发布。"
        }
        val bytes = sandboxRead(sandbox, absolute)
            ?: return "读取文件 $relative 失败。"
        return try {
            val stored = gateway.publish(
                tenantId = spec.tenantId,
                rootSessionId = spec.rootSessionId,
                teamId = spec.teamId,
                memberAgentId = run.member.memberAgentId,
                childSessionId = run.childSessionId,
                fileName = relative.substringAfterLast('/'),
                mimeType = guessMimeType(relative),
                data = bytes,
            )
            run.recordPublished(stored)
            "已发布 $relative，fileId=${stored.fileId}（${stored.sizeBytes} 字节）。把该 fileId 交给主管或其他成员即可。"
        } catch (e: Exception) {
            log.error("[team] Publish failed: run={}, path={}", run.childRunId, relative, e)
            "发布文件 $relative 失败：${e.message ?: e.javaClass.simpleName}。不要用其他方式传递文件。"
        }
    }

    /** Downloads one artifact of this root session into [run]'s own workspace. */
    fun fetchArtifact(
        run: TeamChildRun,
        fileId: String,
        destPath: String,
    ): String {
        val gateway = artifactGateway ?: return "产物存储未启用（MinIO 未配置），无法获取文件。"
        // The reference is not a credential: tenant and root session come from this orchestrator, which
        // admin authorized for the root session, never from the model.
        val artifact = gateway.findOwned(fileId, spec.tenantId, spec.rootSessionId)
            ?: return "找不到属于本会话的产物 fileId=$fileId。"
        val relative = SandboxFileWriter.safeRelativePath(destPath)
            ?: return "只能写入自己工作区内的路径，且不能包含 ..：$destPath"
        if (artifact.sizeBytes > config.maxArtifactBytes) {
            return "产物 ${artifact.fileName} 超过大小上限 ${config.maxArtifactBytes} 字节，未下载。"
        }
        val bytes = gateway.read(artifact.objectKey)
            ?: return "读取产物 ${artifact.fileName} 失败（fileId=$fileId）。"
        val sandbox = sandboxProvider(run.childSessionId)
            ?: return "当前没有运行中的沙箱，无法写入 $relative。"
        return try {
            SandboxFileWriter.write(sandbox, "$sandboxWorkspaceRoot/$relative", bytes)
            "已获取 ${artifact.fileName}（${artifact.sizeBytes} 字节）到 $relative。"
        } catch (e: Exception) {
            log.error("[team] Fetch failed: run={}, fileId={}", run.childRunId, fileId, e)
            "获取产物 $fileId 失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * The delegation this member is executing right now, or null when its tools are reached outside one.
     *
     * Delegation is foreground and a member never has two runs in flight, so this is unambiguous — and it
     * is why a member tool gets its run without the framework having to carry a team identity around.
     */
    fun currentRunOf(memberAgentId: Long): TeamChildRun? = runs.values
        .lastOrNull { it.member.memberAgentId == memberAgentId && !it.isFinished }

    private fun failReport(
        run: TeamChildRun,
        report: StringBuilder,
        reason: String,
    ): String {
        val partial = report.toString().trim()
        run.finish()
        evict(run)
        return buildString {
            append("成员「").append(run.member.agentName).append("」未完成：").append(reason).append("。")
            if (partial.isNotEmpty()) {
                append("\n已完成部分：\n").append(partial)
            }
        }
    }

    /**
     * Drops a member runtime whose state can no longer be trusted.
     *
     * [HarnessAgentWrapper.interrupt] latches, and a turn abandoned at a confirmation leaves a tool call
     * waiting for an answer nobody will give. Both poison the *in-memory* agent, not the persisted child
     * session: the next delegation to this member builds a fresh agent that reads the same history.
     */
    private fun evict(run: TeamChildRun) {
        dropMember(run.member.memberAgentId, "run=${run.childRunId} turned unreliable")
    }

    /** Releases one member's agent, if this orchestrator built one; the next delegation creates a new one. */
    private fun dropMember(
        memberAgentId: Long,
        reason: String,
    ) {
        val runtime = members.remove(memberAgentId) ?: return
        runCatching { runtime.wrapper.interrupt() }
        runCatching { runtime.wrapper.release() }
        log.info(
            "[team] Dropped member runtime: member={}, session={}, reason={}",
            spec.memberOf(memberAgentId)?.agentName ?: memberAgentId,
            runtime.childSessionId,
            reason,
        )
    }

    /**
     * member agent id -> its child session, computed without creating anything. No slash: this value keys
     * the state store, the sandbox container and a workspace path segment.
     */
    private fun childSessionFor(member: TeamMemberSpec): String = TeamSessions.childSessionId(spec.rootSessionId, member.memberAgentId)

    /**
     * The member's agent, built on first use and kept for the rest of this root session.
     *
     * The build happens outside the map lock, because it makes network calls (model config, MCP
     * handshakes) and [ConcurrentHashMap.computeIfAbsent] would hold the bin across them. Two concurrent
     * delegations to one member are already refused by [delegate], so the loser here is a rare race and
     * its agent is released rather than cached.
     */
    private fun memberWrapper(run: TeamChildRun): HarnessAgentWrapper {
        val memberAgentId = run.member.memberAgentId
        members[memberAgentId]?.let { return it.wrapper }
        val childSessionId = childSessionFor(run.member)
        val created = MemberRuntime(childSessionId, memberFactory(run.member, childSessionId, this))
        val previous = members.putIfAbsent(memberAgentId, created)
        if (previous != null) {
            created.wrapper.release()
            return previous.wrapper
        }
        return created.wrapper
    }

    private fun buildTaskBrief(
        member: TeamMemberSpec,
        task: String,
        fileIds: List<String>,
    ): String = buildString {
        append("【团队】").append(spec.teamName)
        append("　【你的分工】").append(member.delegationDescription.ifBlank { member.description })
        append("\n【任务】\n").append(task.trim())
        if (fileIds.isNotEmpty()) {
            append("\n【可用文件】先用 team_artifact_fetch 获取再处理：")
            fileIds.forEach { append(" fileId=").append(it) }
        }
        append("\n【交付】需要交给其他成员或主管的文件，用 team_artifact_publish 发布，并在回复里写明 fileId。")
    }

    private fun userMsg(text: String): Msg = Msg.builder()
        .name("user")
        .role(MsgRole.USER)
        .textContent(text)
        .build()

    private fun publish(event: ChatEvent) {
        val sink = eventSink ?: return
        val result = sink.tryEmitNext(event)
        if (result.isFailure && log.isDebugEnabled) {
            log.debug("[team] Dropped member event for session={}: {}", spec.rootSessionId, result)
        }
    }

    private fun sandboxSize(
        sandbox: Sandbox,
        absolute: String,
    ): Long? = try {
        // `-f` rules out a directory, `! -L` a symlink, and the resolved target has to stay inside the
        // workspace (design section 8.3).
        val command = "if [ -f '$absolute' ] && [ ! -L '$absolute' ]; then " +
            "T=\$(readlink -f '$absolute'); case \"\$T\" in $sandboxWorkspaceRoot/*) stat -c %s \"\$T\";; esac; fi"
        sandbox.exec(null, command, 15).stdout().trim().toLongOrNull()
    } catch (e: Exception) {
        log.debug("[team] Size probe failed for {}: {}", absolute, e.message)
        null
    }

    /**
     * Reads one whole file out of the sandbox through [SandboxFileWriter], or null when it is missing or
     * unreadable.
     *
     * The writer logs the reason at debug — to it a missing file is a normal answer — while here a null
     * makes a tool call fail, so it gets the one WARN the caller needs to see.
     */
    private fun sandboxRead(
        sandbox: Sandbox,
        absolute: String,
    ): ByteArray? = SandboxFileWriter.read(sandbox, absolute).also {
        if (it == null) log.warn("[team] Sandbox read failed for {}", absolute)
    }

    private fun guessMimeType(fileName: String): String = MIME_BY_EXTENSION[fileName.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

    companion object {
        /** A member that keeps asking after this many rounds is stuck; stop rather than loop forever. */
        private const val MAX_CONFIRM_ROUNDS = 5

        private val MIME_BY_EXTENSION = mapOf(
            "csv" to "text/csv",
            "json" to "application/json",
            "txt" to "text/plain",
            "md" to "text/markdown",
            "html" to "text/html",
            "pdf" to "application/pdf",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "zip" to "application/zip",
        )
    }
}

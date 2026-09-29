import Foundation

/// Everything the scheduled-task screens do, read and write — the task surface (`§2`, `§3`) and the execution
/// log surface (`§5`) alike.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentTaskController.kt:47` on the
/// `/api/admin/agent-tasks` prefix. Admin is only a relay for this domain — since release 2 the real service is
/// `harnax-scheduler`, and admin forwards the request body byte for byte and the answer straight back
/// (`:23-44`, `SchedulerClientImpl.kt:109-134`). Two consequences shape this protocol:
///
/// - **A business refusal still arrives as HTTP 200.** The relay registers an empty handler for upstream error
///   statuses (`SchedulerClientImpl.kt:127`), so the envelope's `code` is the only verdict that means anything.
///   `AdminClient` already enforces that; nothing here may assume a 4xx or 5xx transport status.
/// - **The messages are English and stay English.** Every rejection string is hardcoded in the scheduler and bean
///   validation, and admin forwards it verbatim, so `Accept-Language` buys nothing on this domain (`§0`). The
///   text goes to the user as it arrived.
///
/// Create, update, toggle, trigger and delete all answer `ResultVo<Void>` (`§1`), so a saved row is re-read from
/// the page rather than taken from the response — which is also the only way the list learns that an update
/// paused the task.
public protocol AgentTaskCataloging: Sendable {
    /// `name` is a `LIKE` pattern and `taskStatus` an exact 0/1 (`AgentTaskMapper.xml:86-94`); both are
    /// optional, and an unused one is left off the query rather than sent blank.
    ///
    /// The filter parameter is `name` here and `taskName` on the log route (`§1`) — two spellings for the same
    /// idea, which is why the two methods do not share a parameter list.
    func agentTaskPage(
        name: String?,
        taskStatus: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<AgentTaskSummary>, APIError>

    func createAgentTask(_ draft: AgentTaskDraft) async -> Result<EmptyResponse, APIError>

    /// Partial: a field that is `nil` is left off the body and keeps its stored value
    /// (`AgentTaskCrudServiceImpl.kt:122-154`). A successful call **also pauses the task** whatever the body
    /// said (`:156-157`), so the caller must re-read the list afterwards.
    func updateAgentTask(id: Int64, _ change: AgentTaskChange) async -> Result<EmptyResponse, APIError>

    /// `POST /toggle/{id}?status=`, where `1` starts and `0` pauses. Starting is the branch that can be
    /// refused for a reason the client cannot see — an expression that passes the field-count check but that
    /// Quartz rejects answers 500 with its own sentence (`§4.1`).
    func setAgentTaskStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError>

    /// One run now, without touching the schedule. Posted for a paused task too: `runTaskOnce` never reads
    /// `taskStatus` (`SchedulerServiceImpl.kt:446-478`).
    func triggerAgentTask(id: Int64) async -> Result<EmptyResponse, APIError>

    func deleteAgentTask(id: Int64) async -> Result<EmptyResponse, APIError>

    /// The target-agent picker's source. Admin answers this one locally instead of forwarding, from
    /// `agentService.getActiveAgents()` (`AgentTaskController.kt:202-210`), so it lists agents rather than tasks
    /// and carries no creator gate.
    func agentTaskAgents() async -> Result<[AgentTaskAgentOption], APIError>

    /// One task's execution log (`§5.3`), fixed to `create_time DESC` server-side
    /// (`AgentTaskLogMapper.xml:162`).
    ///
    /// Visibility here is *wider* than the write gates: the read joins on the owning task's
    /// `t.active = 1 AND (t.is_public = 1 OR t.creator = #{username})` (`AgentTaskLogMapper.xml:136-141`), so
    /// another account's public task still yields its logs. Stopping one of those rows is a different gate
    /// entirely, which is why `AgentTaskLog.stoppable(by:)` sits next to this rather than being implied by a
    /// successful read.
    func agentTaskLogs(
        taskID: Int64,
        filter: AgentTaskLogFilter,
        num: Int,
        size: Int
    ) async -> Result<Page<AgentTaskLog>, APIError>

    /// Interrupt one live execution, addressed by **log** id (`§5.5`).
    ///
    /// The answer is one verdict and nothing else — `"Task stopped"`, or 500 "Task is not running or already
    /// completed" — because the three outcomes of a real interrupt are settled inside the scheduler and never
    /// reach the caller (`SchedulerServiceImpl.kt:703-740`): `Delivered` leaves the row at 4 for the execution
    /// thread to close out at 5, `Missed` writes 5 on the spot, `Unanswered` leaves it at 4 for the sweep. A
    /// caller that wants to know which of the three happened re-reads the page, which is exactly what the web
    /// console does one second later (`TaskLogModal.tsx:159-161`).
    func stopAgentTaskLog(id: Int64) async -> Result<EmptyResponse, APIError>
}

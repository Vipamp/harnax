import Foundation
import HarnaxCore
@testable import HarnaxFeatures

/// The scheduled-task screens' stand-in, on the reply-queue discipline the other fakes use: a request nobody
/// queued answers as a decoding failure and still shows up in the call log, so one extra read reads as a wrong
/// count rather than as a silent pass.
///
/// `gateWrites` covers the three writes whose *owning screen* decides something while the answer is still out:
/// the list's switch rolls its override back (`TaskListViewModel.setStatus`), run-now marks the row as triggered
/// (`trigger`), and a stop holds the row's spinner (`TaskLogListViewModel.stop`). Delete is ungated on both
/// screens because its row leaves only after the answer, so there is no window to look at.
///
/// `StatusFilter.queryValue`, the log filter and the agent-option read are all logged: this domain spells its
/// page flag `taskStatus` and its log flag plain `status` (`AgentTaskController.kt:58` against `:175`), and both
/// are easy to mix up in a call site.
final class FakeAgentTasks: AgentTaskCataloging, @unchecked Sendable {
    private(set) var pageRequests: [(name: String?, taskStatus: Int?, num: Int, size: Int)] = []
    var pageReplies: [Result<Page<AgentTaskSummary>, APIError>] = []

    private(set) var logRequests: [(taskID: Int64, filter: AgentTaskLogFilter, num: Int, size: Int)] = []
    var logReplies: [Result<Page<AgentTaskLog>, APIError>] = []

    private(set) var agentsRequests = 0
    var agentsReplies: Result<[AgentTaskAgentOption], APIError> = .success([])

    private(set) var createRequests: [AgentTaskDraft] = []
    var createReplies: [Result<EmptyResponse, APIError>] = []

    private(set) var updateRequests: [(id: Int64, change: AgentTaskChange)] = []
    var updateReplies: [Result<EmptyResponse, APIError>] = []

    private(set) var statusRequests: [(id: Int64, enabled: Bool)] = []
    var statusReplies: [Result<EmptyResponse, APIError>] = []

    private(set) var triggerRequests: [Int64] = []
    var triggerReplies: [Result<EmptyResponse, APIError>] = []

    private(set) var deleteRequests: [Int64] = []
    var deleteReplies: [Result<EmptyResponse, APIError>] = []

    private(set) var stopRequests: [Int64] = []
    var stopReplies: [Result<EmptyResponse, APIError>] = []

    var gateWrites = false

    private var parked: [() -> Void] = []

    /// Both page reads are parkable: an append has to be able to stay in flight while the reader changes the
    /// query (`ListAppendIdentityTests`).
    let pageGate = PageReadGate<Result<Page<AgentTaskSummary>, APIError>>()
    let logGate = PageReadGate<Result<Page<AgentTaskLog>, APIError>>()

    func agentTaskPage(
        name: String?,
        taskStatus: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<AgentTaskSummary>, APIError> {
        pageRequests.append((name: name, taskStatus: taskStatus, num: num, size: size))
        return await pageGate.absorb(pageReplies.isEmpty ? .failure(.decoding) : pageReplies.removeFirst())
    }

    func agentTaskLogs(
        taskID: Int64,
        filter: AgentTaskLogFilter,
        num: Int,
        size: Int
    ) async -> Result<Page<AgentTaskLog>, APIError> {
        logRequests.append((taskID: taskID, filter: filter, num: num, size: size))
        return await logGate.absorb(logReplies.isEmpty ? .failure(.decoding) : logReplies.removeFirst())
    }

    func agentTaskAgents() async -> Result<[AgentTaskAgentOption], APIError> {
        agentsRequests += 1
        return agentsReplies
    }

    func createAgentTask(_ draft: AgentTaskDraft) async -> Result<EmptyResponse, APIError> {
        createRequests.append(draft)
        return await write(\.createReplies)
    }

    func updateAgentTask(id: Int64, _ change: AgentTaskChange) async -> Result<EmptyResponse, APIError> {
        updateRequests.append((id: id, change: change))
        return await write(\.updateReplies)
    }

    func setAgentTaskStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> {
        statusRequests.append((id: id, enabled: enabled))
        return await write(\.statusReplies)
    }

    func triggerAgentTask(id: Int64) async -> Result<EmptyResponse, APIError> {
        triggerRequests.append(id)
        return await write(\.triggerReplies)
    }

    func deleteAgentTask(id: Int64) async -> Result<EmptyResponse, APIError> {
        deleteRequests.append(id)
        return deleteReplies.isEmpty ? .failure(.decoding) : deleteReplies.removeFirst()
    }

    func stopAgentTaskLog(id: Int64) async -> Result<EmptyResponse, APIError> {
        stopRequests.append(id)
        return await write(\.stopReplies)
    }

    /// Runs every parked write in the order it went out, each pulling its own next reply.
    func releaseWrites() {
        let waiting = parked
        parked = []
        for resume in waiting { resume() }
    }

    private func write(
        _ queue: ReferenceWritableKeyPath<FakeAgentTasks, [Result<EmptyResponse, APIError>]>
    ) async -> Result<EmptyResponse, APIError> {
        guard gateWrites else { return next(from: queue) }
        return await withCheckedContinuation { continuation in
            parked.append { continuation.resume(returning: self.next(from: queue)) }
        }
    }

    /// An unqueued request is a test bug, and `.decoding` is what the real facade answers with when the envelope
    /// it got cannot be read — loud, and not a silent success.
    private func next(
        from queue: ReferenceWritableKeyPath<FakeAgentTasks, [Result<EmptyResponse, APIError>]>
    ) -> Result<EmptyResponse, APIError> {
        self[keyPath: queue].isEmpty ? .failure(.decoding) : self[keyPath: queue].removeFirst()
    }
}

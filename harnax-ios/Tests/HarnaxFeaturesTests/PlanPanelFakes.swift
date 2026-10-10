import Foundation
import HarnaxCore
@testable import HarnaxFeatures

/// The plan panel's stand-in, on the reply-queue discipline the other fakes use: a read nobody queued answers as
/// a decoding failure and still shows up in the call log, so one extra poll reads as a wrong count rather than as
/// a silent pass.
///
/// The two queues are separate on purpose — the panel's whole shape is that `current-plan` has a cadence and
/// `plans` does not (`ChatWindow.tsx:654-674` against the never-started `plansListTimerRef` at `:608`), and a fake
/// that served both from one list would let a history re-read pass as a poll.
///
/// Each side also takes a *fallback* reply, used only once its queue runs dry. That is what makes a cadence test
/// readable: it asks for three ticks and must not have the third one fail because the test only queued two
/// answers. With no queue and no fallback, the read fails, which is how a missing stub still shows up.
final class FakePlanReading: PlanReading, @unchecked Sendable {
    /// The session id each read was addressed with, in call order.
    private(set) var historyRequests: [String] = []
    var historyReplies: [Result<[PlanNote], APIError>] = []
    var historyFallback: Result<[PlanNote], APIError>?

    private(set) var currentRequests: [String] = []
    var currentReplies: [Result<CurrentPlan, APIError>] = []
    var currentFallback: Result<CurrentPlan, APIError>?

    func planNotes(sessionId: String) async -> Result<[PlanNote], APIError> {
        historyRequests.append(sessionId)
        if !historyReplies.isEmpty { return historyReplies.removeFirst() }
        return historyFallback ?? .failure(.decoding)
    }

    func currentPlan(sessionId: String) async -> Result<CurrentPlan, APIError> {
        currentRequests.append(sessionId)
        if !currentReplies.isEmpty { return currentReplies.removeFirst() }
        return currentFallback ?? .failure(.decoding)
    }
}

/// Plans spelled as the runtime writes them. Built through `PlanNote`'s own initialiser rather than through JSON:
/// the decoding tolerances are `HarnaxCoreTests`' problem, and a view model test should not fail because a key
/// name drifted.
enum PlanFixtures {
    static let session = "sess-7"

    static func subtask(
        _ name: String?,
        state: PlanState? = .done,
        seconds: Int64? = 12,
        description: String? = nil,
        expected: String? = nil,
        outcome: String? = nil
    ) -> PlanSubTask {
        PlanSubTask(
            name: name,
            description: description,
            expectedOutcome: expected,
            outcome: outcome,
            state: state,
            createdAt: "2026-09-25 09:00:0\(seconds ?? 0)",
            finishedAt: nil,
            costTimeSeconds: seconds
        )
    }

    static func note(
        _ planId: String?,
        name: String? = "拆分会话路由的控制器",
        description: String? = "把 AgentProxyController 按计划与权限两组拆开",
        expected: String? = nil,
        subtasks: [PlanSubTask] = [],
        status: PlanState? = .inProgress,
        seconds: Int64? = 45,
        createdAt: String? = "2026-09-25 09:00:00",
        sessionId: String? = session
    ) -> PlanNote {
        PlanNote(
            planId: planId,
            sessionId: sessionId,
            name: name,
            description: description,
            expectedOutcome: expected,
            subtasks: subtasks,
            createdAt: createdAt,
            finishedAt: nil,
            costTimeSeconds: seconds,
            status: status
        )
    }

    /// The reading of a plan that is open right now.
    static func open(_ note: PlanNote?) -> Result<CurrentPlan, APIError> {
        .success(CurrentPlan(note: note))
    }

    static let noPlan: Result<CurrentPlan, APIError> = .success(CurrentPlan())
}

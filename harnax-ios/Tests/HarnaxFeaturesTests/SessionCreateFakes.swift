import Foundation
import HarnaxCore
@testable import HarnaxFeatures

/// The create sheet's stand-in for `SessionCreating`, on the reply-queue discipline the other fakes keep
/// (`TaskFakes.swift`, `SessionListFakes.swift`): a request nobody queued answers as a decoding failure and
/// still lands in the call log, so one extra read reads as a wrong count rather than a silent pass.
///
/// That discipline is what makes the debounce assertable. The whole point of `scheduleTitleCheck` is that a
/// burst of keystrokes costs *one* `COUNT(*)` (`SettingsModal.tsx:54` defines the debounced validator the
/// console never wired up), and the only way to see that from a test is to count the calls.
///
/// Three gates, because the sheet has three independent round trips whose *ordering* is behaviour:
/// - `gateChecks` parks the duplicate check, so a test can hold a verdict open over a later edit;
/// - `gateChoices` parks the executor read, which is what proves the title box is not waiting on it;
/// - `gateWrites` parks the create, the one call that must not be sent twice.
final class FakeSessionCreating: SessionCreating, @unchecked Sendable {
    private(set) var titleCheckRequests: [String] = []
    var titleCheckReplies: [Result<Bool, APIError>] = []

    private(set) var choicesRequests = 0
    var choicesReplies: [Result<SessionExecutorChoices, APIError>] = []

    private(set) var createRequests: [SessionCreateDraft] = []
    var createReplies: [Result<EmptyResponse, APIError>] = []

    var gateChecks = false
    var gateChoices = false
    var gateWrites = false

    private var parkedChecks: [() -> Void] = []
    private var parkedChoices: [() -> Void] = []
    private var parkedWrites: [() -> Void] = []

    func sessionTitleTaken(_ title: String) async -> Result<Bool, APIError> {
        titleCheckRequests.append(title)
        return await parked(gateChecks, \.titleCheckReplies, into: \.parkedChecks)
    }

    func executorChoices() async -> Result<SessionExecutorChoices, APIError> {
        choicesRequests += 1
        guard gateChoices else {
            return choicesReplies.isEmpty ? .failure(.decoding) : choicesReplies.removeFirst()
        }
        return await withCheckedContinuation { continuation in
            parkedChoices.append {
                continuation.resume(returning: self.choicesReplies.isEmpty
                    ? .failure(.decoding)
                    : self.choicesReplies.removeFirst())
            }
        }
    }

    func createSession(_ draft: SessionCreateDraft) async -> Result<EmptyResponse, APIError> {
        createRequests.append(draft)
        guard gateWrites else {
            return createReplies.isEmpty ? .failure(.decoding) : createReplies.removeFirst()
        }
        return await withCheckedContinuation { continuation in
            parkedWrites.append {
                continuation.resume(returning: self.createReplies.isEmpty
                    ? .failure(.decoding)
                    : self.createReplies.removeFirst())
            }
        }
    }

    func releaseChecks() { release(&parkedChecks) }
    func releaseChoices() { release(&parkedChoices) }
    func releaseWrites() { release(&parkedWrites) }

    private func release(_ queue: inout [() -> Void]) {
        let waiting = queue
        queue = []
        for resume in waiting { resume() }
    }

    /// Gated duplicate checks park the same way the writes do; an ungated one answers off its queue.
    private func parked(
        _ gated: Bool,
        _ queue: ReferenceWritableKeyPath<FakeSessionCreating, [Result<Bool, APIError>]>,
        into parked: ReferenceWritableKeyPath<FakeSessionCreating, [() -> Void]>
    ) async -> Result<Bool, APIError> {
        guard gated else {
            return self[keyPath: queue].isEmpty ? .failure(.decoding) : self[keyPath: queue].removeFirst()
        }
        return await withCheckedContinuation { continuation in
            self[keyPath: parked].append {
                continuation.resume(returning: self[keyPath: queue].isEmpty
                    ? .failure(.decoding)
                    : self[keyPath: queue].removeFirst())
            }
        }
    }
}

// MARK: - fixtures

enum CreateFixtures {
    static func agent(_ id: Int64, _ name: String = "翻译助手") -> SessionExecutorOption {
        SessionExecutorOption(kind: .agent, id: id, name: name, detail: "把中文翻成英文")
    }

    static func team(_ id: Int64, _ name: String = "本地化小组") -> SessionExecutorOption {
        SessionExecutorOption(kind: .team, id: id, name: name)
    }

    /// Both groups answered, which is the shape a healthy modal opens with.
    static func bothGroups() -> SessionExecutorChoices {
        SessionExecutorChoices(agents: [agent(7)], teams: [team(4)])
    }

    /// The console's independent-load failure: the team route threw, the agent route did not
    /// (`SettingsModal.tsx:64-86`).
    static func teamsMissing() -> SessionExecutorChoices {
        SessionExecutorChoices(agents: [agent(7)], teams: [], unavailableKinds: [.team])
    }

    static func agentsMissing() -> SessionExecutorChoices {
        SessionExecutorChoices(agents: [], teams: [team(4)], unavailableKinds: [.agent])
    }

    static func bothMissing() -> SessionExecutorChoices {
        SessionExecutorChoices(unavailableKinds: [.agent, .team])
    }
}

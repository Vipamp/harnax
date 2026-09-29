import Foundation
import HarnaxCore
@testable import HarnaxFeatures

/// The environment-variable screens' stand-in, on the reply-queue discipline the other fakes use: a request
/// nobody queued answers as a decoding failure and still shows up in the call log, so one extra read reads
/// as a wrong count rather than as a silent pass.
///
/// `gateWrites` is the dial the two mid-flight behaviours need. `EnvVarListViewModel.setStatus` decides its
/// rollback while the answer is still out, and `EnvVarFormViewModel.save` refuses a second tap for the same
/// reason, so a write has to be holdable (`SessionListFakes` does exactly this for its switch and rename).
/// Delete stays ungated: its row leaves the list only after the answer, so there is no window to look at.
final class FakeEnvVars: EnvVarCataloging, @unchecked Sendable {
    /// The paged read takes `keyword` alone — no status filter exists on this route
    /// (`EnvVariableController.kt:25-37`), so a logged `status` here would be a fiction.
    private(set) var requests: [(keyword: String?, num: Int, size: Int)] = []
    var replies: [Result<Page<EnvVarSummary>, APIError>] = []

    private(set) var createRequests: [EnvVarDraft] = []
    var createReplies: [Result<EmptyResponse, APIError>] = []

    /// The update route is addressed by the row id and takes the whole patch body, so both are logged.
    private(set) var updateRequests: [(id: Int64, change: EnvVarChange)] = []
    var updateReplies: [Result<EmptyResponse, APIError>] = []

    private(set) var statusRequests: [(id: Int64, enabled: Bool)] = []
    var statusReplies: [Result<EmptyResponse, APIError>] = []

    private(set) var deleteRequests: [Int64] = []
    var deleteReplies: [Result<EmptyResponse, APIError>] = []

    /// The `/list` projection takes no parameter, so only the count is loggable; a form that re-read it on
    /// every keystroke would otherwise pass a test that only checked the candidates it got.
    private(set) var candidateCalls = 0
    var candidateReplies: [Result<[EnvVarCandidate], APIError>] = []

    var gateWrites = false

    private var parked: [() -> Void] = []

    func envVarPage(keyword: String?, num: Int, size: Int) async -> Result<Page<EnvVarSummary>, APIError> {
        requests.append((keyword: keyword, num: num, size: size))
        return replies.isEmpty ? .failure(.decoding) : replies.removeFirst()
    }

    func envVarCandidates() async -> Result<[EnvVarCandidate], APIError> {
        candidateCalls += 1
        return candidateReplies.isEmpty ? .failure(.decoding) : candidateReplies.removeFirst()
    }

    func createEnvVar(_ draft: EnvVarDraft) async -> Result<EmptyResponse, APIError> {
        createRequests.append(draft)
        return await write(\.createReplies)
    }

    func updateEnvVar(id: Int64, _ change: EnvVarChange) async -> Result<EmptyResponse, APIError> {
        updateRequests.append((id: id, change: change))
        return await write(\.updateReplies)
    }

    func setEnvVarStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> {
        statusRequests.append((id: id, enabled: enabled))
        return await write(\.statusReplies)
    }

    func deleteEnvVar(id: Int64) async -> Result<EmptyResponse, APIError> {
        deleteRequests.append(id)
        return deleteReplies.isEmpty ? .failure(.decoding) : deleteReplies.removeFirst()
    }

    /// Runs every parked write in the order it went out, each pulling its own next reply.
    func releaseWrites() {
        let waiting = parked
        parked = []
        for resume in waiting { resume() }
    }

    private func write(
        _ queue: ReferenceWritableKeyPath<FakeEnvVars, [Result<EmptyResponse, APIError>]>
    ) async -> Result<EmptyResponse, APIError> {
        guard gateWrites else { return next(from: queue) }
        return await withCheckedContinuation { continuation in
            parked.append { continuation.resume(returning: self.next(from: queue)) }
        }
    }

    /// An unqueued request is a test bug, and `.decoding` is what the real facade answers with when the
    /// envelope it got cannot be read — loud, and not a silent success.
    private func next(
        from queue: ReferenceWritableKeyPath<FakeEnvVars, [Result<EmptyResponse, APIError>]>
    ) -> Result<EmptyResponse, APIError> {
        self[keyPath: queue].isEmpty ? .failure(.decoding) : self[keyPath: queue].removeFirst()
    }
}

extension EnvVarCandidate {
    /// The projection type has no initialiser on purpose — a mask must not be constructible in Swift any
    /// more than it is submittable — so a fixture is built by decoding the four-key map the server answers.
    static func stub(_ fields: [String: Any]) throws -> EnvVarCandidate {
        let data = try JSONSerialization.data(withJSONObject: fields)
        return try JSONDecoder().decode(EnvVarCandidate.self, from: data)
    }
}

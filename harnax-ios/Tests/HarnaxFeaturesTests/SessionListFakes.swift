import Foundation
import HarnaxCore
@testable import HarnaxFeatures

/// The conversation list's stand-in, on the reply-queue discipline the other fakes use: a request nobody
/// queued answers as a decoding failure and still shows up in the call log, so one extra read reads as a
/// wrong count rather than as a crash.
///
/// `gateWrites` is the dial the two optimistic paths need. The status switch and the rename change what the
/// row shows *before* the server answers, and that window — not the settled state — is where a rollback is
/// decided, so a write has to be holdable mid-flight. `releaseWrites()` then replays them in order.
final class FakeSessions: SessionCataloging, @unchecked Sendable {
    private(set) var requests: [(keyword: String?, status: Int?, num: Int, size: Int)] = []
    var replies: [Result<Page<SessionSummary>, APIError>] = []

    private(set) var renameRequests: [(id: Int64, title: String)] = []
    var renameReplies: [Result<EmptyResponse, APIError>] = []

    private(set) var statusRequests: [(id: Int64, enabled: Bool)] = []
    var statusReplies: [Result<EmptyResponse, APIError>] = []

    private(set) var deleteRequests: [Int64] = []
    var deleteReplies: [Result<EmptyResponse, APIError>] = []

    /// The command is addressed by the string business key, so the log records that rather than a row id.
    private(set) var clearRequests: [String] = []
    var clearReplies: [Result<AgentCommandReply, APIError>] = []

    var gateWrites = false

    private var parked: [() -> Void] = []

    /// The page read is parkable: an append has to be able to stay in flight while the reader changes the
    /// query (`ListAppendIdentityTests`).
    let pageGate = PageReadGate<Result<Page<SessionSummary>, APIError>>()

    func sessionPage(
        keyword: String?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<SessionSummary>, APIError> {
        requests.append((keyword: keyword, status: status, num: num, size: size))
        return await pageGate.absorb(replies.isEmpty ? .failure(.decoding) : replies.removeFirst())
    }

    func renameSession(_ session: SessionSummary, to title: String) async -> Result<EmptyResponse, APIError> {
        renameRequests.append((id: session.id ?? -1, title: title))
        return await write(\.renameReplies)
    }

    func setSessionStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> {
        statusRequests.append((id: id, enabled: enabled))
        return await write(\.statusReplies)
    }

    /// Not gateable: the row leaves the list only after the answer, so there is no mid-flight to look at.
    func deleteSession(id: Int64) async -> Result<EmptyResponse, APIError> {
        deleteRequests.append(id)
        return deleteReplies.isEmpty ? .failure(.decoding) : deleteReplies.removeFirst()
    }

    func clearMessages(sessionId: String) async -> Result<AgentCommandReply, APIError> {
        clearRequests.append(sessionId)
        guard gateWrites else { return nextClear() }
        return await withCheckedContinuation { continuation in
            parked.append { continuation.resume(returning: self.nextClear()) }
        }
    }

    /// The clear queue's next reply, unqueued meaning the envelope could not be read.
    private func nextClear() -> Result<AgentCommandReply, APIError> {
        clearReplies.isEmpty ? .failure(.decoding) : clearReplies.removeFirst()
    }

    /// Runs every parked write in the order it went out, each pulling its own next reply.
    func releaseWrites() {
        let waiting = parked
        parked = []
        for resume in waiting { resume() }
    }

    private func write(
        _ queue: ReferenceWritableKeyPath<FakeSessions, [Result<EmptyResponse, APIError>]>
    ) async -> Result<EmptyResponse, APIError> {
        guard gateWrites else { return next(from: queue) }
        return await withCheckedContinuation { continuation in
            parked.append { continuation.resume(returning: self.next(from: queue)) }
        }
    }

    /// An unqueued request is a test bug, and `.decoding` is what the real facade answers with when the
    /// envelope it got cannot be read — loud, and not a silent success.
    private func next(
        from queue: ReferenceWritableKeyPath<FakeSessions, [Result<EmptyResponse, APIError>]>
    ) -> Result<EmptyResponse, APIError> {
        self[keyPath: queue].isEmpty ? .failure(.decoding) : self[keyPath: queue].removeFirst()
    }
}

import Foundation
import HarnaxCore
@testable import HarnaxFeatures

/// The API Key surface, on the reply-queue discipline the other fakes use: a request nobody queued answers
/// as a decoding failure and still shows up in the call log, so one extra round trip reads as a wrong count
/// rather than as a silent pass.
///
/// `gateWrites` is the dial the three paths with something to say *before* the answer need. The status
/// switch publishes an override the moment it goes out, a rotate only publishes its key once the reply
/// lands, and a save has to hold the sheet busy while the server thinks — all of which is a mid-flight
/// window, not a settled state. `releaseWrites()` replays them in order.
///
/// The page read is never gated: a read that hangs proves nothing about the screen.
final class FakeApiKeys: ApiKeyCataloging, @unchecked Sendable {
    private(set) var requests: [(keyword: String?, enabled: Int?, num: Int, size: Int)] = []
    var replies: [Result<Page<ApiKeySummary>, APIError>] = []

    private(set) var createRequests: [ApiKeyDraft] = []
    var createReplies: [Result<ApiKeyCreatedSummary, APIError>] = []

    private(set) var updateRequests: [(id: Int64, change: ApiKeyChange)] = []
    var updateReplies: [Result<EmptyResponse, APIError>] = []

    private(set) var statusRequests: [(id: Int64, enabled: Bool)] = []
    var statusReplies: [Result<EmptyResponse, APIError>] = []

    private(set) var deleteRequests: [Int64] = []
    var deleteReplies: [Result<EmptyResponse, APIError>] = []

    private(set) var regenerateRequests: [Int64] = []
    var regenerateReplies: [Result<ApiKeyCreatedSummary, APIError>] = []

    var gateWrites = false

    private var parked: [() -> Void] = []

    /// The status column goes out as the raw 0/1 it is on the wire, and `all` leaves it off entirely
    /// (`ApiKeyController.kt:36-39`), so the log keeps `Int?` rather than a prettier `Bool`.
    func apiKeyPage(
        keyword: String?,
        enabled: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<ApiKeySummary>, APIError> {
        requests.append((keyword: keyword, enabled: enabled, num: num, size: size))
        return next(from: \.replies)
    }

    func createApiKey(_ draft: ApiKeyDraft) async -> Result<ApiKeyCreatedSummary, APIError> {
        createRequests.append(draft)
        return await gate(\.createReplies)
    }

    func updateApiKey(id: Int64, _ change: ApiKeyChange) async -> Result<EmptyResponse, APIError> {
        updateRequests.append((id: id, change: change))
        return await gate(\.updateReplies)
    }

    func setApiKeyStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> {
        statusRequests.append((id: id, enabled: enabled))
        return await gate(\.statusReplies)
    }

    /// Not gateable: the row leaves the list only after the answer, so there is no mid-flight to look at.
    func deleteApiKey(id: Int64) async -> Result<EmptyResponse, APIError> {
        deleteRequests.append(id)
        return next(from: \.deleteReplies)
    }

    /// The second of the two routes that hand back a raw key, and the only one this screen can reach
    /// (`ApiKeyCataloging.swift:18-28`).
    func regenerateApiKey(id: Int64) async -> Result<ApiKeyCreatedSummary, APIError> {
        regenerateRequests.append(id)
        return await gate(\.regenerateReplies)
    }

    /// Runs every parked write in the order it went out, each pulling its own next reply.
    func releaseWrites() {
        let waiting = parked
        parked = []
        for resume in waiting { resume() }
    }

    private func gate<T>(
        _ queue: ReferenceWritableKeyPath<FakeApiKeys, [Result<T, APIError>]>
    ) async -> Result<T, APIError> {
        guard gateWrites else { return next(from: queue) }
        return await withCheckedContinuation { continuation in
            parked.append { continuation.resume(returning: self.next(from: queue)) }
        }
    }

    /// An unqueued request is a test bug, and `.decoding` is what the real facade answers with when the
    /// envelope it got cannot be read — loud, and not a silent success.
    private func next<T>(
        from queue: ReferenceWritableKeyPath<FakeApiKeys, [Result<T, APIError>]>
    ) -> Result<T, APIError> {
        self[keyPath: queue].isEmpty ? .failure(.decoding) : self[keyPath: queue].removeFirst()
    }
}

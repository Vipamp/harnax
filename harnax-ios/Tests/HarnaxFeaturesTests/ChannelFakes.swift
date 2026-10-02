import Foundation
import HarnaxCore
@testable import HarnaxFeatures

/// The channel screens' stand-in, on the reply-queue discipline the other fakes use: a request nobody queued
/// answers as a decoding failure and still shows up in the call log, so one extra read reads as a wrong count
/// rather than as a silent pass.
///
/// `gateWrites` is the dial the two mid-flight behaviours need: `ChannelListViewModel.setStatus` decides its
/// rollback while the answer is still out, and `ChannelFormViewModel.save` refuses a second tap for the same
/// reason. Delete stays ungated: its row leaves the list only after the answer, so there is no window to look
/// at.
///
/// The three scan calls are a queue of their own. `startWechatLogin` hands back an image and the poll hands
/// back one sentence per call, so a test scripts the whole conversation rather than a single verdict.
final class FakeChannels: ChannelCataloging, @unchecked Sendable {
    /// All four filters, because this route really has them (`ChannelController.kt:34-46`) — a logged
    /// `type` here would be a fiction on the environment-variable-shaped fakes.
    private(set) var requests: [(keyword: String?, type: String?, status: Int?, num: Int, size: Int)] = []
    var replies: [Result<Page<ChannelSummary>, APIError>] = []

    private(set) var createRequests: [ChannelDraft] = []
    var createReplies: [Result<EmptyResponse, APIError>] = []

    private(set) var updateRequests: [(id: Int64, change: ChannelChange)] = []
    var updateReplies: [Result<EmptyResponse, APIError>] = []

    private(set) var statusRequests: [(id: Int64, running: Bool)] = []
    var statusReplies: [Result<EmptyResponse, APIError>] = []

    private(set) var deleteRequests: [Int64] = []
    var deleteReplies: [Result<EmptyResponse, APIError>] = []

    /// The runtime read is keyed by session id, so the ids a page asked for are the assertion.
    private(set) var sandboxRequests: [[String]] = []
    var sandboxReplies: [Result<SandboxStatusMap, APIError>] = []

    private(set) var scanStarts: [Int64] = []
    var scanStartReplies: [Result<WechatQrCode, APIError>] = []

    private(set) var scanPolls = 0
    var scanPollReplies: [Result<WechatLoginUpdate, APIError>] = []

    private(set) var scanCancels: [Int64] = []
    var scanCancelReplies: [Result<EmptyResponse, APIError>] = []

    var gateWrites = false

    private var parked: [() -> Void] = []

    /// The page read is parkable: an append has to be able to stay in flight while the reader changes the
    /// query (`ListAppendIdentityTests`).
    let pageGate = PageReadGate<Result<Page<ChannelSummary>, APIError>>()

    func channelPage(
        keyword: String?, type: String?, status: Int?, num: Int, size: Int
    ) async -> Result<Page<ChannelSummary>, APIError> {
        requests.append((keyword: keyword, type: type, status: status, num: num, size: size))
        return await pageGate.absorb(replies.isEmpty ? .failure(.decoding) : replies.removeFirst())
    }

    func createChannel(_ draft: ChannelDraft) async -> Result<EmptyResponse, APIError> {
        createRequests.append(draft)
        return await write(\.createReplies)
    }

    func updateChannel(id: Int64, _ change: ChannelChange) async -> Result<EmptyResponse, APIError> {
        updateRequests.append((id: id, change: change))
        return await write(\.updateReplies)
    }

    func setChannelStatus(id: Int64, running: Bool) async -> Result<EmptyResponse, APIError> {
        statusRequests.append((id: id, running: running))
        return await write(\.statusReplies)
    }

    func deleteChannel(id: Int64) async -> Result<EmptyResponse, APIError> {
        deleteRequests.append(id)
        return deleteReplies.isEmpty ? .failure(.decoding) : deleteReplies.removeFirst()
    }

    func sandboxStatuses(sessionIds: [String]) async -> Result<SandboxStatusMap, APIError> {
        sandboxRequests.append(sessionIds)
        return sandboxReplies.isEmpty ? .failure(.decoding) : sandboxReplies.removeFirst()
    }

    func startWechatLogin(id: Int64) async -> Result<WechatQrCode, APIError> {
        scanStarts.append(id)
        return scanStartReplies.isEmpty ? .failure(.decoding) : scanStartReplies.removeFirst()
    }

    func wechatLoginStatus(id: Int64) async -> Result<WechatLoginUpdate, APIError> {
        scanPolls += 1
        return scanPollReplies.isEmpty ? .failure(.decoding) : scanPollReplies.removeFirst()
    }

    func cancelWechatLogin(id: Int64) async -> Result<EmptyResponse, APIError> {
        scanCancels.append(id)
        return scanCancelReplies.isEmpty ? .failure(.decoding) : scanCancelReplies.removeFirst()
    }

    /// Runs every parked write in the order it went out, each pulling its own next reply.
    func releaseWrites() {
        let waiting = parked
        parked = []
        for resume in waiting { resume() }
    }

    private func write(
        _ queue: ReferenceWritableKeyPath<FakeChannels, [Result<EmptyResponse, APIError>]>
    ) async -> Result<EmptyResponse, APIError> {
        guard gateWrites else { return next(from: queue) }
        return await withCheckedContinuation { continuation in
            parked.append { continuation.resume(returning: self.next(from: queue)) }
        }
    }

    /// An unqueued request is a test bug, and `.decoding` is what the real facade answers with when the
    /// envelope it got cannot be read — loud, and not a silent success.
    private func next(
        from queue: ReferenceWritableKeyPath<FakeChannels, [Result<EmptyResponse, APIError>]>
    ) -> Result<EmptyResponse, APIError> {
        self[keyPath: queue].isEmpty ? .failure(.decoding) : self[keyPath: queue].removeFirst()
    }
}

extension ChannelSummary {
    static func stub(_ fields: [String: Any]) throws -> ChannelSummary {
        let data = try JSONSerialization.data(withJSONObject: fields)
        return try JSONDecoder().decode(ChannelSummary.self, from: data)
    }
}

/// The poll's payload is decode-only, so a test spells the two wire fields instead of building one.
extension WechatLoginUpdate {
    static func stub(status: String?, message: String? = nil) throws -> WechatLoginUpdate {
        var fields: [String: Any] = [:]
        if let status { fields["status"] = status }
        if let message { fields["message"] = message }
        let data = try JSONSerialization.data(withJSONObject: fields)
        return try JSONDecoder().decode(WechatLoginUpdate.self, from: data)
    }
}

enum QR {
    /// `hello`, which is all the view needs to tell a readable image from an absent one.
    static let dataUrl = "data:image/png;base64,aGVsbG8="
    static var png: Data { Data("hello".utf8) }

    static func code(_ dataUrl: String = QR.dataUrl) -> WechatQrCode {
        WechatQrCode(dataUrl: dataUrl)
    }
}

/// A `workspace/status` answer keyed by session id, in the shape the runtime really sends
/// (`AgentProxyController.kt:217` types each entry `Map<String, Any>`).
enum SandboxStub {
    static func map(_ entries: [String: Bool]) -> SandboxStatusMap {
        SandboxStatusMap(states: entries.mapValues { SandboxState(active: $0) })
    }
}

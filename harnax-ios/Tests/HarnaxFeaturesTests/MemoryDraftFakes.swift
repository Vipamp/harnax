import Foundation
import HarnaxCore
@testable import HarnaxFeatures

/// The memory review queue's double, on the reply-queue discipline the other fakes use: a request nobody queued
/// answers as a decoding failure and still shows up in the call log, so one extra re-read — the thing every
/// branch of `apply(_:)` and every「after a decision the queue re-reads」claim is defined by — reads as a wrong
/// count rather than as a silent pass.
///
/// `gateWrites` is the dial the one-in-flight claim needs: an approval rewrites the owner's whole long-term
/// layer, so `isActing` has to hold the buttons down between the tap and the answer and a second tap must not put
/// a second approve on the wire. `releaseWrites()` replays what is parked, in the order it went out.
///
/// The page read has its own park (`pageGate`), which is what lets a tail page sit on the wire while the reader
/// switches arm. Note that one `refresh()` on this queue takes **two** page replies: the rows, then the
/// queue-size read `MemoryDraftCataloging.pendingCount()` issues as its own size-1 page
/// (`MemoryDraftCataloging.swift:50-56`) — the badge is the queue's, not the filtered page's.
final class FakeMemoryDrafts: MemoryDraftCataloging, @unchecked Sendable {
    /// The arm is recorded as the enum and the term as the optional it goes out as, because both are the claim
    /// under test: the arm is always sent, and an emptied search must leave the key off entirely. `sessionId` is
    /// recorded with them because this queue is the owner's across every conversation and must therefore send
    /// nothing there (`MemoryDraftController.kt:70`).
    private(set) var pageRequests: [
        (status: MemoryDraftStatus, agentName: String?, sessionId: String?, num: Int, size: Int)
    ] = []
    var pageReplies: [Result<Page<MemoryDraftRow>, APIError>] = []
    let pageGate = PageReadGate<Result<Page<MemoryDraftRow>, APIError>>()

    /// The most recent read of the list itself, skipping the badge read that follows it.
    ///
    /// The badge asks for one row of the pending arm whatever the term on screen is
    /// (`MemoryDraftCataloging.swift:50-56`), so `pageRequests.last` is that one and carries no term by design —
    /// asserting the search against it would pass only when the term is absent.
    var lastListRead: (status: MemoryDraftStatus, agentName: String?, sessionId: String?, num: Int, size: Int)? {
        pageRequests.last { $0.size != 1 }
    }

    private(set) var detailRequests: [Int64] = []
    var detailReplies: [Result<MemoryDraftDetail, APIError>] = []

    private(set) var approveRequests: [(id: Int64, payload: MemoryDraftApprovePayload)] = []
    var approveReplies: [Result<MemoryDraftDecision, APIError>] = []

    private(set) var rejectRequests: [(id: Int64, payload: MemoryDraftRejectPayload)] = []
    var rejectReplies: [Result<MemoryDraftDecision, APIError>] = []

    var gateWrites = false

    private var parked: [() -> Void] = []

    func page(
        status: MemoryDraftStatus,
        agentName: String?,
        sessionId: String?,
        num: Int,
        size: Int
    ) async -> Result<Page<MemoryDraftRow>, APIError> {
        pageRequests.append((status: status, agentName: agentName, sessionId: sessionId, num: num, size: size))
        return await pageGate.absorb(next(from: \.pageReplies))
    }

    /// Not gateable: nothing on this screen reads a candidate while another candidate read is already in flight,
    /// and the re-read after a decision is awaited by the decision path itself.
    func detail(id: Int64) async -> Result<MemoryDraftDetail, APIError> {
        detailRequests.append(id)
        return next(from: \.detailReplies)
    }

    func approve(id: Int64, _ payload: MemoryDraftApprovePayload) async -> Result<MemoryDraftDecision, APIError> {
        approveRequests.append((id: id, payload: payload))
        return await gate(\.approveReplies)
    }

    func reject(id: Int64, _ payload: MemoryDraftRejectPayload) async -> Result<MemoryDraftDecision, APIError> {
        rejectRequests.append((id: id, payload: payload))
        return await gate(\.rejectReplies)
    }

    /// Runs every parked write in the order it went out, each pulling its own next reply.
    func releaseWrites() {
        let waiting = parked
        parked = []
        for resume in waiting { resume() }
    }

    private func gate<T>(
        _ queue: ReferenceWritableKeyPath<FakeMemoryDrafts, [Result<T, APIError>]>
    ) async -> Result<T, APIError> {
        guard gateWrites else { return next(from: queue) }
        return await withCheckedContinuation { continuation in
            parked.append { continuation.resume(returning: self.next(from: queue)) }
        }
    }

    private func next<T>(
        from queue: ReferenceWritableKeyPath<FakeMemoryDrafts, [Result<T, APIError>]>
    ) -> Result<T, APIError> {
        self[keyPath: queue].isEmpty ? .failure(.decoding) : self[keyPath: queue].removeFirst()
    }
}

/// Field maps rather than string literals, for the same reason `PageStub` gives: the wire shape is what the
/// decoder is strict about, and a test about a decision branch should not have to spell out twenty keys. Each
/// base carries only the keys the synthesised initialiser cannot leave absent — every other column on
/// `MemoryDraftResponse.kt` is nullable, which is exactly what the strictness claim in the wire suite turns on.
extension MemoryDraftRow {
    static func stub(_ fields: [String: Any] = [:]) throws -> MemoryDraftRow {
        try stubbed([
            "id": 1,
            "agentName": "翻译助手",
            "sessionId": "sess-1",
            "status": MemoryDraftStatus.pending.rawValue,
            "baseVersion": 3,
            "mergedChars": 120,
            "sourceCount": 2,
            "targetCount": 1,
        ], fields)
    }
}

extension MemoryDraftDetail {
    static func stub(_ fields: [String: Any] = [:]) throws -> MemoryDraftDetail {
        try stubbed([
            "id": 1,
            "agentName": "翻译助手",
            "sessionId": "sess-1",
            "status": MemoryDraftStatus.pending.rawValue,
            "mergedMd": "# 用户偏好\n\n中文优先。",
            "baseVersion": 3,
            "sources": [["path": "memory/sess-1.md", "content": "旧的会话记忆"]],
            "targets": [[
                "path": "memory/2026-10-08.md",
                "expectedVersion": 2,
                "baseText": "周一：只在需要时翻译。",
                "mergedText": "周一：只在需要时翻译，中文优先。",
            ]],
            "contentDigest": String(repeating: "a", count: 64),
        ], fields)
    }
}

extension MemoryDraftDecision {
    static func stub(_ fields: [String: Any] = [:]) throws -> MemoryDraftDecision {
        try stubbed([
            "outcome": "APPROVED",
            // The four counters are non-optional on the DTO (`MemoryDraftDecisionResponse.kt:28-42`), so a stub
            // without them would fail to decode — the same strictness the wire enforces.
            "clearedSources": 0,
            "keptSources": 0,
            "absentSources": 0,
            "dailyTargetsApplied": 0,
        ], fields)
    }
}

private func stubbed<T: Decodable>(_ base: [String: Any], _ overrides: [String: Any]) throws -> T {
    var fields = base
    for (key, value) in overrides { fields[key] = value }
    let data = try JSONSerialization.data(withJSONObject: fields)
    return try JSONDecoder().decode(T.self, from: data)
}

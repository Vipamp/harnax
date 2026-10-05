import Foundation
import HarnaxCore
@testable import HarnaxFeatures

/// The review queue's double, on the reply-queue discipline the other fakes use: a request nobody queued
/// answers as a decoding failure and still shows up in the call log, so one extra re-read — the thing every
/// branch of `apply(_:askedName:)` is defined by whether it does — reads as a wrong count rather than as a
/// silent pass.
///
/// `gateWrites` is the dial the one in-flight claim needs: approval is a publish, so `isActing` has to hold the
/// buttons down between the tap and the answer, and a second tap must not put a second approve on the wire.
/// `releaseWrites()` replays what is parked in the order it went out.
///
/// The page read has its own park (`pageGate`), which is what lets an append sit on the wire while the reader
/// switches arm (`ListAppendIdentityTests`).
final class FakeSkillDrafts: SkillDraftCataloging, @unchecked Sendable {
    /// The status is recorded as the enum and the name as the optional it goes out as, because both are the
    /// claim under test: the arm is always sent, and an emptied search must leave the key off entirely.
    private(set) var pageRequests: [(status: SkillDraftStatus, name: String?, num: Int, size: Int)] = []
    var pageReplies: [Result<Page<SkillDraftRow>, APIError>] = []
    let pageGate = PageReadGate<Result<Page<SkillDraftRow>, APIError>>()

    private(set) var detailRequests: [Int64] = []
    var detailReplies: [Result<SkillDraftDetail, APIError>] = []

    private(set) var approveRequests: [(id: Int64, payload: SkillDraftApprovePayload)] = []
    var approveReplies: [Result<SkillDraftDecision, APIError>] = []

    private(set) var rejectRequests: [(id: Int64, payload: SkillDraftRejectPayload)] = []
    var rejectReplies: [Result<SkillDraftDecision, APIError>] = []

    var gateWrites = false

    private var parked: [() -> Void] = []

    func page(
        status: SkillDraftStatus,
        name: String?,
        num: Int,
        size: Int
    ) async -> Result<Page<SkillDraftRow>, APIError> {
        pageRequests.append((status: status, name: name, num: num, size: size))
        return await pageGate.absorb(next(from: \.pageReplies))
    }

    /// Not gateable: nothing on this screen reads the detail while another detail read is already in flight,
    /// and the reload after a decision is awaited by the decision path itself.
    func detail(id: Int64) async -> Result<SkillDraftDetail, APIError> {
        detailRequests.append(id)
        return next(from: \.detailReplies)
    }

    func approve(id: Int64, _ payload: SkillDraftApprovePayload) async -> Result<SkillDraftDecision, APIError> {
        approveRequests.append((id: id, payload: payload))
        return await gate(\.approveReplies)
    }

    func reject(id: Int64, _ payload: SkillDraftRejectPayload) async -> Result<SkillDraftDecision, APIError> {
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
        _ queue: ReferenceWritableKeyPath<FakeSkillDrafts, [Result<T, APIError>]>
    ) async -> Result<T, APIError> {
        guard gateWrites else { return next(from: queue) }
        return await withCheckedContinuation { continuation in
            parked.append { continuation.resume(returning: self.next(from: queue)) }
        }
    }

    private func next<T>(
        from queue: ReferenceWritableKeyPath<FakeSkillDrafts, [Result<T, APIError>]>
    ) -> Result<T, APIError> {
        self[keyPath: queue].isEmpty ? .failure(.decoding) : self[keyPath: queue].removeFirst()
    }
}

/// Field maps rather than string literals, for the same reason `PageStub` gives: the wire shape is what the
/// decoder is strict about, and a test about a decision branch should not have to spell out twenty keys.
/// Each base carries only the keys the synthesised initializer cannot leave absent — every other column on
/// `SkillDraftResponse.kt` is nullable.
extension SkillDraftRow {
    static func stub(_ fields: [String: Any] = [:]) throws -> SkillDraftRow {
        try stubbed([
            "id": 1,
            "name": "周报汇总",
            "status": SkillDraftStatus.pending.rawValue,
            "upstreamFindingCount": 0,
        ], fields)
    }
}

extension SkillDraftDetail {
    static func stub(_ fields: [String: Any] = [:]) throws -> SkillDraftDetail {
        try stubbed([
            "id": 1,
            "name": "周报汇总",
            "status": SkillDraftStatus.pending.rawValue,
            "skillmd": "# 周报汇总\n\n每周一汇总上周的构建失败。",
            "resources": [String: String](),
            "scripts": [Any](),
            "scanFindings": [Any](),
            "localFindings": [Any](),
            "contentDigest": String(repeating: "a", count: 64),
            "history": [Any](),
        ], fields)
    }
}

extension SkillDraftDecision {
    static func stub(_ fields: [String: Any] = [:]) throws -> SkillDraftDecision {
        try stubbed([
            "outcome": "PROMOTED",
            // `findings: [String]` is non-optional on the DTO, so a stub without it would fail to decode —
            // which is the same strictness the wire enforces (`SkillDraftDecisionResponse.kt:26-27`).
            "findings": [Any](),
        ], fields)
    }
}

private func stubbed<T: Decodable>(_ base: [String: Any], _ overrides: [String: Any]) throws -> T {
    var fields = base
    for (key, value) in overrides { fields[key] = value }
    let data = try JSONSerialization.data(withJSONObject: fields)
    return try JSONDecoder().decode(T.self, from: data)
}

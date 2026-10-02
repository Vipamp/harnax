import Foundation
import HarnaxCore
@testable import HarnaxFeatures

/// The skill surface, on the reply-queue discipline the other doubles use: a request nobody queued answers
/// as a decoding failure and still shows up in the call log, so one extra round trip reads as a wrong count
/// rather than as a silent pass.
///
/// `gateWrites` parks the install, which is the only window the two install behaviours live in: the report
/// a source row publishes, and the second tap that must not post the same install twice.
/// `gateRepositoryWrites` is the same window for the repository form's two writes
/// (`createSource` / `updateSource`), and both park in the one slot `releaseWrites()` drains.
final class FakeSkills: SkillCataloging, @unchecked Sendable {
    private(set) var sourceRequests: [(name: String?, status: Int?, num: Int, size: Int)] = []
    var sourceReplies: [Result<Page<SkillSourceSummary>, APIError>] = []

    private(set) var sourceDetailRequests: [Int64] = []
    var sourceDetailReplies: [Result<SkillSourceSummary, APIError>] = []

    private(set) var previewRequests: [Int64] = []
    var previewReplies: [Result<[SkillPreviewItem], APIError>] = []

    private(set) var installRequests: [(sourceID: Int64, names: [String]?)] = []
    var installReplies: [Result<SkillInstallOutcome, APIError>] = []

    private(set) var createRequests: [SkillSourceCreatePayload] = []
    var createReplies: [Result<SkillSourceInstallResult, APIError>] = []

    private(set) var updateRequests: [(id: Int64, patch: SkillSourceUpdatePayload)] = []
    var updateReplies: [Result<EmptyResponse, APIError>] = []

    private(set) var deleteRequests: [Int64] = []
    var deleteReplies: [Result<EmptyResponse, APIError>] = []

    private(set) var statusCalls: [(id: Int64, enabled: Bool)] = []
    var statusReplies: [Result<EmptyResponse, APIError>] = []

    private(set) var uploadRequests: [(name: String, fileName: String, bytes: Int)] = []
    var uploadReplies: [Result<SkillSourceInstallResult, APIError>] = []

    private(set) var skillRequests: [(name: String?, repositoryID: Int64?, status: Int?, num: Int, size: Int)] = []
    var skillPageReplies: [Result<Page<SkillItem>, APIError>] = []

    private(set) var skillDetailRequests: [Int64] = []
    var skillDetailReplies: [Result<SkillItem, APIError>] = []

    private(set) var skillStatusCalls: [(id: Int64, enabled: Bool)] = []
    var skillStatusReplies: [Result<EmptyResponse, APIError>] = []

    var gateWrites = false

    /// The two switches park on their own dial: `gateWrites` belongs to the install, and the re-entry tests
    /// need the switch's own window (`RowWriteReentryTests`).
    var gateStatusWrites = false

    /// Same window again for the repository form's create and update (`SkillRepositoryFormTests`).
    var gateRepositoryWrites = false

    /// Both paged routes are parkable: an append has to be able to sit on the wire while the reader changes
    /// the query (`ListAppendIdentityTests`).
    let sourceGate = PageReadGate<Result<Page<SkillSourceSummary>, APIError>>()
    let skillGate = PageReadGate<Result<Page<SkillItem>, APIError>>()

    private var parked: [() -> Void] = []

    /// Queues the one page answer the tests that only need sources on screen ask for.
    func seedSources(_ rows: [[String: Any]], total: Int? = nil, pageSize: Int = 20) throws {
        sourceReplies = [.success(try PageStub.page(SkillSourceSummary.self, rows, total: total, pageSize: pageSize))]
    }

    func sourcePage(
        name: String?,
        sourceType: String?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<SkillSourceSummary>, APIError> {
        sourceRequests.append((name: name, status: status, num: num, size: size))
        return await sourceGate.absorb(sourceReplies.isEmpty ? .failure(.decoding) : sourceReplies.removeFirst())
    }

    func source(id: Int64) async -> Result<SkillSourceSummary, APIError> {
        sourceDetailRequests.append(id)
        return sourceDetailReplies.isEmpty ? .failure(.decoding) : sourceDetailReplies.removeFirst()
    }

    func preview(sourceID: Int64) async -> Result<[SkillPreviewItem], APIError> {
        previewRequests.append(sourceID)
        return previewReplies.isEmpty ? .failure(.decoding) : previewReplies.removeFirst()
    }

    /// The domain's only parked write: `install` is what both the report and the double tap are about.
    func install(sourceID: Int64, names: [String]?) async -> Result<SkillInstallOutcome, APIError> {
        installRequests.append((sourceID: sourceID, names: names))
        guard gateWrites else { return next(from: \.installReplies) }
        return await withCheckedContinuation { continuation in
            parked.append { continuation.resume(returning: self.next(from: \.installReplies)) }
        }
    }

    /// Both repository writes park on `gateRepositoryWrites`, so a re-entry test can hold one on the wire and
    /// tap the sheet again.
    func createSource(_ payload: SkillSourceCreatePayload) async -> Result<SkillSourceInstallResult, APIError> {
        createRequests.append(payload)
        guard gateRepositoryWrites else { return createNext() }
        return await withCheckedContinuation { continuation in
            parked.append { continuation.resume(returning: self.createNext()) }
        }
    }

    func updateSource(id: Int64, _ payload: SkillSourceUpdatePayload) async -> Result<EmptyResponse, APIError> {
        updateRequests.append((id: id, patch: payload))
        guard gateRepositoryWrites else { return emptyNext(from: \.updateReplies) }
        return await withCheckedContinuation { continuation in
            parked.append { continuation.resume(returning: self.emptyNext(from: \.updateReplies)) }
        }
    }

    func deleteSource(id: Int64) async -> Result<EmptyResponse, APIError> {
        deleteRequests.append(id)
        return deleteReplies.isEmpty ? .failure(.decoding) : deleteReplies.removeFirst()
    }

    func setSourceStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> {
        statusCalls.append((id: id, enabled: enabled))
        return await statusWrite(\.statusReplies)
    }

    func uploadSource(
        name: String,
        fileName: String,
        payload: Data
    ) async -> Result<SkillSourceInstallResult, APIError> {
        uploadRequests.append((name: name, fileName: fileName, bytes: payload.count))
        return uploadReplies.isEmpty ? .failure(.decoding) : uploadReplies.removeFirst()
    }

    func skillPage(
        name: String?,
        repositoryID: Int64?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<SkillItem>, APIError> {
        skillRequests.append((name: name, repositoryID: repositoryID, status: status, num: num, size: size))
        return await skillGate.absorb(skillPageReplies.isEmpty ? .failure(.decoding) : skillPageReplies.removeFirst())
    }

    func skill(id: Int64) async -> Result<SkillItem, APIError> {
        skillDetailRequests.append(id)
        return skillDetailReplies.isEmpty ? .failure(.decoding) : skillDetailReplies.removeFirst()
    }

    func setSkillStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> {
        skillStatusCalls.append((id: id, enabled: enabled))
        return await statusWrite(\.skillStatusReplies)
    }

    /// Runs every parked write in the order it went out, each pulling its own next reply.
    func releaseWrites() {
        let waiting = parked
        parked = []
        for resume in waiting { resume() }
    }

    private func next(from queue: ReferenceWritableKeyPath<FakeSkills, [Result<SkillInstallOutcome, APIError>]>)
        -> Result<SkillInstallOutcome, APIError> {
        self[keyPath: queue].isEmpty ? .failure(.decoding) : self[keyPath: queue].removeFirst()
    }

    /// The create queue shares its answer type with the upload queue but not its replies: an upload test that
    /// queued nothing must not answer a create.
    private func createNext() -> Result<SkillSourceInstallResult, APIError> {
        createReplies.isEmpty ? .failure(.decoding) : createReplies.removeFirst()
    }

    /// A switch parked on `gateStatusWrites` waits in the same slot as the install, so one `releaseWrites()`
    /// hands back whatever the test armed.
    private func statusWrite(
        _ queue: ReferenceWritableKeyPath<FakeSkills, [Result<EmptyResponse, APIError>]>
    ) async -> Result<EmptyResponse, APIError> {
        guard gateStatusWrites else { return emptyNext(from: queue) }
        return await withCheckedContinuation { continuation in
            parked.append { continuation.resume(returning: self.emptyNext(from: queue)) }
        }
    }

    private func emptyNext(
        from queue: ReferenceWritableKeyPath<FakeSkills, [Result<EmptyResponse, APIError>]>
    ) -> Result<EmptyResponse, APIError> {
        self[keyPath: queue].isEmpty ? .failure(.decoding) : self[keyPath: queue].removeFirst()
    }
}

extension SkillSourceSummary {
    static func stub(_ fields: [String: Any]) throws -> SkillSourceSummary {
        let data = try JSONSerialization.data(withJSONObject: fields)
        return try JSONDecoder().decode(SkillSourceSummary.self, from: data)
    }

    /// `SkillSourceResponse.kt` gives every column but the config and the sync block a non-null default, so
    /// a row the stack can answer always carries these ten keys.
    static func stub(
        _ id: Int,
        name: String = "技能源",
        type: String = "GIT",
        enabled: Bool = true,
        enabledSkills: Int? = nil
    ) throws -> SkillSourceSummary {
        var row: [String: Any] = [
            "id": id,
            "name": name,
            "sourceType": type,
            "version": "1.0.0",
            "url": "https://example.com/skills.git",
            "branch": "main",
            "description": "示例来源",
            "status": enabled ? 1 : 0,
            "isPublic": 1,
            "creator": "heqingsong",
            "createTime": "2026-09-01 10:00:00",
            "updateTime": "2026-09-01 10:00:00",
        ]
        if let enabledSkills { row["enabledSkillCount"] = enabledSkills }
        return try stub(row)
    }
}

extension SkillSourceInstallResult {
    /// The create and upload answer (`SkillSourceInstallResponse.kt:12-17`): the row as the server stored it,
    /// plus what the install that ran alongside it did. Decoded rather than constructed because the struct's
    /// synthesised memberwise init is internal, and this is exactly the shape the wire carries. The legacy
    /// `url` / `branch` columns mirror the config the way the service mirrors them
    /// (`SkillSourceServiceImpl.kt:144-148`).
    static func stub(
        name: String,
        type: String = "GIT",
        installed: [String] = [],
        sourceConfig: [String: String]? = nil
    ) throws -> SkillSourceInstallResult {
        var source: [String: Any] = [
            "id": 1,
            "name": name,
            "sourceType": type,
            "version": "",
            "url": sourceConfig?["url"] ?? "",
            "branch": sourceConfig?["branch"] ?? "",
            "description": "",
            "status": 1,
            "isPublic": 0,
            "creator": "heqingsong",
            "createTime": "2026-09-01 10:00:00",
            "updateTime": "2026-09-01 10:00:00",
        ]
        if let sourceConfig { source["sourceConfig"] = sourceConfig }
        let payload: [String: Any] = [
            "source": source,
            "install": [
                "installed": installed,
                "updated": [],
                "failed": [],
                "flagged": [],
                "stale": [],
            ],
        ]
        return try JSONDecoder().decode(
            SkillSourceInstallResult.self,
            from: JSONSerialization.data(withJSONObject: payload)
        )
    }
}

extension SkillItem {
    static func stub(_ fields: [String: Any]) throws -> SkillItem {
        let data = try JSONSerialization.data(withJSONObject: fields)
        return try JSONDecoder().decode(SkillItem.self, from: data)
    }

    /// The two binding counters are the only keys `SkillResponse.kt` guarantees, so they are always written
    /// and `id` is passed as `Any?` — a row the server left without an address is a real wire shape.
    static func stub(
        _ id: Any?,
        name: String? = "技能",
        agents: Int = 0,
        teams: Int = 0
    ) throws -> SkillItem {
        var row: [String: Any] = ["boundAgentCount": agents, "boundTeamCount": teams]
        if let id { row["id"] = id }
        if let name { row["name"] = name }
        return try stub(row)
    }
}

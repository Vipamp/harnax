import Foundation
import HarnaxCore
@testable import HarnaxFeatures

/// 技能监控's stand-in, on the reply-queue discipline the other fakes use: a request nobody queued answers as a
/// decoding failure and still shows up in the call log, so a second read for one window reads as a wrong count
/// rather than as a silent pass.
///
/// `gate` exists for the one race this screen has. The window is the route's only parameter
/// (`SkillUsageController.kt:36-39`), so the case worth holding open is a 90-day answer still in flight when the
/// reader switches to 7 days — the superseded reply must take none of the rows, the five numbers or the window
/// echo with it.
final class FakeSkillUsage: SkillUsageReading, @unchecked Sendable {
    /// The `days` each request named, in order.
    private(set) var requests: [Int] = []
    var replies: [Result<SkillUsageSummary, APIError>] = []

    var gate = false

    private var parked: [() -> Void] = []

    func summary(days: Int) async -> Result<SkillUsageSummary, APIError> {
        requests.append(days)
        guard gate else { return next() }
        return await withCheckedContinuation { continuation in
            parked.append { continuation.resume(returning: self.next()) }
        }
    }

    /// Lets every held reply land, in the order it went out.
    func release() {
        let waiting = parked
        parked = []
        for resume in waiting { resume() }
    }

    private func next() -> Result<SkillUsageSummary, APIError> {
        replies.isEmpty ? .failure(.decoding) : replies.removeFirst()
    }
}

/// The summary fixtures.
///
/// Both contract types are `Decodable` with no memberwise initialiser on purpose — a row is what the stack
/// answered and nothing else — so a fixture is built by decoding the keys the DTO declares
/// (`SkillUsageSummaryResponse.kt:16-72`), the way `EnvVarFakes.swift:100-107` does for its projection. The
/// point of going through the decoder is that a test cannot invent a field the server does not send, which is
/// exactly the trap `usedSkillCount` would fall into.
extension SkillUsageSummary {
    static func stub(
        days: Int = 30,
        // The wire form of a `LocalDateTime`, which is ISO on this route (`SkillUsageSummaryResponse.kt:21`,
        // `JacksonConfig.kt:13`) rather than the mask `application.yml:23` sets for `java.util.Date`.
        since: String? = "2026-09-05T09:00:00",
        totalSkills: Int = 0,
        zeroUseCount: Int = 0,
        totalViews: Int = 0,
        totalUses: Int = 0,
        rows: [SkillUsageRow] = [],
        extraFields: [String: Any] = [:]
    ) throws -> SkillUsageSummary {
        var payload: [String: Any] = [
            "days": days,
            "since": since as Any,
            "totalSkills": totalSkills,
            "zeroUseCount": zeroUseCount,
            "totalViews": totalViews,
            "totalUses": totalUses,
            "rows": rows.map { $0.stubPayload },
        ]
        for (key, value) in extraFields { payload[key] = value }
        return try JSONDecoder().decode(SkillUsageSummary.self, from: jsonData(payload))
    }
}

extension SkillUsageRow {
    /// A row nobody loaded and nobody followed — the row this page exists to show
    /// (`SkillUsage.swift:51-52`). It carries no stamp either: with no event in the window, `lastUsedAt`
    /// is the column the server leaves null (`SkillUsageSummaryResponse.kt:69-70`).
    static func unused(id: Int64, name: String = "unused-skill") throws -> SkillUsageRow {
        try stub(id: id, name: name, lastUsedAt: nil)
    }

    static func stub(
        id: Int64,
        name: String = "skill",
        repositoryId: Int64 = 1,
        repositoryName: String? = "qoder-skills",
        status: Int = 1,
        origin: String = "human",
        views: Int = 0,
        uses: Int = 0,
        lastUsedAt: String? = "2026-09-20T10:00:00"
    ) throws -> SkillUsageRow {
        try JSONDecoder().decode(SkillUsageRow.self, from: jsonData(payload(
            id: id,
            name: name,
            repositoryId: repositoryId,
            repositoryName: repositoryName,
            status: status,
            origin: origin,
            views: views,
            uses: uses,
            lastUsedAt: lastUsedAt
        )))
    }

    fileprivate var stubPayload: [String: Any] {
        Self.payload(
            id: skillId,
            name: name,
            repositoryId: repositoryId,
            repositoryName: repositoryName,
            status: status,
            origin: origin,
            views: viewCount,
            uses: useCount,
            lastUsedAt: lastUsedAt
        )
    }

    private static func payload(
        id: Int64,
        name: String,
        repositoryId: Int64,
        repositoryName: String?,
        status: Int,
        origin: String,
        views: Int,
        uses: Int,
        lastUsedAt: String?
    ) -> [String: Any] {
        [
            "skillId": id,
            "name": name,
            "repositoryId": repositoryId,
            "repositoryName": repositoryName as Any,
            "status": status,
            "origin": origin,
            "viewCount": views,
            "useCount": uses,
            "lastUsedAt": lastUsedAt as Any,
        ]
    }
}

/// The one JSON writer the fixtures share, the same as `EnvVarFakes.swift:104-105`.
private func jsonData(_ fields: [String: Any]) -> Data {
    try! JSONSerialization.data(withJSONObject: fields)
}

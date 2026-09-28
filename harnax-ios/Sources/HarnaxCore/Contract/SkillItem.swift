import Foundation

/// One row of `GET /api/admin/skills/page` and the body of `GET /api/admin/skills/{id}`
/// (`SkillResponse.kt:12-44`).
///
/// This DTO is the opposite shape from `SkillSourceResponse`: all but the two binding counters are
/// `T? = null`, so every one of those keys can be absent on the wire and is optional here.
public struct SkillItem: Decodable, Identifiable, Equatable, Sendable {
    public let id: Int64?
    public let name: String?
    public let repositoryId: Int64?
    public let repositoryName: String?
    public let repositoryUrl: String?
    public let repositoryBranch: String?
    public let description: String?
    /// The `skill.md` body, as stored. Null on the paged endpoint, which does not select it; the detail
    /// read does (`SkillController.kt:35-70`).
    public let skillmd: String?
    /// A JSON *string* of `path -> content`, not an object: the column is stored as text and handed out
    /// verbatim, so the caller parses it (`harnax-webui/src/pages/skill/detail.tsx:192-224`).
    public let resources: String?
    public let status: Int?
    /// Both counters carry a non-null default in the DTO, so they always arrive — and they are the whole
    /// delete/disable gate.
    public let boundAgentCount: Int
    public let boundTeamCount: Int
    public let isPublic: Int?
    public let creator: String?
    /// Serialised `LocalDateTime` in the server's `yyyy-MM-dd HH:mm:ss` pattern
    /// (`harnax-admin/src/main/resources/application.yml:23`).
    public let createTime: String?
    public let updateTime: String?

    /// A row with no status column reads as enabled, matching the source row's rule.
    public var isEnabled: Bool { status != 0 }
    public var isShared: Bool { isPublic == 1 }
    public var title: String? { hxPresented(name) }
    public var detail: String? { hxPresented(description) }
    public var source: String? { hxPresented(repositoryName) }

    /// "A bound skill can be neither disabled nor deleted from here" (`SkillResponse.kt:33-36`). The
    /// guard is the counts themselves, not a server round trip, so the row can say why it is inert before
    /// anything is tapped.
    public var isBound: Bool { boundAgentCount > 0 || boundTeamCount > 0 }
    public var bindings: Int { boundAgentCount + boundTeamCount }

    /// The flat `path -> content` map behind the file tree. Unparseable or absent resources read as
    /// "this skill has no resource files" — the two are indistinguishable on the wire, and the detail
    /// screen shows the markdown tab either way.
    public var resourceFiles: [String: String] {
        guard let raw = hxPresented(resources), let data = raw.data(using: .utf8) else { return [:] }
        return (try? JSONDecoder().decode([String: String].self, from: data)) ?? [:]
    }
}

/// `SyncSkillResponse` — one entry of the `GET /skill-sources/{id}/fetch` preview
/// (`SyncSkillResponse.kt:9-18`). Nothing is stored yet: `exists` is what tells a candidate *new* from a
/// candidate that will *overwrite* a row already on file (`SyncSkillModal.tsx:91-144`).
public struct SkillPreviewItem: Decodable, Equatable, Identifiable, Sendable {
    public let name: String?
    public let description: String?
    public let skillmd: String?
    /// Here the map really is an object, unlike `SkillItem.resources`.
    public let resources: [String: String]
    public let exists: Bool

    public var id: String { name ?? "" }
    public var title: String? { hxPresented(name) }
    public var detail: String? { hxPresented(description) }
    public var resourceCount: Int { resources.count }
}

/// `SkillInstallResponse.failed[]` (`SkillInstallResponse.kt:53-60`).
public struct SkillInstallFailure: Decodable, Equatable, Sendable {
    public let name: String
    public let reason: String
}

/// `SkillInstallResponse.flagged[]` — `reasons` is a **list**, not a single sentence
/// (`SkillInstallResponse.kt:62-68`); one scan can flag the same file on several counts.
public struct SkillInstallFlag: Decodable, Equatable, Sendable {
    public let name: String
    public let reasons: [String]
}

/// Body of `POST /skill-sources/{id}/install`, and the `install` half of a create/upload answer
/// (`SkillInstallResponse.kt:12-33`).
///
/// A `200` here does **not** mean everything landed: the buckets exist precisely because a partial failure
/// used to disappear into a log line. Every decoded list keeps its non-null default, so an absent key is a
/// contract break rather than something to paper over.
///
/// The server's own `savedCount` / `failedCount` / `complete` / `summary` are derived getters
/// (`SkillInstallResponse.kt:34-51`); iOS recomputes the two counters from the lists instead of trusting a
/// second, redundant copy, and never shows `summary` because it is hard-coded English.
public struct SkillInstallOutcome: Decodable, Equatable, Sendable {
    public let installed: [String]
    public let updated: [String]
    public let failed: [SkillInstallFailure]
    public let flagged: [SkillInstallFlag]
    public let sourceError: String?
    public let emptyReason: String?
    /// Stored rows the source no longer holds. Reported only — deleting them would take the agent bindings
    /// down with them (`SkillInstallResponse.kt:31-32`).
    public let stale: [String]

    /// Explicit because the synthesised memberwise init is internal, and both the report reader and the
    /// test fakes build outcomes by hand.
    public init(
        installed: [String] = [],
        updated: [String] = [],
        failed: [SkillInstallFailure] = [],
        flagged: [SkillInstallFlag] = [],
        sourceError: String? = nil,
        emptyReason: String? = nil,
        stale: [String] = []
    ) {
        self.installed = installed
        self.updated = updated
        self.failed = failed
        self.flagged = flagged
        self.sourceError = sourceError
        self.emptyReason = emptyReason
        self.stale = stale
    }

    public var savedCount: Int { installed.count + updated.count }
    public var failedCount: Int { failed.count }
    public var isComplete: Bool { failed.isEmpty && sourceError == nil }
    public static let none = SkillInstallOutcome(
        installed: [],
        updated: [],
        failed: [],
        flagged: [],
        sourceError: nil,
        emptyReason: nil,
        stale: []
    )
}

/// `SkillSourceInstallResponse` — the source plus what the install that ran alongside it did
/// (`SkillSourceInstallResponse.kt:12-17`). Both create and ZIP upload answer this.
public struct SkillSourceInstallResult: Decodable, Equatable, Sendable {
    public let source: SkillSourceSummary
    public let install: SkillInstallOutcome
}

/// Body of `POST /api/admin/skill-sources` (`SkillSourceCreateRequest.kt:7-41`).
///
/// `sourceConfig` is the live carrier and `url` / `branch` are the legacy columns the DTO keeps for
/// backward compatibility; the web console writes both (`RepositoryForm.tsx:85-95`).
public struct SkillSourceCreatePayload: Encodable, Sendable {
    public let name: String
    public let sourceType: String
    public let sourceConfig: [String: String]
    public var version: String?
    public var description: String?
    public var status: Int?
    public var isPublic: Int?
    public var url: String?
    public var branch: String?

    public init(
        name: String,
        sourceType: String,
        sourceConfig: [String: String],
        version: String? = nil,
        description: String? = nil,
        status: Int? = nil,
        isPublic: Int? = nil,
        url: String? = nil,
        branch: String? = nil
    ) {
        self.name = name
        self.sourceType = sourceType
        self.sourceConfig = sourceConfig
        self.version = version
        self.description = description
        self.status = status
        self.isPublic = isPublic
        self.url = url
        self.branch = branch
    }
}

/// Body of `PUT /api/admin/skill-sources/{id}` (`SkillSourceUpdateRequest.kt:6-37`).
///
/// Every field is optional and "absent means unchanged", which is why there is no `sourceType` here at all:
/// a source's type cannot be switched after the fact. Swift's synthesised encoder already drops nil
/// optionals, so no `encodeIfPresent` plumbing is needed.
public struct SkillSourceUpdatePayload: Encodable, Equatable, Sendable {
    public var name: String?
    public var sourceConfig: [String: String]?
    public var version: String?
    public var description: String?
    public var status: Int?
    public var isPublic: Int?
    public var url: String?
    public var branch: String?

    public init(
        name: String? = nil,
        sourceConfig: [String: String]? = nil,
        version: String? = nil,
        description: String? = nil,
        status: Int? = nil,
        isPublic: Int? = nil,
        url: String? = nil,
        branch: String? = nil
    ) {
        self.name = name
        self.sourceConfig = sourceConfig
        self.version = version
        self.description = description
        self.status = status
        self.isPublic = isPublic
        self.url = url
        self.branch = branch
    }
}

/// Body of `POST /api/admin/skill-sources/{id}/install` (`SkillSourceInstallRequest.kt:13-15`).
///
/// The distinction is load-bearing: an *absent* `names` means "store the whole source", an *empty* array
/// means "the caller had nothing to store".
public struct SkillInstallPayload: Encodable, Equatable, Sendable {
    public let names: [String]?

    public init(names: [String]?) {
        self.names = names
    }
}

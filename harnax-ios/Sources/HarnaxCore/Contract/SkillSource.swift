import Foundation

/// Which transport a skill source reads from.
///
/// The management form offers GIT / NPM / ZIP (`RepositoryForm.tsx:207-209`); ZIP is the one type whose
/// create path uploads the archive instead of naming a remote. `BUILTIN` is seeded with the platform
/// (`V1__init_schema.sql:744-749`), has no remote at all, and never appears in the form —
/// `SkillSourcePolicy.requireRefreshable` refuses to refresh both ZIP and BUILTIN. The case is decoded by
/// raw string so a type the app does not know about still renders as a row rather than failing the list.
public enum SkillSourceType: Sendable, Equatable {
    case git
    case npm
    case zip
    case builtin
    /// Anything the server knows that this build does not. A future source type must not blank the row.
    case unknown(String)

    /// The wire column, for a request body that has to name the type back.
    public var rawValue: String {
        switch self {
        case .git: "GIT"
        case .npm: "NPM"
        case .zip: "ZIP"
        case .builtin: "BUILTIN"
        case let .unknown(other): other
        }
    }

    public init(raw: String?) {
        switch raw?.uppercased() {
        case "GIT": self = .git
        case "NPM": self = .npm
        case "ZIP": self = .zip
        case "BUILTIN": self = .builtin
        case .some(let other): self = .unknown(other)
        case nil: self = .unknown("")
        }
    }

    /// The three types a form may name. BUILTIN is platform-owned, so it is never a choice.
    public static var formOptions: [SkillSourceType] { [.git, .npm, .zip] }

    /// `true` for GIT and NPM only: the install endpoint has nothing to read behind the other two.
    public var isRefreshable: Bool { self == .git || self == .npm }

    public var isBuiltin: Bool { self == .builtin }
}

/// `SkillSourceResponse.sourceConfig` — a projection of the stored JSON with the server-only keys cut
/// (`SkillSourceConfigs.forApi` drops `zipPath`). Every key is optional because each source type stores a
/// different set: GIT writes `url`/`branch`, NPM writes `packageName`/`registry`, and the upload path adds
/// `originalFilename` on top (`RepositoryForm.tsx:85-95`, `RepositoryList.tsx:417-425`).
///
/// The wire type is `Map<String, Any>` (`SkillSourceResponse.kt:20`) and `forApi` copies the stored object
/// out unchanged, so a key this app knows may still carry a non-text value: `SkillSourceCreateRequest
/// .sourceConfig` takes any JSON an API caller sends (`SkillSourceCreateRequest.kt:19`) and nothing on the
/// way in narrows it. Reading that as a decode failure would blank every row in the list for one bad value
/// in one config, so each key is read the way the server's own loaders read it — `as? String`, which yields
/// nothing for a non-text value (`GitSkillLoader.kt:64-65`, `NpmSkillLoader.kt:43-45`).
public struct SkillSourceConfig: Decodable, Equatable, Sendable {
    public let url: String?
    public let branch: String?
    public let packageName: String?
    public let registry: String?
    public let originalFilename: String?

    public init(
        url: String? = nil,
        branch: String? = nil,
        packageName: String? = nil,
        registry: String? = nil,
        originalFilename: String? = nil
    ) {
        self.url = url
        self.branch = branch
        self.packageName = packageName
        self.registry = registry
        self.originalFilename = originalFilename
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        url = container.hxText(.url)
        branch = container.hxText(.branch)
        packageName = container.hxText(.packageName)
        registry = container.hxText(.registry)
        originalFilename = container.hxText(.originalFilename)
    }

    private enum CodingKeys: String, CodingKey {
        case url, branch, packageName, registry, originalFilename
    }
}

/// A text column, or nothing when the value under that key is not text.
///
/// Deliberately not `try? decodeIfPresent([String: Any])`-style coercion: `12` rendered as `"12"` would put
/// an address on the screen that nobody configured.
private extension KeyedDecodingContainer {
    func hxText(_ key: Key) -> String? {
        (try? decodeIfPresent(String.self, forKey: key)) ?? nil
    }
}

/// `SkillSourceResponse.lastSyncDetail` — the report `SkillSyncRecorder.detailJson` wrote during the last
/// install (`SkillSyncRecorder.kt:52-64`). Note the two renames against the install response: the counter is
/// `saved` rather than `savedCount`, and `sourceError`/`emptyReason` have been folded into one `error`.
///
/// All fields are optional on purpose: the recorder only started writing `stale` and `flagged` with this
/// build, and rows synced before it carry fewer keys.
public struct SkillSyncDetail: Decodable, Equatable, Sendable {
    public let saved: Int?
    public let installed: [String]?
    public let updated: [String]?
    public let failed: [SkillInstallFailure]?
    public let flagged: [SkillInstallFlag]?
    public let stale: [String]?
    public let error: String?
}

/// One row of `GET /api/admin/skill-sources/page` (`SkillSourceResponse.kt:9-62`).
///
/// Unlike `AgentResponse`, this DTO gives every column a non-null default, so the shape is the opposite of
/// the agent rows: only `sourceConfig`, the three `lastSync*` columns and `enabledSkillCount` can be absent.
public struct SkillSourceSummary: Decodable, Identifiable, Equatable, Sendable {
    /// Non-optional because the DTO declares `val id: Long = 0` — an absent key is a contract break, not a
    /// nullable column.
    public let id: Int64
    public let name: String
    public let sourceType: String
    public let sourceConfig: SkillSourceConfig?
    public let version: String
    public let url: String
    public let branch: String
    public let description: String
    public let status: Int
    public let isPublic: Int
    public let creator: String
    /// Kept as the server wrote it. These are `String`s here, not `LocalDateTime`s: `fromEntity` calls
    /// `entity.createTime.toString()` (`SkillSourceResponse.kt:80-81`), so a source row arrives in the ISO
    /// form with a `T` and with the seconds cut when they are zero (`2026-09-20T16:42`), while the Jackson
    /// `yyyy-MM-dd HH:mm:ss` pattern (`application.yml:23`) only applies to the skill rows. One presenter
    /// for both has to split on either separator.
    public let createTime: String
    public let updateTime: String
    public let lastSyncStatus: String?
    public let lastSyncTime: String?
    public let lastSyncDetail: SkillSyncDetail?
    /// Absent on `GET /skill-sources/active`, which never looks it up (`SkillSourceResponse.kt:58-62`).
    public let enabledSkillCount: Int?

    public var type: SkillSourceType { SkillSourceType(raw: sourceType) }
    public var isEnabled: Bool { status != 0 }
    public var isShared: Bool { isPublic == 1 }
    public var title: String { hxPresented(name) ?? "" }

    /// The seeded, platform-owned row the console offers no write on, sync and delete included
    /// (`harnax-webui/src/pages/skill/components/RepositoryList.tsx:384,438`).
    ///
    /// Two keys, because the console tests the name while the seed writes the type: a row re-tagged by hand
    /// stays read-only there, and a builtin row under another name stays read-only here.
    public var isPlatformOwned: Bool { type.isBuiltin || name == Self.builtinRepositoryName }

    /// `BUILTIN_CLI_SKILL_REPO` (`harnax-webui/src/constants/builtinRepository.ts:9`, `BuiltinRepository.kt:17`).
    public static let builtinRepositoryName = "builtin-cli-skills"

    /// The address line under the name: `sourceConfig` is the live carrier and the plain `url` / `branch`
    /// columns are only the legacy fallback, which is why the config wins (`SkillSourceConfigs.kt:22-35`).
    public var endpoint: String? {
        switch type {
        case .git, .builtin, .unknown:
            return hxPresented(sourceConfig?.url) ?? hxPresented(url)
        case .npm:
            return hxPresented(sourceConfig?.packageName)
        case .zip:
            return hxPresented(sourceConfig?.originalFilename)
        }
    }

    /// GIT shows the branch next to the address; the other types have nothing of the sort to say
    /// (`RepositoryList.tsx:394-426`).
    public var revision: String? {
        guard type == .git else { return nil }
        return hxPresented(sourceConfig?.branch) ?? hxPresented(branch)
    }

    /// Rows still enabled under this source. The delete gate needs it, and an absent count is not a zero:
    /// the picker endpoint does not look it up.
    public var enabledSkills: Int? { enabledSkillCount }

    /// The one name a sync report can still say something with.
    public var lastSyncError: String? { hxPresented(lastSyncDetail?.error) }
}

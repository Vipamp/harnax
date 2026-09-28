import Foundation

/// One registered CLI plugin package — the row of `GET /api/admin/clis/page` *and* the payload of
/// `GET /api/admin/clis/{id}`.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/CliResponse.kt:21-49` declares a
/// single DTO for both shapes and every field `Long?`/`String?`/`List?`, so any key may arrive as `null`
/// or — because the stack serialises with `default-property-inclusion: non_null` — not at all.
/// `Identifiable.ID` is therefore `Int64?`, the same rule the agent and team rows follow.
///
/// The two endpoints differ in what they fill, not in type: `payloadDigest`, `depsApt` and `runtimeEnv`
/// are only set by `convertToDetailResponse`
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/CliController.kt:59-66`), so a page
/// row carries no key for them and the detail read is the only way to see them. They stay optional here
/// rather than splitting into a second type, because the server really does answer one DTO.
///
/// There is no `creator` and no `isPublic` on this DTO, so no row-level permission gate is possible: the
/// page is readable and switchable for any signed-in account (design D9/D10).
public struct CliSummary: Decodable, Identifiable, Equatable, Sendable {
    public let id: Int64?
    public let name: String?
    public let description: String?
    public let version: String?
    public let checkCommand: String?
    /// sha256 of the registered archive — the package's identity.
    public let packageDigest: String?
    /// Canonical sha256 of payload plus deps. The console labels this 'Image Fingerprint'.
    public let payloadDigest: String?
    public let envParams: [EnvParamEntry]?
    public let depsApt: [String]?
    public let runtimeEnv: [String: String]?
    public let skill: CliSkill?
    public let status: Int?
    public let createTime: String?
    public let updateTime: String?

    /// A row with no status column reads as enabled: the table defaults `status` to `1`
    /// (`harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:211`) and the console switches on
    /// `status ?? 1` (`harnax-webui/src/pages/cli/index.tsx:267-275`).
    public var isEnabled: Bool { status != 0 }
    public var title: String? { hxPresented(name) }

    /// The declarations travel on the page row too, so the count chip costs no second request.
    public var envParamEntries: [EnvParamEntry] { envParams ?? [] }
    public var aptDependencies: [String] { depsApt ?? [] }

    /// The `SKILL.md` that came inside the package — the only skill a CLI ever has. A row whose skill was
    /// deleted answers no `skill` key at all, which is why the whole element is optional.
    public var shippedSkillName: String? { hxPresented(skill?.skillName) }
    public var shippedSkillId: Int64? { skill?.skillId }

    /// The console cuts the digest at 12 characters and leaves the full value to a tooltip
    /// (`harnax-webui/src/pages/cli/index.tsx:255-263`). A row with no digest says so rather than showing
    /// an empty code chip.
    public var packageDigestAbbrev: String? { Self.abbreviate(packageDigest) }
    public var payloadDigestAbbrev: String? { Self.abbreviate(payloadDigest) }

    /// Sorted by key: the map arrives unordered, and a section that reorders between two renders of the
    /// same drawer would look like the package had changed.
    public var runtimeEnvEntries: [(key: String, value: String)] {
        (runtimeEnv ?? [:]).keys.sorted().map { key in (key, runtimeEnv?[key] ?? "") }
    }

    /// First 12 characters with an ellipsis; short values are already readable whole.
    static func abbreviate(_ digest: String?) -> String? {
        guard let digest = hxPresented(digest) else { return nil }
        return digest.count > 12 ? "\(digest.prefix(12))…" : digest
    }
}

/// `skill` of `CliResponse` (`CliResponse.kt:51-59`) — the triple the registrar filled from the skill row
/// it created for the `SKILL.md` inside the package.
public struct CliSkill: Decodable, Equatable, Sendable {
    public let skillId: Int64?
    public let skillName: String?
    public let skillDescription: String?

    public var name: String? { hxPresented(skillName) }
    public var detail: String? { hxPresented(skillDescription) }
}

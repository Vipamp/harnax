import Foundation

/// Usage of this tenant's skills over one reporting window: `GET /api/admin/skill-usage/summary`.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SkillUsageSummaryResponse.kt:16-72`.
///
/// `rows` includes the skills with **no events at all** (`:6-14`, `:23-24`). That is the whole reason this page
/// exists — the question is which skill never gets loaded, and an aggregate grouped over the event stream
/// would omit those rows, leaving an operator unable to tell「unused」from「unknown」. A client that filters or
/// sorts must not drop the zero rows by accident.
public struct SkillUsageSummary: Decodable, Equatable, Sendable {
    /// Window length the counts were taken over.
    public let days: Int
    /// Start of the window, ISO form from the server; the label above the table.
    public let since: String?
    /// Skills the caller's tenant can see, used or not.
    public let totalSkills: Int
    /// Of those, how many had neither a load nor a use inside the window.
    public let zeroUseCount: Int
    public let totalViews: Int
    public let totalUses: Int
    /// Every skill with its counts, most-loaded first.
    public let rows: [SkillUsageRow]

    /// The share of the tenant's skills that got loaded at least once — the number the console puts next to
    /// the zero count, derived here so the screen does not re-divide by a total that can be zero.
    public var usedSkillCount: Int { totalSkills - zeroUseCount }
}

/// One skill inside that window (`SkillUsageSummaryResponse.kt:44-71`).
public struct SkillUsageRow: Decodable, Identifiable, Equatable, Sendable {
    public let skillId: Int64
    public let name: String
    public let repositoryId: Int64
    public let repositoryName: String?
    /// `1` enabled, `0` disabled — the 0/1 column convention, not a boolean.
    public let status: Int
    /// Provenance: `human` or `agent_promoted` (`Skill.ORIGIN_HUMAN` / `ORIGIN_AGENT_PROMOTED`). Kept as text so
    /// an unrecognised origin still renders; `SkillDraftRules.agentPromotedOrigin` is the one value that means
    /// an agent wrote it.
    public let origin: String
    /// Times loaded into a context within the window.
    public let viewCount: Int
    /// Times its instructions were actually followed within the window.
    public let useCount: Int
    /// Most recent event of either kind within the window; nil when the window holds none.
    public let lastUsedAt: String?

    public var id: Int64 { skillId }
    public var isEnabled: Bool { status == 1 }
    /// The row the「只看没用过的」filter keeps: loaded never and followed never, together rather than either.
    public var isUnused: Bool { viewCount == 0 && useCount == 0 }
    public var isAgentPromoted: Bool { origin == SkillDraftRules.agentPromotedOrigin }
}

/// The skill-usage read. One route, read-only, and the tenant is resolved from the caller's own credentials —
/// there is no `tenantId` parameter to send (`SkillUsageController.kt:20-24,36-39`).
public protocol SkillUsageReading: Sendable {
    /// Per-skill load and use counts for this tenant inside a `days`-wide window.
    ///
    /// The server's own default is 30 (`SkillUsageController.kt:46`), and the console picks 7/30/90 the same way
    /// (`harnax-webui/src/pages/skill/usage.tsx:23-25`), so a caller always sends a number it chose.
    func summary(days: Int) async -> Result<SkillUsageSummary, APIError>
}

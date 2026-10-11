import Foundation
import HarnaxCore
import HarnaxKit

/// The words and figures this screen prints, so its body says nothing but shape.
///
/// One headline number, in the console's card order with the contract's complement appended last. Nothing here
/// re-sorts a reply and almost nothing here re-derives one: the row set is the server's row set in the server's
/// order (`harnax-webui/src/pages/skill/usage.tsx:46-49` hands `summary.rows` straight to its table), and the
/// only number this screen adds is `SkillUsageSummary.usedSkillCount`, which the contract computes from the two
/// counts it was given (`Sources/HarnaxCore/Contract/SkillUsage.swift:25-27`).
public struct SkillUsageMetric: Identifiable, Equatable, Sendable {
    public enum Kind: String, CaseIterable, Equatable, Sendable {
        case skills, loads, uses, unused, used

        public var titleKey: String {
            switch self {
            case .skills: return "skill.usage.metric.skills"
            case .loads: return "skill.usage.metric.loads"
            case .uses: return "skill.usage.metric.uses"
            case .unused: return "skill.usage.metric.unused"
            case .used: return "skill.usage.metric.used"
            }
        }

        /// The console's tooltip, and only where it has one (`usage.tsx:61`, `:67`, `:73`). The two counts a
        /// reader cannot mix up — the tenant's skill total and the derived complement — are left unexplained
        /// rather than given a sentence invented here.
        public var captionKey: String? {
            switch self {
            case .skills, .used: return nil
            case .loads: return "skill.usage.hint.loads"
            case .uses: return "skill.usage.hint.uses"
            case .unused: return "skill.usage.hint.unused"
            }
        }

        /// The console's four card hues (`usage.tsx:55`, `:60`, `:66`, `:72`), read as palette slots because
        /// this app has no literal colours outside the palette table.
        public var tone: PaletteSlot {
            switch self {
            case .skills: return .brand
            case .loads: return .teal
            case .uses: return .success
            case .unused: return .warning
            case .used: return .indigo
            }
        }
    }

    public let kind: Kind
    /// A bare integer as text. The console feeds `Statistic.value` the raw column and groups nothing
    /// (`usage.tsx:51-75`, `:119-130`), so `1200` reads the same here and there.
    public let value: String

    public var id: Kind { kind }

    public init(kind: Kind, value: Int) {
        self.kind = kind
        self.value = String(value)
    }

    public var titleKey: String { kind.titleKey }

    public var captionKey: String? { kind.captionKey }

    public var tone: PaletteSlot { kind.tone }
}

/// The two pills that say what a row *is* — the status the operator set, and who wrote the skill.
///
/// The status words are the app's own 0/1 column convention (`state.badge.*`, as `ApiKeyListView.swift:238`
/// draws it), and the provenance words are the ones `SkillTableView.swift:167-169` already prints, so a skill
/// reads the same in the list and in the monitor.
public enum SkillUsageBadge: String, CaseIterable, Identifiable, Equatable, Sendable {
    case enabled, disabled, agentPromoted, human

    public var id: String { rawValue }

    public var titleKey: String {
        switch self {
        case .enabled: return "state.badge.enabled"
        case .disabled: return "state.badge.disabled"
        case .agentPromoted: return "skill.origin.agent"
        case .human: return "skill.origin.human"
        }
    }

    public var tone: PaletteSlot {
        switch self {
        case .enabled: return .success
        case .disabled: return .textTertiary
        case .agentPromoted: return .purple
        case .human: return .textSecondary
        }
    }

    /// Status first, then provenance.
    ///
    /// Both pills always exist here, which is not true of the skill list: `origin` on this payload is not
    /// nullable and the DTO's default is `Skill.ORIGIN_HUMAN` (`SkillUsageSummaryResponse.kt:60-61`), so a row
    /// always says who wrote it, while a list row may carry no `origin` at all and must not claim a human
    /// installed it (`SkillTableView.swift:163-169`).
    public static func make(for row: SkillUsageRow) -> [SkillUsageBadge] {
        [
            row.isEnabled ? .enabled : .disabled,
            row.isAgentPromoted ? .agentPromoted : .human,
        ]
    }
}

/// One skill inside the answered window, as the screen draws it.
public struct SkillUsageEntry: Identifiable, Equatable, Sendable {
    public let id: Int64
    /// The server's text, which may be blank — the row then falls back to the list's own「未命名技能」at the
    /// call site, the way `SkillTableView.swift:153` handles the same column.
    public let name: String
    public let views: String
    public let uses: String
    /// `nil` when the window holds no event for this skill (`SkillUsageSummaryResponse.kt:69-70`).
    ///
    /// The wire form is the ISO one: `lastUsedAt` is a `LocalDateTime`, and Jackson 3 writes java.time with its
    /// own mask whatever `harnax-admin/src/main/resources/application.yml:23` sets
    /// (`JacksonConfig.kt:13`: 「no separate JavaTimeModule needed」). The console then hands that string to
    /// `dayjs` and prints `YYYY-MM-DD HH:mm:ss` (`usage.tsx:136`), so the separator is the only difference
    /// between the two surfaces — and the row cuts it rather than keeping a `T` no other screen here shows.
    public let lastEvent: String?
    public let badges: [SkillUsageBadge]
    /// The repository pill's text, or `nil` when the row names none. The console prints a bare `-` there
    /// (`usage.tsx:98`); a pill drawn from a dash would look like a value, and this row's silence is the
    /// point — the skill came from no repository the caller can see.
    public let repository: String?
    /// Whether this is the row the page exists to show: never loaded and never followed
    /// (`SkillUsage.swift:51-52`, `SkillUsageSummaryResponse.kt:10-13`).
    public let isUnused: Bool

    public init(row: SkillUsageRow) {
        id = row.skillId
        name = row.name
        views = String(row.viewCount)
        uses = String(row.useCount)
        lastEvent = hxSpaceStamp(row.lastUsedAt)
        badges = SkillUsageBadge.make(for: row)
        let repository = (row.repositoryName ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        self.repository = repository.isEmpty ? nil : repository
        isUnused = row.isUnused
    }
}

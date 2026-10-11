import Foundation
import HarnaxCore

/// The three review queues the 治理 column offers, as one navigation value so the hub can be a `ForEach` and
/// its last row knows it is last.
///
/// The console groups the same three pages under a first-level 「优化治理」 (`harnax-webui/config/routes.ts:119-159`)
/// and puts no access rule on any of them: the two queues feed the agents' own improvement loop, and what an
/// agent remembers is self-service compliance — every signed-in user sees and purges their own (`:140-141`).
/// The app has no first-level row to add, so this belongs to the tab whose columns are already one row per
/// agent rather than to 「我的」, which is the administration domain (`SystemDomain/SystemRoute.swift:4-12`).
///
/// The order is the console's: a conversation's merge only reaches the long-term layer once its owner says so,
/// so the approval queue is listed before the page that shows what those layers already hold
/// (`routes.ts:126-133` before `:139-146`).
///
/// Each row takes the key its own destination screen titles itself with (`memory.draft.title`,
/// `memory.list.title`, `skill.draft.title`), the same rule `env.title` and `apikey.title` follow on 「我的」, so
/// a row label and the page it opens are one string rather than two that can drift apart. The subtitles are the
/// row's own — a hub row has to say what is behind it, and the destination page never shows that sentence.
public enum GovernanceRoute: Hashable, CaseIterable, Sendable {
    /// The merge proposals the runtime has drawn out of conversation memory — `routes.ts:126-133`.
    case memoryDrafts
    /// What each of this owner's agents remembers, and the way to wipe it — `routes.ts:139-146`.
    case memory
    /// The skills the agents have proposed — `routes.ts:147-152`.
    case skillDrafts

    public var titleKey: String {
        switch self {
        case .memoryDrafts: return "memory.draft.title"
        case .memory: return "memory.list.title"
        case .skillDrafts: return "skill.draft.title"
        }
    }

    public var subtitleKey: String {
        switch self {
        case .memoryDrafts: return "governance.memoryDrafts.subtitle"
        case .memory: return "governance.memory.subtitle"
        case .skillDrafts: return "governance.skillDrafts.subtitle"
        }
    }

    /// Icon names avoid the copy namespaces on purpose: a dotted literal that starts with one of them
    /// reads as a localisation key to the gate.
    public var systemImage: String {
        switch self {
        case .memoryDrafts: return "arrow.triangle.merge"
        case .memory: return "book"
        case .skillDrafts: return "square.text.square"
        }
    }
}

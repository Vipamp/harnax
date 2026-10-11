import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// 技能监控 — which of this tenant's skills actually get loaded, and which never do.
///
/// One read, one route: `GET /api/admin/skill-usage/summary?days=` (`SkillUsageEndpoint.swift:10-17`,
/// `SkillUsageController.kt:23`, `:31`). There is nothing to write here — no create, no edit, no delete — so
/// the whole screen is this one reply plus a filter over it.
///
/// The reply's row set is deliberate and the view model's one hard rule: it holds *every* skill the caller can
/// see, including the ones with no events at all (`SkillUsageSummaryResponse.kt:10-13`). Those zero rows are
/// what the page is for — an omitted skill reads as「unknown」and only a zero row reads as「nobody ever loads
/// this」— so no path through this screen may drop one by accident. The single filter it offers is the
/// console's own `Switch` and it inverts the rule rather than breaking it: switched on it keeps *only* the zero
/// rows (`usage.tsx:46-49`).
@MainActor
public final class SkillUsageViewModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        case content
        /// The stack answered, and this tenant sees no skill at all — `totalSkills` 0 with an empty row set
        /// (`SkillUsageSummaryResponse.kt:23-24`, `:35-36`). News about the tenant, kept apart from a refusal.
        case empty
        /// The read was refused: a transport failure, or an envelope whose code is not 200 — the console's two
        /// branches at `usage.tsx:32-41`, which the shared transport already folds into one `APIError`
        /// (`Sources/HarnaxAPI/Transport/ResponseMapper.swift:22-24`). The sentence is the server's when it
        /// brought one, the dictionary's otherwise (`ErrorMessage.text`).
        case failed(String)
    }

    /// The window is a choice, never a free field: the same three presets the console's `Select` offers
    /// (`usage.tsx:195-199`), because a day count nobody counted is a number the operator cannot read back.
    public enum Window: Int, CaseIterable, Identifiable, Equatable, Sendable {
        case seven = 7
        case thirty = 30
        case ninety = 90

        public var id: Int { rawValue }

        /// Sent as `days`, verbatim — this is both the case's meaning and the query's value, so a control and
        /// a request cannot drift apart.
        public var days: Int { rawValue }

        /// Shared with the token monitor's range labels: the three windows are the same three spans, and one
        /// translation of「最近 7 天」is the app's promise.
        public var titleKey: String {
            switch self {
            case .seven: return "monitor.range.sevenDays"
            case .thirty: return "monitor.range.thirtyDays"
            case .ninety: return "monitor.range.ninetyDays"
            }
        }

        /// 30, the one window both sides already agree on: the controller's own default
        /// (`SkillUsageController.kt:46`) and the console's initial state (`usage.tsx:22`).
        public static let initial: Window = .thirty

        /// The segmented control reports an option id; an id outside the three is no window the server was
        /// offered, so it falls back rather than sending a number the screen cannot label.
        public static func `for`(_ days: Int) -> Window {
            allCases.first { $0.days == days } ?? .initial
        }
    }

    @Published public private(set) var phase: Phase = .loading
    @Published public private(set) var metrics: [SkillUsageMetric] = []
    /// What the table draws, in the reply's order. See `recompute()` for the one filter that may shorten it.
    @Published public private(set) var entries: [SkillUsageEntry] = []
    /// The window the server says it answered (`SkillUsage.swift:12-13`, `SkillUsageSummaryResponse.kt:17-18`).
    ///
    /// The sentence under the table prints this rather than `window.days`: a label that repeats what was asked
    /// for stays a label after the stack answers a different span, and the whole point of the echo is that the
    /// screen can be told what it is really showing.
    @Published public private(set) var answeredDays: Int?
    /// Start of the answered window, in the server's own text (`SkillUsageSummaryResponse.kt:20-21`).
    @Published public private(set) var since: String?
    /// Of the rows in hand, how many are the zero-use ones — the count this page is here for, and the number
    /// the `Unused only` pill quotes.
    @Published public private(set) var unusedCount = 0
    /// A refused re-read under rows that are still on screen, kept apart from `.failed` so one failed pull
    /// cannot erase the list the operator was reading (`EnvVarListViewModel.swift:96-101` does the same).
    @Published public private(set) var inlineError: String?

    @Published public var window: Window = .initial {
        // The window is the request's only parameter, so a new one is a new read.
        didSet { if window != oldValue { Task { await refresh() } } }
    }

    /// The console's `Switch` (`usage.tsx:201-204`), and client-side exactly as theirs is: no parameter on this
    /// route means「only the skills nobody loads」(`SkillUsageController.kt:36-39` takes `days` alone), so the
    /// filter is a decision about rows already answered and must not cost a request.
    @Published public var unusedOnly = false {
        didSet { if unusedOnly != oldValue { recompute() } }
    }

    private let catalog: any SkillUsageReading
    private var summary: SkillUsageSummary?
    /// Last answer wins. Bumped on every send, and a reply whose tag is no longer current takes none of the
    /// rows, the five numbers or the window echo with it — the case that bites is a 90-day read from the
    /// previous selection landing after the 7-day request that replaced it.
    private var generation = 0

    public init(catalog: any SkillUsageReading) {
        self.catalog = catalog
    }

    /// Whether the filter matched nothing, which is a different sentence from「this tenant has no skills」:
    /// the rows are on the other side of a switch the reader just threw.
    public var showsNoUnusedNote: Bool {
        unusedOnly && entries.isEmpty && (summary?.rows.isEmpty == false)
    }

    public func refresh() async {
        generation += 1
        let tag = generation
        // Only the first read has nothing to hold up. Re-writing the screen the pull is attached to is the
        // defect `PullRefreshGateTests` reads out of the source, and the control already spins.
        if summary == nil { phase = .loading }
        switch await catalog.summary(days: window.days) {
        case let .success(summary):
            guard tag == generation else { return }
            apply(summary)
        case let .failure(error):
            guard tag == generation else { return }
            guard ErrorMessage.carriesNews(error) else { return }
            let text = ErrorMessage.text(for: error)
            if summary == nil {
                phase = .failed(text)
            } else {
                inlineError = text
            }
        }
    }

    private func apply(_ summary: SkillUsageSummary) {
        inlineError = nil
        self.summary = summary
        answeredDays = summary.days
        since = hxSpaceStamp(summary.since)
        metrics = [
            SkillUsageMetric(kind: .skills, value: summary.totalSkills),
            SkillUsageMetric(kind: .loads, value: summary.totalViews),
            SkillUsageMetric(kind: .uses, value: summary.totalUses),
            SkillUsageMetric(kind: .unused, value: summary.zeroUseCount),
            // Not on the wire: derived here from the two counts the server did answer
            // (`SkillUsage.swift:25-27`), so a screen never re-divides by a total that can be zero.
            SkillUsageMetric(kind: .used, value: summary.usedSkillCount),
        ]
        recompute()
    }

    private func recompute() {
        let rows = summary?.rows ?? []
        unusedCount = rows.filter(\.isUnused).count
        // The order is the reply's and the reply is sorted most-loaded first
        // (`SkillUsageSummaryResponse.kt:35-36`); the console neither sorts nor re-orders
        // (`usage.tsx:46-49`), so a second order here would put a different skill on top of the two tables.
        entries = (unusedOnly ? rows.filter(\.isUnused) : rows).map(SkillUsageEntry.init(row:))
        phase = rows.isEmpty ? .empty : .content
    }
}

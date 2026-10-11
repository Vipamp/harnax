import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// The call-metrics screen: counts per subject over a window, and the window's own trend.
///
/// Two reads, not one. The console fires both together on entry and on every filter change
/// (`harnax-webui/src/pages/call-metrics/index.tsx:74-103`) and re-renders as each one lands, so the state is one
/// `Phase` for the page plus one `BlockState` per route: "the trend timed out" is a fact about the trend, and the
/// four numbers and the rows above and below it are still true. A failed summary is the different case — the cards
/// and the table both come from it, so the page itself has nothing left to draw.
///
/// Read side only, like the whole page family: `ToolMetricsReading` exposes three GETs and no write
/// (`Sources/HarnaxCore/Contract/ToolMetrics.swift:296-315`), and `ToolMetricsController.kt:20-25` resolves the
/// tenant from the caller's own credentials rather than from a request value, so there is no identity here for a
/// screen to change and no form to build.
@MainActor
public final class CallMetricsViewModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        case content
        case empty
        case failed(String)
    }

    /// The two routes of this screen, named for what they fill.
    public enum Block: String, CaseIterable, Identifiable, Sendable {
        /// The four cards and the rows: `GET /summary`.
        case summary
        /// The buckets: `GET /time-series`.
        case trend

        public var id: String { rawValue }
    }

    public enum BlockState: Equatable {
        case loading
        case loaded
        /// Already resolved into the app's own sentence, so a block can say *why* only it is missing.
        case failed(String)
    }

    /// How the trend is read: the line, or the numbers the line is drawn from.
    ///
    /// The console has only the line (`index.tsx:421-435`) because it has the width; three series over a 90-day
    /// window are unreadable on a handset, so this screen keeps the token monitor's switch
    /// (`TokenMonitorViewModel.TrendForm`) and its two existing arm labels rather than inventing a second chart
    /// language. The reading form is not a filter: both forms come out of the buckets already in hand.
    public enum TrendForm: String, CaseIterable, Identifiable, Equatable, Sendable {
        case chart
        case list

        public var id: String { rawValue }

        public var titleKey: String {
            switch self {
            case .chart: return "monitor.trend.form.chart"
            case .list: return "monitor.trend.form.list"
            }
        }

        /// Segment index = the case's place in `allCases`, clamped, because `HXSegmented` speaks in integers and
        /// an out-of-range one is a control that promises a form the enum does not have.
        public static func at(_ index: Int) -> TrendForm {
            allCases[min(max(index, 0), allCases.count - 1)]
        }
    }

    @Published public private(set) var phase: Phase = .loading
    @Published public private(set) var states: [Block: BlockState] = [:]

    @Published public private(set) var cards: [CallMetricsSummaryCard] = []
    @Published public private(set) var rows: [CallMetricsSubjectRow] = []
    @Published public private(set) var buckets: [CallMetricsBucket] = []

    /// The interval the summary actually counted, in the server's own text, clamps included — which is why the
    /// footnote prints these two rather than the window this screen asked for (`index.tsx:503-510`).
    @Published public private(set) var answered: AnsweredWindow?

    /// The bucket size the trend answered with, kept as its wire text so an unknown one stays visible
    /// (`CallMetricsLabels.granularity(_:)`). The screen asks for `.auto` and reads back what the service folded
    /// to (`ToolMetrics.swift:193-196`).
    @Published public private(set) var answeredGranularity: String?

    /// The window arm in force. Every read on the page names it, so one screen describes one interval.
    @Published public var window: CallMetricsWindow = CallMetricsWindows.default {
        didSet {
            guard window != oldValue else { return }
            // Both ends moved, so both reads are stale; the console reloads both for a new range too
            // (`index.tsx:103`).
            Task { await refresh() }
        }
    }

    /// The dimension the table groups by, and so what one row *means*: a tool, a server, a package, an agent, a
    /// session (`harnax-webui/src/pages/call-metrics/dimensions.ts:1-19`).
    @Published public var group: CallMetricsGroup = .tool {
        didSet {
            guard group != oldValue else { return }
            // The rows are the answer to a different question now, so what was on screen is not a shorter
            // version of the new list — it is somebody else's list. Everything is re-read and the old pages of
            // rows are discarded with it, which is also why the trend goes out again: a line drawn over a table
            // of a different subject is the one thing a switch must not leave behind (`index.tsx:91-94`).
            Task { await refresh() }
        }
    }

    /// The origin filter, `nil` for every origin.
    ///
    /// The console gets this for free from the tab it stands on (`index.tsx:29,69`); here the dimension switch is
    /// not an origin switch, so the filter is its own arm and opens on "all" — the one reading that does not hide
    /// three of the five origin buckets before the operator asked for anything. A blank `kind` is no predicate
    /// server-side (`ToolMetricsController.kt:45`, `ToolInvocationStatsMapper.xml:166`).
    @Published public var kind: CallMetricsKind? {
        didSet {
            guard kind != oldValue else { return }
            Task { await refresh() }
        }
    }

    @Published public var trendForm: TrendForm = .chart

    /// The interval a reply named.
    public struct AnsweredWindow: Equatable, Sendable {
        public let from: String
        public let to: String
        /// The dimension the server says it grouped by, which is the one the rows have to be labelled with.
        public let groupBy: String
    }

    private let catalog: any ToolMetricsReading

    /// The replies, kept raw: the cards, the rows and the buckets are all derived from them, and switching the
    /// trend's reading form must not re-fetch what is already in hand.
    private var summary: CallMetricsSummary?
    private var series: CallMetricsTimeSeries?

    /// The rows exactly as the reply sent them, in the reply's order. The cards draw the derived rows; the push
    /// value has to come from these, because a drill-down predicate is built from the id and the key a card drops
    /// (`ref(at:)`).
    private var sourceRows: [CallMetricsRow] = []

    /// One counter per route, bumped when that route is asked again. A reply whose tag is no longer current is
    /// dropped rather than applied — the case that matters is a slow trend from the old window landing after the
    /// new window's two requests went out.
    ///
    /// Per block rather than one shared number, because a block is retried on its own (`retry(_:)`): a shared
    /// counter would retire the other request mid-flight and leave its block spinning for good.
    private var generations: [Block: Int] = [:]

    public init(catalog: any ToolMetricsReading) {
        self.catalog = catalog
    }

    public func state(for block: Block) -> BlockState {
        states[block] ?? .loading
    }

    // MARK: - what the filters mean on the wire

    /// The origin filter that reaches the wire.
    ///
    /// On the `mcp` and `cli` dimensions the dimension *is* an origin bucket, so the service pins the kind itself
    /// and the caller's value cannot widen the rows — neither the list nor the cards
    /// (`ToolInvocationStatsMapper.xml:158-168`, `ToolMetricsServiceImpl.kt:55-60`). This mirrors that: the arm
    /// travels as the dimension's own bucket, which keeps the request and the reply describing the same set.
    public var effectiveKind: CallMetricsKind? {
        switch group {
        case .mcp: return .mcp
        case .cli: return .cli
        case .tool, .agent, .session: return kind
        }
    }

    /// Whether the origin control has stopped meaning anything, which is exactly the two dimensions that are
    /// themselves an origin bucket (`ToolMetrics.swift:165-168`). The view replaces the control with a sentence.
    public var showsKindNote: Bool { group == .mcp || group == .cli }

    /// The dimension the rows on screen were answered as, which is the reply's `groupBy` rather than the arm this
    /// screen asked for: an unrecognised `groupBy` falls back to `tool` server-side without failing
    /// (`ToolMetrics.swift:18-21`), and labelling those rows by the asked-for dimension would describe a grouping
    /// that did not happen.
    public var answeredGroup: CallMetricsGroup { summary?.group ?? group }

    /// Whether this dimension's rows were counted from the detail table, which bounds them by the retention
    /// window and answers them at the call's own instant (`index.tsx:478-485`, `dimensions.ts:8-14`).
    public var showsDetailNote: Bool { !group.readsAggregate }

    /// The push value for the row at this position, or `nil` when that row has nothing to drill by. A row without
    /// a subject id has no predicate to build (`ToolMetrics.swift:71-73`), so the affordance is not offered rather
    /// than offered and answered empty.
    ///
    /// Position, not `CallMetricsRow.id`: that identity is only `kind` plus `subjectKey`, and the console
    /// deliberately refuses to key its table on it (`index.tsx:488`), so two rows that collide would otherwise
    /// open the same subject twice.
    public func ref(at index: Int) -> CallMetricsSubjectRef? {
        guard index >= 0, index < sourceRows.count else { return nil }
        let row = sourceRows[index]
        guard row.isDrillable else { return nil }
        return CallMetricsSubjectRef(
            subjectId: row.subjectId,
            subjectKey: row.subjectKey,
            title: row.subjectName.isEmpty ? row.subjectKey : row.subjectName,
            toolName: row.toolName,
            kind: row.kind,
            group: answeredGroup,
            filterKind: kind,
            window: window
        )
    }

    // MARK: - derived copy

    /// The footnote under the table: the interval the counts cover, in the server's own text, with the window
    /// this screen asked for standing in until the first reply lands (`index.tsx:503-510`).
    public var windowNote: String {
        if let answered {
            return hx("monitor.calls.window.note", answered.from, answered.to)
        }
        return hx("monitor.calls.window.asked", window.start)
    }

    /// The empty sentence of the page: the window answered, and answered nothing
    /// (`index.tsx:495` — `pages.callMetrics.empty`).
    public var emptyKey: String { "monitor.calls.empty" }

    /// The trend's own empty. A zero-filled reply with no subject in the window at all has no bucket to plot
    /// (`ToolMetricsServiceImpl.kt:101-113` builds the grid over the subjects it found), and that is a different
    /// fact from "no calls", which the page already said.
    public var trendEmptyKey: String { "monitor.calls.trend.empty" }

    // MARK: - reads

    /// Both routes, as the page entry and every filter change do it.
    public func refresh() async {
        await load(blocks: Block.allCases)
    }

    /// One block on its own, from the failure note that block is showing. The other keeps what it answered.
    public func retry(_ block: Block) async {
        await load(blocks: [block])
    }

    private func load(blocks: [Block]) async {
        var tags: [Block: Int] = [:]
        for block in blocks {
            generations[block, default: 0] += 1
            tags[block] = generations[block]
        }
        for block in blocks { states[block] = .loading }
        if blocks.contains(.summary) {
            summary = nil
            cards = []
            rows = []
            answered = nil
        }
        if blocks.contains(.trend) {
            series = nil
            buckets = []
            answeredGranularity = nil
        }
        updatePhase()

        await withTaskGroup(of: Void.self) { group in
            for block in blocks {
                let tag = tags[block] ?? 0
                switch block {
                case .summary:
                    group.addTask { await self.loadSummary(tag: tag) }
                case .trend:
                    group.addTask { await self.loadTrend(tag: tag) }
                }
            }
        }
    }

    private func loadSummary(tag: Int) async {
        let reply = await catalog.summary(window: window, group: group, kind: effectiveKind)
        guard tag == generations[.summary] else { return }
        switch reply {
        case let .success(payload):
            summary = payload
            answered = AnsweredWindow(from: payload.from, to: payload.to, groupBy: payload.groupBy)
            states[.summary] = .loaded
        case let .failure(error):
            summary = nil
            answered = nil
            states[.summary] = .failed(ErrorMessage.text(for: error))
        }
        applySummary()
        updatePhase()
    }

    private func loadTrend(tag: Int) async {
        // `subjectId` stays nil: the trend here is the window's own, and narrowing it is the drill-down screen's
        // job — the console narrows nothing on this read either (`index.tsx:80`).
        let reply = await catalog.timeSeries(
            window: window,
            kind: effectiveKind,
            subjectId: nil,
            granularity: .auto
        )
        guard tag == generations[.trend] else { return }
        switch reply {
        case let .success(payload):
            series = payload
            answeredGranularity = payload.granularity
            states[.trend] = .loaded
        case let .failure(error):
            series = nil
            answeredGranularity = nil
            states[.trend] = .failed(ErrorMessage.text(for: error))
        }
        applyTrend()
        updatePhase()
    }

    private func applySummary() {
        guard let summary else {
            cards = []
            rows = []
            sourceRows = []
            return
        }
        cards = [
            CallMetricsSummaryCard(kind: .calls, value: CallMetricsFigures.count(summary.totalCalls)),
            CallMetricsSummaryCard(kind: .rate, value: CallMetricsFigures.rate(summary.successRate)),
            CallMetricsSummaryCard(
                kind: .p95,
                value: CallMetricsFigures.p95(sign: summary.p95Operator, ms: summary.p95Ms)
            ),
            CallMetricsSummaryCard(kind: .failures, value: CallMetricsFigures.count(summary.failingCalls)),
        ]
        sourceRows = summary.rows
        rows = sourceRows.map { CallMetricsSubjectRow(row: $0, group: summary.group) }
    }

    private func applyTrend() {
        guard let series else {
            buckets = []
            return
        }
        buckets = CallMetricsTrend.buckets(of: series)
    }

    /// The page's own judgement, and it is the *pair* of replies that carries it.
    ///
    /// A block that failed is drawn by that block (`BlockState.failed`), because one route's outage is not the
    /// page's: the cards and the rows come from the summary, the buckets from the trend, and a failed trend left
    /// beside a table full of rows is a screen with most of its answers in. This is the token monitor's rule
    /// (`TokenMonitorViewModel.updatePhase`), and the console renders as each of its two reads lands for the same
    /// reason (`index.tsx:82-95`).
    ///
    /// `.empty` needs both reads to have answered *successfully* with nothing in them. A window whose table came
    /// back empty while its trend failed is a screen with a hole in it, not an empty window.
    private func updatePhase() {
        switch state(for: .summary) {
        case .loading:
            phase = .loading
        case let .failed(message):
            phase = state(for: .trend) == .loaded ? .content : .failed(message)
        case .loaded:
            switch state(for: .trend) {
            case .loading:
                phase = .content
            case .loaded:
                phase = nothingOnScreen ? .empty : .content
            case let .failed(message):
                phase = nothingOnScreen ? .failed(message) : .content
            }
        }
    }

    /// Whether any of the two replies has anything to draw.
    private var nothingOnScreen: Bool {
        (summary?.rows.isEmpty ?? true) && buckets.isEmpty
    }
}

import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// E5 — the token monitor.
///
/// One page, five reads. The console fires them together on entry and on every filter change
/// (`harnax-webui/src/pages/token-monitor/index.tsx:145-155`) and re-renders as each one lands; this screen does
/// the same, and that is why the state is not one `Phase` but one per block: with five independent replies, "the
/// session chart timed out" is a fact about the session chart, and the seven numbers above it are still true.
///
/// The blocks are the five routes, and the pairing is not a design choice — `/aggregation` is the only source
/// for the KPI numbers *and* the three pies (`TokenStatsServiceImpl.kt:30-61` fills `overall` plus the three
/// lists, and no other route fills them), while each `/time-series*` route fills only its own rows (`:65-92`,
/// `:97-111`, `:113-127`, `:129-146`). So a failed aggregation read takes the cards and the pies down with it,
/// and can take nothing else.
///
/// Two of the three filters reach the server and one does not: the window and the granularity are query
/// parameters, the measure (`token` / `fee`) only decides which column the same rows get plotted as
/// (`harnax-ios/specs/03-system-domain.md:175-179`). Tenant is never a parameter — `currentTenantId()` reads it
/// from the caller's own credentials (`TokenStatsController.kt:20-24`, `:37`) — and the window is named
/// explicitly on all five requests, so one page load describes one interval instead of five slightly different
/// "now"s.
@MainActor
public final class TokenMonitorViewModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        case content
        case empty
        case failed(String)
    }

    /// One route. `aggregate` is the KPI cards plus the three pies; the four others are the four line charts.
    public enum Block: String, CaseIterable, Identifiable, Sendable {
        case aggregate
        case trend
        case modelTrend
        case agentTrend
        case sessionTrend

        public var id: String { rawValue }

        /// Whether this block's request carries a `granularity` — that is, whether a bucket change reloads it.
        /// `/aggregation` does not take the parameter at all (`TokenStatsController.kt:41-49`).
        public var isGranularitySensitive: Bool { self != .aggregate }
    }

    public enum BlockState: Equatable {
        case loading
        case loaded
        /// Already resolved into the app's own sentence, so a block can say *why* only it is missing.
        case failed(String)
    }

    @Published public private(set) var phase: Phase = .loading
    @Published public private(set) var states: [Block: BlockState] = [:]

    @Published public private(set) var cards: [TokenKpiCard] = []
    @Published public private(set) var modelSlices: [TokenShareSlice] = []
    @Published public private(set) var agentSlices: [TokenShareSlice] = []
    @Published public private(set) var sessionSlices: [TokenShareSlice] = []

    @Published public private(set) var overallSeries: [TokenTrendSeries] = []
    @Published public private(set) var modelSeries: [TokenTrendSeries] = []
    @Published public private(set) var agentSeries: [TokenTrendSeries] = []
    @Published public private(set) var sessionSeries: [TokenTrendSeries] = []

    @Published public var range: TokenWindowPreset = .default {
        // Both ends of the window move, so all five reads are stale — the console reloads all five for a new
        // range too (`index.tsx:145-155`).
        didSet { if range != oldValue { Task { await refresh() } } }
    }

    @Published public var granularity: TokenGranularity = .serverDefault {
        // The seven numbers cannot change: `granularity` is not an argument `/aggregation` accepts
        // (`TokenStatsController.kt:41-49`), so re-asking for the cards would buy one identical reply.
        didSet { if granularity != oldValue { Task { await refreshTrends() } } }
    }

    @Published public var measure: TokenMeasure = .token {
        // Nothing left to fetch — the rows already in hand hold both columns. This is the one filter that must
        // not touch the network.
        didSet { if measure != oldValue { recompute() } }
    }

    /// The console's own truncation, copied rather than invented
    /// (`harnax-webui/src/pages/token-monitor/index.tsx:483-484` — `slice(0, 10)` on the session pie). The
    /// server's `ORDER BY grandTotalToken DESC` makes "the first ten" mean "the ten that cost the most".
    static let sessionPieLimit = 10

    private let catalog: any TokenStatsCataloging
    private let now: @Sendable () -> Date

    /// The replies, before this screen turns them into rows and series. Kept so a measure change and a
    /// granularity change can redraw from what is already in hand.
    private var overall: TokenOverallStats?
    private var modelRows: [TokenModelStat] = []
    private var agentRows: [TokenAgentStat] = []
    private var sessionRows: [TokenSessionStat] = []
    private var trendRows: [TokenTimePoint] = []
    private var modelTrendRows: [TokenTimePoint] = []
    private var agentTrendRows: [TokenTimePoint] = []
    private var sessionTrendRows: [TokenTimePoint] = []

    /// One counter per route, bumped when that route is asked again. A reply whose tag is no longer current is
    /// dropped rather than applied — the case that matters is a slow request from the old range landing after
    /// the new range's five have gone out.
    ///
    /// Per block rather than one shared number, because a single block can be retried on its own
    /// (`retry(_:)`): a shared counter would retire the other four requests mid-flight and leave their blocks
    /// spinning for good.
    private var generations: [Block: Int] = [:]

    public init(catalog: any TokenStatsCataloging, now: @escaping @Sendable () -> Date = { Date() }) {
        self.catalog = catalog
        self.now = now
    }

    public func state(for block: Block) -> BlockState {
        states[block] ?? .loading
    }

    /// The window the next five requests will name, in the text the routes parse.
    public var window: (start: String, end: String) {
        let end = now()
        let start = end.addingTimeInterval(TimeInterval(-range.dayOffset * 86_400))
        return (hxWallClockString(start), hxWallClockString(end))
    }

    /// All five routes, as the page entry and the range filter do it.
    public func refresh() async {
        await load(blocks: Block.allCases)
    }

    /// The four trend routes only — what a granularity change actually needs.
    public func refreshTrends() async {
        await load(blocks: Block.allCases.filter(\.isGranularitySensitive))
    }

    /// One block on its own, from the failure note that card is showing. The other four keep whatever they
    /// already answered — and an in-flight request among them is not retired by this.
    public func retry(_ block: Block) async {
        await load(blocks: [block])
    }

    private func load(blocks: [Block]) async {
        var tags: [Block: Int] = [:]
        for block in blocks {
            generations[block, default: 0] += 1
            tags[block] = generations[block]
        }
        // Sampled once and shared: five reads of `now()` would give five slightly different intervals, and the
        // seven numbers would then describe a window no chart matches.
        let window = self.window
        for block in blocks { states[block] = .loading }
        if blocks.contains(.aggregate) {
            cards = []
            modelSlices = []
            agentSlices = []
            sessionSlices = []
            overall = nil
            modelRows = []
            agentRows = []
            sessionRows = []
        }
        for block in blocks where block != .aggregate {
            setTrendRows([], for: block)
            applyTrend(block)
        }
        updatePhase()

        await withTaskGroup(of: Void.self) { group in
            for block in blocks {
                let tag = tags[block] ?? 0
                switch block {
                case .aggregate:
                    group.addTask { await self.loadAggregate(window: window, tag: tag) }
                case .trend, .modelTrend, .agentTrend, .sessionTrend:
                    group.addTask { await self.loadTrend(block, window: window, tag: tag) }
                }
            }
        }
    }

    private func loadAggregate(window: (start: String, end: String), tag: Int) async {
        let reply = await catalog.tokenAggregation(startTime: window.start, endTime: window.end)
        guard tag == generations[.aggregate] else { return }
        switch reply {
        case let .success(payload):
            overall = payload.overall
            modelRows = payload.modelStats
            agentRows = payload.agentStats
            sessionRows = payload.sessionStats
            states[.aggregate] = .loaded
        case let .failure(error):
            overall = nil
            modelRows = []
            agentRows = []
            sessionRows = []
            states[.aggregate] = .failed(ErrorMessage.text(for: error))
        }
        applyAggregate()
        updatePhase()
    }

    private func loadTrend(_ block: Block, window: (start: String, end: String), tag: Int) async {
        let reply: Result<[TokenTimePoint], APIError>
        switch block {
        case .aggregate: return
        case .trend:
            reply = await catalog.tokenTimeSeries(
                startTime: window.start, endTime: window.end, granularity: granularity
            )
        case .modelTrend:
            reply = await catalog.tokenModelTimeSeries(
                startTime: window.start, endTime: window.end, granularity: granularity
            )
        case .agentTrend:
            reply = await catalog.tokenAgentTimeSeries(
                startTime: window.start, endTime: window.end, granularity: granularity
            )
        case .sessionTrend:
            reply = await catalog.tokenSessionTimeSeries(
                startTime: window.start, endTime: window.end, granularity: granularity
            )
        }
        // A late reply to a superseded window is dropped; the newer request for this block owns the screen.
        guard tag == generations[block] else { return }
        switch reply {
        case let .success(rows):
            setTrendRows(rows, for: block)
            states[block] = .loaded
        case let .failure(error):
            setTrendRows([], for: block)
            states[block] = .failed(ErrorMessage.text(for: error))
        }
        applyTrend(block)
        updatePhase()
    }

    /// Rebuilds every derived block from the stored replies. The measure switch runs this instead of fetching:
    /// both columns are already in these rows.
    private func recompute() {
        applyAggregate()
        for block in Block.allCases where block != .aggregate { applyTrend(block) }
    }

    private func applyAggregate() {
        if let overall {
            cards = Self.makeCards(overall)
            modelSlices = slices(
                modelRows.map { (key: tokenDimensionKey(name: $0.title, identity: $0.identity),
                                   title: $0.title, detail: $0.provider, row: $0 as any TokenMetricsReport) },
                limit: nil,
                overall: overall
            )
            agentSlices = slices(
                agentRows.map { (key: tokenDimensionKey(name: $0.title, identity: $0.identity),
                                 title: $0.title, detail: $0.identity, row: $0 as any TokenMetricsReport) },
                limit: nil,
                overall: overall
            )
            sessionSlices = slices(
                sessionRows.map { (key: tokenDimensionKey(name: $0.title, identity: $0.identity),
                                   title: $0.title, detail: $0.identity, row: $0 as any TokenMetricsReport) },
                limit: Self.sessionPieLimit,
                overall: overall
            )
        } else {
            cards = []
            modelSlices = []
            agentSlices = []
            sessionSlices = []
        }
    }

    private func applyTrend(_ block: Block) {
        switch block {
        case .aggregate: break
        case .trend: overallSeries = makeOverallSeries(trendRows)
        case .modelTrend: modelSeries = makeDimensionSeries(modelTrendRows)
        case .agentTrend: agentSeries = makeDimensionSeries(agentTrendRows)
        case .sessionTrend: sessionSeries = makeDimensionSeries(sessionTrendRows)
        }
    }

    private func setTrendRows(_ rows: [TokenTimePoint], for block: Block) {
        switch block {
        case .aggregate: break
        case .trend: trendRows = rows
        case .modelTrend: modelTrendRows = rows
        case .agentTrend: agentTrendRows = rows
        case .sessionTrend: sessionTrendRows = rows
        }
    }

    /// The page's own judgement, and it is the aggregation read that carries it.
    ///
    /// `grandTotalToken == 0` empties the whole console page (`harnax-webui/src/pages/token-monitor/index.tsx:878`),
    /// which is the server's own "this window holds nothing" reading rather than a client-side invention — so an
    /// empty window is *empty*, not "cards missing and four charts drawn". A failed aggregation is a different
    /// matter: nothing here can tell an empty window from an unreadable one, so whatever else answered stays on
    /// screen with the cards' own failure note.
    private func updatePhase() {
        switch states[.aggregate] {
        case nil, .loading:
            phase = .loading
        case .loaded:
            phase = (overall?.hasConsumption ?? false) ? .content : .empty
        case let .failed(message):
            phase = anyBlockLoaded ? .content : .failed(message)
        }
    }

    /// Whether at least one of the four charts has an answer to draw.
    private var anyBlockLoaded: Bool {
        for state in states.values where state == .loaded { return true }
        return false
    }

    // MARK: - derived blocks

    /// The seven cards, in the console's order (`index.tsx:177-234`), and there are exactly seven: the console's
    /// KPI row also shows a call count, a failure count and a period-over-period figure, and no field on
    /// `TokenOverallStats` backs them (`TokenStatsAggregationResponse.kt:140-160`), so this screen has no card —
    /// not even a placeholder — for those three.
    private static func makeCards(_ overall: TokenOverallStats) -> [TokenKpiCard] {
        [
            TokenKpiCard(kind: .fee, value: TokenFigures.fee(overall.totalFee), share: nil),
            TokenKpiCard(
                kind: .input,
                value: TokenFigures.token(overall.totalInputToken),
                share: TokenFigures.share(Double(overall.totalInputToken), of: Double(overall.grandTotalToken))
            ),
            TokenKpiCard(
                kind: .output,
                value: TokenFigures.token(overall.totalOutputToken),
                share: TokenFigures.share(Double(overall.totalOutputToken), of: Double(overall.grandTotalToken))
            ),
            TokenKpiCard(kind: .total, value: TokenFigures.token(overall.grandTotalToken), share: nil),
            TokenKpiCard(kind: .agents, value: TokenFigures.token(overall.agentCount), share: nil),
            TokenKpiCard(kind: .sessions, value: TokenFigures.token(overall.sessionCount), share: nil),
            TokenKpiCard(kind: .models, value: TokenFigures.token(overall.modelCount), share: nil),
        ]
    }

    /// One row per pie slice, in the order the server sent them.
    ///
    /// The order is load-bearing twice over: `ORDER BY grandTotalToken DESC` is what makes the session
    /// truncation a "top ten" rather than ten arbitrary rows, and it is what fixes each slice's hue, since the
    /// colour is the row's position (`TokenChartPalette`).
    private func slices(
        _ rows: [(key: String, title: String?, detail: String?, row: any TokenMetricsReport)],
        limit: Int?,
        overall: TokenOverallStats
    ) -> [TokenShareSlice] {
        rows.prefix(limit ?? rows.count).enumerated().map { offset, item in
            TokenShareSlice(
                index: offset,
                key: item.key,
                title: item.title,
                detail: item.detail,
                value: measure.formatted(of: item.row),
                plot: measure.value(of: item.row),
                share: measure.share(of: item.row, against: overall),
                slot: TokenChartPalette.slot(forIndex: offset)
            )
        }
    }

    /// The overall trend: one series per measure column the console draws, all reading the same rows.
    private func makeOverallSeries(_ rows: [TokenTimePoint]) -> [TokenTrendSeries] {
        let usable = plottable(rows)
        return TokenOverallSeries.visible(in: measure).enumerated().map { offset, series in
            TokenTrendSeries(
                id: series.rawValue,
                name: nil,
                nameKey: series.titleKey,
                slot: TokenChartPalette.slot(forIndex: offset),
                points: usable.enumerated().map { index, row in
                    point(
                        seriesID: series.rawValue,
                        index: index,
                        row: row,
                        value: series.value(of: row),
                        formatted: series.formatted(row)
                    )
                }
            )
        }
    }

    /// A dimension reply is one row per *bucket × dimension* (`TokenStatsMapper.xml:234` groups by both), so the
    /// rows have to be grouped before a line can be drawn. Grouping happens here, once, rather than in the
    /// view's body: the number of rows is buckets times dimensions, and a chart that regrouped on every redraw
    /// is the thing the spec warns about (`harnax-ios/specs/03-system-domain.md:334`).
    private func makeDimensionSeries(_ rows: [TokenTimePoint]) -> [TokenTrendSeries] {
        var order: [String] = []
        var grouped: [String: [TokenTimePoint]] = [:]
        for row in plottable(rows) {
            let key = tokenDimensionKey(name: row.name, identity: row.identity)
            if grouped[key] == nil { order.append(key) }
            grouped[key, default: []].append(row)
        }
        return order.enumerated().map { offset, key in
            let points = (grouped[key] ?? []).enumerated().map { index, row in
                point(
                    seriesID: key,
                    index: index,
                    row: row,
                    value: measure.value(of: row),
                    formatted: measure.formatted(of: row)
                )
            }
            return TokenTrendSeries(
                id: key,
                name: key.isEmpty ? nil : key,
                nameKey: nil,
                slot: TokenChartPalette.slot(forIndex: offset),
                points: points
            )
        }
    }

    /// A row without bucket text cannot be placed on a time axis at all — `timePoint` is what says where it
    /// goes, and `TokenStatsAggregationResponse.kt:241` declares it nullable. Those rows are left out rather
    /// than plotted at position zero, which would draw a spike no server made.
    private func plottable(_ rows: [TokenTimePoint]) -> [TokenTimePoint] {
        rows.filter { hxPresented($0.timePoint) != nil }
    }

    private func point(
        seriesID: String,
        index: Int,
        row: TokenTimePoint,
        value: Double,
        formatted: String
    ) -> TokenTrendPoint {
        let timePoint = row.timePoint ?? ""
        return TokenTrendPoint(
            id: "\(seriesID)|\(timePoint)|\(index)",
            timePoint: timePoint,
            label: TokenAxisLabel.make(timePoint, granularity: granularity),
            value: value,
            formatted: formatted
        )
    }
}

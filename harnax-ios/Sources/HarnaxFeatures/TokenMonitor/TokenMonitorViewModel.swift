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

    /// A chart with a legend the reader can switch entries off — the console's legend click, which ECharts
    /// gives every chart on the page for free (`harnax-webui/src/pages/token-monitor/index.tsx:243-256`,
    /// `:601-611`).
    ///
    /// One case per chart rather than one per `Block`, because the three donuts share the aggregation read: an
    /// id keyed by position can name the first model and the first session alike, and muting the pie you are
    /// reading must never mute the pie you are not.
    public enum LegendChart: String, CaseIterable, Identifiable, Sendable {
        case modelPie, agentPie, sessionPie
        case overallTrend, modelTrend, agentTrend, sessionTrend

        public var id: String { rawValue }

        /// The read whose reply feeds this chart. Reloading it retires the entries switched off, which is the
        /// honest outcome: an id carries the row's position in the previous reply, and after a new window the row
        /// at that position is a different entity.
        var block: Block {
            switch self {
            case .modelPie, .agentPie, .sessionPie: return .aggregate
            case .overallTrend: return .trend
            case .modelTrend: return .modelTrend
            case .agentTrend: return .agentTrend
            case .sessionTrend: return .sessionTrend
            }
        }
    }

    /// How one trend block is read: the line, or the numbers the line is drawn from.
    ///
    /// A choice per block rather than one for the page, because the four blocks answer four different questions
    /// and the reader who wants the overall trend as numbers may still want the per-model split as a shape. The
    /// default is the chart: the accessibility rule (`DESIGN.md` §10) asks that the numeric reading exist, not
    /// that it replace what a sighted reader already reads.
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

        /// Segment index = the case's place in `allCases`, clamped. `HXSegmented` speaks in integer ids, and an
        /// out-of-range one is a control that promised a form the enum does not have.
        public static func at(_ index: Int) -> TrendForm {
            allCases[min(max(index, 0), allCases.count - 1)]
        }
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

    /// The entries the reader has switched off, one set per chart.
    ///
    /// Local and never sent: the console's legend selection is component state too, and none of the five routes
    /// takes a parameter that could say "every model except this one". A hidden row is still a row the server
    /// counted, which is why the shares of the rows that stay on screen keep their denominator.
    @Published public private(set) var hiddenIDs: [LegendChart: Set<String>] = [:]

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

    // MARK: - interactive legend

    public func isHidden(_ id: String, in chart: LegendChart) -> Bool {
        hiddenIDs[chart]?.contains(id) ?? false
    }

    /// Switches one legend entry on or off. The id is the row's own identity in the reply it came from — a
    /// slice's is index-qualified so two rows with no name stay two entries, a series' is the dimension key.
    public func toggleLegend(_ id: String, in chart: LegendChart) {
        if hiddenIDs[chart]?.contains(id) == true {
            hiddenIDs[chart]?.remove(id)
        } else {
            hiddenIDs[chart, default: []].insert(id)
            if drilledIDs[chart] == id { drilledIDs[chart] = nil }
        }
    }

    /// What the donut draws. The legend list still carries every row, hidden ones included, so an entry can
    /// always be switched back on — and a hidden slice takes its share of the ring with it while the percentage
    /// the legend prints next to the others keeps the window as its denominator, because those numbers come from
    /// `overall` and not from the rows on screen (`TokenMeasure.share(of:against:)`).
    public func visibleSlices(_ slices: [TokenShareSlice], in chart: LegendChart) -> [TokenShareSlice] {
        guard let hidden = hiddenIDs[chart], !hidden.isEmpty else { return slices }
        return slices.filter { !hidden.contains($0.id) }
    }

    /// What a line chart draws. Dropping a series also drops it from the y-axis domain, which is the point of
    /// switching one off: the console's chart rescales the same way.
    public func visibleSeries(_ series: [TokenTrendSeries], in chart: LegendChart) -> [TokenTrendSeries] {
        guard let hidden = hiddenIDs[chart], !hidden.isEmpty else { return series }
        return series.filter { !hidden.contains($0.id) }
    }

    // MARK: - drill-down

    /// The one ring row a tap opened, per chart.
    @Published public private(set) var drilledIDs: [LegendChart: String] = [:]

    /// The one bucket a line chart is reading out, per chart.
    @Published public private(set) var selectedBuckets: [LegendChart: String] = [:]

    /// Which row a tap on the ring landed on.
    ///
    /// `chartAngleSelection` hands back the touched position as a running total of the sector values, so the row
    /// is the one whose span on the ring holds it. Only the rows the ring draws are walked, and a row with
    /// nothing to plot takes no angle a finger could hit.
    public func slice(atAngle angle: Double, in chart: LegendChart) -> TokenShareSlice? {
        guard angle >= 0 else { return nil }
        var running = 0.0
        for slice in visibleSlices(slices(for: chart), in: chart) {
            // Each wedge's span, closed at both ends the way the framework reads its own selection value: the
            // number is a distance round the ring, and a touch on a wedge's edge — including the last wedge's
            // far edge, which is the full total — is that wedge. A row with nothing to plot spans no distance,
            // so the angle it would have occupied belongs to the next one drawn.
            if slice.plot > 0, (running...running + slice.plot).contains(angle) { return slice }
            running += slice.plot
        }
        return nil
    }

    /// Opens a row's own figures, or closes it again when the same wedge is tapped twice.
    public func drill(_ slice: TokenShareSlice, in chart: LegendChart) {
        drilledIDs[chart] = drilledIDs[chart] == slice.id ? nil : slice.id
    }

    public func drilledSlice(in chart: LegendChart) -> TokenShareSlice? {
        guard let id = drilledIDs[chart] else { return nil }
        return slices(for: chart).first { $0.id == id }
    }

    /// Moves the readout to a bucket, or removes it when the tap lands where it already sits.
    public func pickBucket(_ timePoint: String, in chart: LegendChart) {
        selectedBuckets[chart] = selectedBuckets[chart] == timePoint ? nil : timePoint
    }

    /// The lines' numbers at the bucket being read out — the handset's version of the console's hover tooltip
    /// (`harnax-webui/src/pages/token-monitor/index.tsx:642-650`), which has no finger equivalent on a phone.
    public func readings(in chart: LegendChart) -> [TokenBucketReading] {
        guard let bucket = selectedBuckets[chart] else { return [] }
        return visibleSeries(series(for: chart), in: chart).compactMap { item in
            guard let point = item.points.first(where: { $0.timePoint == bucket }) else { return nil }
            return TokenBucketReading(
                id: item.id, slot: item.slot, name: item.legendText, value: point.formatted
            )
        }
    }

    /// The bucket being read out on this chart, in the axis text the screen already shows for it.
    public func readoutBucket(in chart: LegendChart) -> String? {
        guard let bucket = selectedBuckets[chart] else { return nil }
        for item in series(for: chart) {
            if let point = item.points.first(where: { $0.timePoint == bucket }) { return point.label }
        }
        return bucket
    }

    // MARK: - the line, or the numbers behind it

    /// The reading form of every trend block the reader has moved off the default, one entry per chart.
    ///
    /// Keyed by `LegendChart` exactly like `hiddenIDs` and `selectedBuckets` — the four trend cases of that enum
    /// are the four blocks that draw a line, one each — and held here rather than as `@State` on the card,
    /// because the card is rebuilt from the block's state on every reply and because the choice has to be
    /// assertable without SwiftUI. Nothing about a form names a row of a reply, so unlike the toggles above it
    /// survives a reload (see `load(blocks:)`).
    @Published public private(set) var trendForms: [LegendChart: TrendForm] = [:]

    /// What this block draws. An absent entry is the chart, which is why the map stays sparse instead of being
    /// seeded with four cases at init.
    public func form(for chart: LegendChart) -> TrendForm {
        trendForms[chart] ?? .chart
    }

    /// Choosing a form costs no request: both readings come out of the rows already in hand, the same as the
    /// legend toggles and the measure filter.
    public func setForm(_ form: TrendForm, for chart: LegendChart) {
        trendForms[chart] = form
    }

    /// Every bucket of a trend block as a row of numbers — the reading that does not need the line to be seen
    /// (`DESIGN.md` §10).
    ///
    /// It says what the chart draws and nothing else: only the series the legend leaves visible contribute, a
    /// number is the plotted `point.value` through `TokenMeasure.formatted(value:)` (the same call the chart's
    /// own y-axis labels make, so a list value and the height it came from cannot disagree), a bucket label is
    /// the point's own `label` rather than a date this side re-reads, and the rows run in bucket order because
    /// the server's `yyyy-MM-dd HH:mm:ss` text orders itself as text. A bucket one line has no point in — a
    /// dimension that was quiet that day — carries no cell for that line, just as the line skips that point.
    public func trendListRows(in chart: LegendChart) -> [TokenTrendListRow] {
        struct Held {
            let label: String
            var readings: [TokenBucketReading]
        }
        var buckets: [String: Held] = [:]
        for item in visibleSeries(series(for: chart), in: chart) {
            for point in item.points {
                // The point's own id rather than the series': one bucket row holds several cells, and two cells
                // that shared an id would be a list SwiftUI cannot key.
                let reading = TokenBucketReading(
                    id: point.id,
                    slot: item.slot,
                    name: item.legendText,
                    value: measure.formatted(value: point.value)
                )
                if let held = buckets[point.timePoint] {
                    buckets[point.timePoint] = Held(label: held.label, readings: held.readings + [reading])
                } else {
                    buckets[point.timePoint] = Held(label: point.label, readings: [reading])
                }
            }
        }
        return buckets.keys.sorted().compactMap { timePoint in
            guard let held = buckets[timePoint] else { return nil }
            return TokenTrendListRow(id: timePoint, label: held.label, values: held.readings)
        }
    }

    private func slices(for chart: LegendChart) -> [TokenShareSlice] {
        switch chart {
        case .modelPie: return modelSlices
        case .agentPie: return agentSlices
        case .sessionPie: return sessionSlices
        case .overallTrend, .modelTrend, .agentTrend, .sessionTrend: return []
        }
    }

    private func series(for chart: LegendChart) -> [TokenTrendSeries] {
        switch chart {
        case .modelPie, .agentPie, .sessionPie: return []
        case .overallTrend: return overallSeries
        case .modelTrend: return modelSeries
        case .agentTrend: return agentSeries
        case .sessionTrend: return sessionSeries
        }
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
        // A switched-off id names a row's position in the reply that is about to be replaced, so the next reply's
        // row at that position could be a different entity; the toggle goes with the data it was read from.
        // The reading form is not: it names no row, only how the block is drawn, so a re-asked window keeps it.
        for chart in LegendChart.allCases where blocks.contains(chart.block) {
            hiddenIDs[chart] = []
            drilledIDs[chart] = nil
            selectedBuckets[chart] = nil
        }
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
                slot: TokenChartPalette.slot(forIndex: offset),
                breakdown: TokenRowBreakdown(row: item.row)
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

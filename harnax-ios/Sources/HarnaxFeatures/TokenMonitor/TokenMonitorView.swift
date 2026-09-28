import SwiftUI
import Charts
import HarnaxCore
import HarnaxKit

/// E5 — the token monitor.
///
/// Layout is the console's, top to bottom: the filters, seven numbers, three donuts, four lines
/// (`harnax-webui/src/pages/token-monitor/index.tsx:787-1105`). What a handset changes is the width: the console
/// puts three pies across a row and four lines under them, and here each block is one card of its own, in the
/// order the data comes in — the cards first, because they are the only thing the aggregation read can say.
///
/// The charts are Swift Charts rather than a picture of the console's. Two rules they have to keep: the hue of a
/// slice comes from a token slot the view model chose by position (`TokenChartPalette`), so a refresh does not
/// repaint a model in a different colour, and no `.animation` is attached anywhere, because five independent
/// replies landing one after another would otherwise animate the same chart five times
/// (`harnax-ios/specs/03-system-domain.md:334`).
public struct TokenMonitorView: View {
    @StateObject private var vm: TokenMonitorViewModel

    public init(catalog: any TokenStatsCataloging) {
        _vm = StateObject(wrappedValue: TokenMonitorViewModel(catalog: catalog))
    }

    public var body: some View {
        content
            .harnaxScreen()
            .navigationTitle(Text(verbatim: hx("monitor.title")))
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        Task { await vm.refresh() }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .accessibilityLabel(hx("state.action.reload"))
                }
            }
            .task {
                if vm.phase == .loading { await vm.refresh() }
            }
            .refreshable { await vm.refresh() }
    }

    @ViewBuilder
    private var content: some View {
        switch vm.phase {
        case .loading:
            HXStateView(.loading)
        case .empty:
            HXStateView(
                .empty,
                message: hx("monitor.empty"),
                retry: { Task { await vm.refresh() } }
            )
        case let .failed(message):
            HXStateView(.error, message: message, retry: { Task { await vm.refresh() } })
        case .content:
            screen
        }
    }

    private var screen: some View {
        ScrollView {
            LazyVStack(spacing: 14) {
                filters
                cardsBlock
                piesBlock
                trendsBlock
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, HXLayout.tabBarClearance)
        }
    }

    // MARK: - filters

    /// The three filters. Only the first two reach the network — the measure picks a column out of rows already
    /// in hand, which is why changing it must not start a request.
    private var filters: some View {
        HXCard {
            VStack(alignment: .leading, spacing: 12) {
                filterRow("monitor.filter.range", presets.rangeIndex) { filterOptions(TokenWindowPreset.allCases) }
                filterRow("monitor.filter.granularity", presets.granularityIndex) {
                    // The four codes are the four the server accepts, and the raw values are the query values
                    // (`TokenStatsController.kt:77`), so this menu cannot send a bucket it would downgrade.
                    filterOptions(TokenGranularity.allCases)
                }
                filterRow("monitor.filter.measure", presets.measureIndex) { filterOptions(TokenMeasure.allCases) }
            }
        }
    }

    /// Segment index = the case's place in `allCases`, which is what `at(_:)` reads back. Writing the ids out
    /// per row would let a menu promise a bucket the enum no longer has at that index.
    private func filterOptions(_ cases: [some TokenFilterOption]) -> [HXSegmentOption] {
        cases.enumerated().map { HXSegmentOption(id: $0.offset, $0.element.titleKey) }
    }

    private func filterRow(_ titleKey: String, _ selection: Binding<Int>, _ options: () -> [HXSegmentOption]) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HXText(titleKey)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.hx(.textTertiary))
            HXSegmented(options(), selection: selection)
        }
    }

    /// The three segment bindings. `HXSegmented` speaks in integer ids, and the view model speaks in enum
    /// cases, so the mapping lives here rather than in the filter state.
    private var presets: SegmentBindings { SegmentBindings(vm: vm) }

    private struct SegmentBindings {
        let vm: TokenMonitorViewModel

        var rangeIndex: Binding<Int> {
            Binding(
                get: { TokenWindowPreset.allCases.firstIndex(of: vm.range) ?? 0 },
                set: { vm.range = TokenWindowPreset.at($0) }
            )
        }

        var granularityIndex: Binding<Int> {
            Binding(
                get: { TokenGranularity.allCases.firstIndex(of: vm.granularity) ?? 1 },
                set: { vm.granularity = TokenGranularity.at($0) }
            )
        }

        var measureIndex: Binding<Int> {
            Binding(
                get: { TokenMeasure.allCases.firstIndex(of: vm.measure) ?? 0 },
                set: { vm.measure = TokenMeasure.at($0) }
            )
        }
    }

    // MARK: - the seven numbers

    /// The KPI row is the aggregation read and nothing else, so a failed aggregation is exactly the seven
    /// numbers missing — the charts below keep their own answers.
    @ViewBuilder
    private var cardsBlock: some View {
        switch vm.state(for: .aggregate) {
        case .loading:
            HXCard { HXStateView(.loading) }
        case let .failed(message):
            blockFailure("monitor.cards", message: message, block: .aggregate)
        case .loaded:
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 10)], spacing: 10) {
                ForEach(vm.cards) { card in
                    TokenKpiTile(card: card)
                }
            }
        }
    }

    // MARK: - the three donuts

    @ViewBuilder
    private var piesBlock: some View {
        HXSectionHeader("monitor.section.pies")
        donut("monitor.pie.model", block: .aggregate, slices: vm.modelSlices)
        donut("monitor.pie.agent", block: .aggregate, slices: vm.agentSlices)
        donut("monitor.pie.session", block: .aggregate, slices: vm.sessionSlices)
    }

    @ViewBuilder
    private func donut(_ titleKey: String, block: TokenMonitorViewModel.Block, slices: [TokenShareSlice]) -> some View {
        switch vm.state(for: block) {
        case .loading:
            HXCard { HXStateView(.loading) }
        case let .failed(message):
            blockFailure(titleKey, message: message, block: block)
        case .loaded:
            HXCard {
                VStack(alignment: .leading, spacing: 12) {
                    HXText(titleKey)
                        .font(.headline)
                        .foregroundStyle(Color.hx(.textPrimary))
                    if slices.isEmpty {
                        HXText("monitor.pie.empty")
                            .font(.footnote)
                            .foregroundStyle(Color.hx(.textSecondary))
                    } else {
                        TokenDonutChart(slices: slices)
                        VStack(alignment: .leading, spacing: 6) {
                            ForEach(slices) { slice in
                                TokenLegendLine(
                                    slot: slice.slot,
                                    title: slice.legendText,
                                    detail: slice.detail,
                                    trailing: slice.share + " · " + slice.value
                                )
                            }
                        }
                        if titleKey == "monitor.pie.session" {
                            // Worth a word, because ten of them are on screen and the window may hold fifty:
                            // the console keeps the same cut (`index.tsx:483-484`).
                            HXText("monitor.pie.session.note")
                                .font(.caption)
                                .foregroundStyle(Color.hx(.textTertiary))
                        }
                    }
                }
            }
        }
    }

    // MARK: - the four lines

    @ViewBuilder
    private var trendsBlock: some View {
        HXSectionHeader("monitor.section.trends")
        trend("monitor.trend.overall", block: .trend, series: vm.overallSeries)
        trend("monitor.trend.model", block: .modelTrend, series: vm.modelSeries)
        trend("monitor.trend.agent", block: .agentTrend, series: vm.agentSeries)
        trend("monitor.trend.session", block: .sessionTrend, series: vm.sessionSeries)
    }

    @ViewBuilder
    private func trend(
        _ titleKey: String,
        block: TokenMonitorViewModel.Block,
        series: [TokenTrendSeries]
    ) -> some View {
        switch vm.state(for: block) {
        case .loading:
            HXCard { HXStateView(.loading) }
        case let .failed(message):
            blockFailure(titleKey, message: message, block: block)
        case .loaded:
            HXCard {
                VStack(alignment: .leading, spacing: 12) {
                    HXText(titleKey)
                        .font(.headline)
                        .foregroundStyle(Color.hx(.textPrimary))
                    if series.isEmpty || series.allSatisfy({ $0.points.isEmpty }) {
                        HXText("monitor.trend.empty")
                            .font(.footnote)
                            .foregroundStyle(Color.hx(.textSecondary))
                    } else {
                        TokenLineChart(series: series, measure: vm.measure)
                            .frame(height: 190)
                        HXFlow(spacing: 10) {
                            ForEach(series) { item in
                                HXStack(slot: item.slot, title: item.legendText)
                            }
                        }
                    }
                }
            }
        }
    }

    /// A block that did not answer says so where it would have drawn, and offers its own retry rather than
    /// making the reader reload the page.
    @ViewBuilder
    private func blockFailure(
        _ titleKey: String,
        message: String,
        block: TokenMonitorViewModel.Block
    ) -> some View {
        HXCard {
            VStack(alignment: .leading, spacing: 10) {
                HXText(titleKey)
                    .font(.headline)
                    .foregroundStyle(Color.hx(.textPrimary))
                HXBanner("state.error.title", message: message, systemImage: "exclamationmark.triangle", tone: .danger)
                Button {
                    Task { await vm.retry(block) }
                } label: {
                    HXText("common.retry")
                }
                .buttonStyle(.hxInline)
            }
        }
    }
}

/// One number of the seven.
private struct TokenKpiTile: View {
    let card: TokenKpiCard

    var body: some View {
        HXCard {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 6) {
                    Image(systemName: glyph)
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(Color.hx(tone))
                    HXText(titleKey)
                        .font(.footnote)
                        .foregroundStyle(Color.hx(.textSecondary))
                        .lineLimit(2)
                }
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(verbatim: card.value)
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(Color.hx(.textPrimary))
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                    if let share = card.share {
                        HXChip(share, tone: tone)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    /// The seven, in the console's order, with the console's roles: fee, input, output, total, and the three
    /// counts (`harnax-webui/src/pages/token-monitor/index.tsx:177-234`).
    private var titleKey: String {
        switch card.kind {
        case .fee: return "monitor.card.fee"
        case .input: return "monitor.card.input"
        case .output: return "monitor.card.output"
        case .total: return "monitor.card.total"
        case .agents: return "monitor.card.agents"
        case .sessions: return "monitor.card.sessions"
        case .models: return "monitor.card.models"
        }
    }

    private var glyph: String {
        switch card.kind {
        case .fee: return "chart.pie"
        case .input: return "arrow.down"
        case .output: return "arrow.up"
        case .total: return "sum"
        case .agents: return "person.2"
        case .sessions: return "bubble.left"
        case .models: return "square.stack"
        }
    }

    private var tone: PaletteSlot {
        switch card.kind {
        case .fee: return .warning
        case .input: return .brand
        case .output: return .teal
        case .total: return .success
        case .agents: return .purple
        case .sessions: return .indigo
        case .models: return .danger
        }
    }
}

/// The ring: `innerRadius 0.6`, which is what the console's donut cut is too
/// (`harnax-webui/src/pages/token-monitor/index.tsx:252` uses `radius 0.8` on a pie with a hole).
private struct TokenDonutChart: View {
    let slices: [TokenShareSlice]

    var body: some View {
        Chart(slices) { slice in
            SectorMark(
                angle: .value(Text(verbatim: slice.legendText), slice.plot),
                innerRadius: .ratio(0.6),
                angularInset: 1.5
            )
            .cornerRadius(2)
            .foregroundStyle(Color.hx(slice.slot))
        }
        .chartLegend(.hidden)
        .frame(height: 170)
        .accessibilityHidden(true)
    }
}

/// A line per series. The x value is the server's own bucket text: it is zoneless, and
/// `yyyy-MM-dd HH:mm:ss` orders itself as text, so no local-time round trip is involved
/// (`harnax-ios/HARNESS-NOTES.md` contract conventions; `TokenStatsAggregationResponse.kt:96-104`).
private struct TokenLineChart: View {
    let series: [TokenTrendSeries]
    let measure: TokenMeasure

    private var timeLabel: String { hx("monitor.axis.time") }

    var body: some View {
        Chart {
            ForEach(series) { item in
                ForEach(item.points) { point in
                    LineMark(
                        x: .value(Text(verbatim: timeLabel), point.timePoint),
                        y: .value(Text(verbatim: item.legendText), point.value),
                        series: .value(Text(verbatim: item.legendText), item.id)
                    )
                    .foregroundStyle(Color.hx(item.slot))
                    .lineStyle(StrokeStyle(lineWidth: 2))
                    .interpolationMethod(.catmullRom)
                    PointMark(
                        x: .value(Text(verbatim: timeLabel), point.timePoint),
                        y: .value(Text(verbatim: item.legendText), point.value)
                    )
                    .foregroundStyle(Color.hx(item.slot))
                    .symbolSize(24)
                }
            }
        }
        .chartLegend(.hidden)
        .chartXAxis {
            AxisMarks { value in
                AxisGridLine()
                AxisValueLabel {
                    if let bucket = value.as(String.self) {
                        Text(verbatim: labels[bucket] ?? bucket)
                            .font(.caption2)
                    }
                }
            }
        }
        .chartYAxis {
            AxisMarks(position: .leading) { value in
                AxisGridLine()
                AxisValueLabel {
                    if let number = value.as(Double.self) {
                        Text(verbatim: measure.formatted(value: number))
                            .font(.caption2)
                    }
                }
            }
        }
    }

    /// The axis label for each bucket, taken from the points themselves rather than recomputed here — the view
    /// model already sliced the server text once, by granularity.
    private var labels: [String: String] {
        var map: [String: String] = [:]
        for item in series {
            for point in item.points { map[point.timePoint] = point.label }
        }
        return map
    }
}

/// A legend line: hue, name, and the two numbers the console puts next to it.
private struct TokenLegendLine: View {
    let slot: PaletteSlot
    let title: String
    let detail: String?
    let trailing: String

    var body: some View {
        HStack(spacing: 8) {
            HXStatusDot(tone: slot)
            VStack(alignment: .leading, spacing: 1) {
                Text(verbatim: title)
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.textPrimary))
                    .lineLimit(1)
                if let detail {
                    Text(verbatim: detail)
                        .font(.caption2)
                        .foregroundStyle(Color.hx(.textTertiary))
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 8)
            Text(verbatim: trailing)
                .font(.footnote.monospacedDigit())
                .foregroundStyle(Color.hx(.textSecondary))
                .lineLimit(1)
        }
    }
}

/// A line chart's legend chip.
private struct HXStack: View {
    let slot: PaletteSlot
    let title: String

    var body: some View {
        HStack(spacing: 5) {
            HXStatusDot(tone: slot)
            Text(verbatim: title)
                .font(.caption)
                .foregroundStyle(Color.hx(.textSecondary))
                .lineLimit(1)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(Color.hxFill(slot, alpha: 0.10), in: RoundedRectangle(cornerRadius: 6, style: .continuous))
    }
}

extension TokenWindowPreset {
    static func at(_ index: Int) -> TokenWindowPreset {
        allCases[min(max(index, 0), allCases.count - 1)]
    }
}

extension TokenGranularity {
    static func at(_ index: Int) -> TokenGranularity {
        allCases[min(max(index, 0), allCases.count - 1)]
    }
}

extension TokenMeasure {
    static func at(_ index: Int) -> TokenMeasure {
        allCases[min(max(index, 0), allCases.count - 1)]
    }
}

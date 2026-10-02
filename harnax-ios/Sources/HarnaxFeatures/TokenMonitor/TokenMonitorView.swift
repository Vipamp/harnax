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

    /// For a caller that owns the model — the appearance walkthrough, which has to mount the numeric reading of
    /// a trend block directly because choosing it is a tap and `simctl` injects none.
    public init(vm: TokenMonitorViewModel) {
        _vm = StateObject(wrappedValue: vm)
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

    /// The three filters, each a label on the left and its menu on the right — the shape the app's other
    /// settings rows use, and the one that keeps them at the top where they always were. Only the first two
    /// reach the network: the window and the bucket are query parameters, while the measure picks a column out
    /// of rows already in hand, which is why changing it must not start a request.
    private var filters: some View {
        HXGroupCard {
            filterRow("monitor.filter.range", selection: $vm.range, options: TokenWindowPreset.allCases)
            filterRow("monitor.filter.granularity", selection: $vm.granularity, options: TokenGranularity.allCases)
            filterRow(
                "monitor.filter.measure",
                selection: $vm.measure,
                options: TokenMeasure.allCases,
                divider: false
            )
        }
    }

    /// One labelled row, one menu. The option list is the enum's own `allCases`, so a menu cannot promise a
    /// bucket the server would downgrade — the four granularity codes are the four the route accepts
    /// (`TokenStatsController.kt:77`) and their raw values are what goes in the query.
    private func filterRow<Option: TokenFilterOption & Hashable>(
        _ titleKey: String,
        selection: Binding<Option>,
        options: [Option],
        divider: Bool = true
    ) -> some View {
        HXRow(titleKey, divider: divider) {
            Picker(selection: selection) {
                ForEach(options, id: \.self) { option in
                    Text(verbatim: hx(option.titleKey)).tag(option)
                }
            } label: {
                HXText(titleKey)
            }
            .pickerStyle(.menu)
            .tint(Color.hx(.brand))
        }
    }

    /// The state a tappable legend entry reports in words. The dimming plus the strike-through are the visual
    /// answer, and neither of them reaches someone using VoiceOver.
    private static func legendValue(_ isHidden: Bool) -> String {
        hx(isHidden ? "monitor.legend.hidden" : "monitor.legend.shown")
    }

    /// A line chart's chosen bucket, round-tripped through the view model.
    ///
    /// Only a real pick is forwarded: Swift Charts writes `nil` when the touch lifts, and honouring that would
    /// close the readout the instant the finger came off — the handset's replacement for a hover tooltip has to
    /// outlast the tap. `pickBucket` toggles, so a second tap on the same bucket is how it closes.
    private func bucketBinding(for chart: TokenMonitorViewModel.LegendChart) -> Binding<String?> {
        Binding(
            get: { vm.selectedBuckets[chart] },
            set: { if let bucket = $0 { vm.pickBucket(bucket, in: chart) } }
        )
    }

    /// The form chosen for one trend block, as the segment id `HXSegmented` reads and writes. The mapping lives
    /// here rather than in the view model because it belongs to the control (`TrendForm.at(_:)` clamps, so an
    /// id the enum no longer holds cannot be selected).
    private func formBinding(for chart: TokenMonitorViewModel.LegendChart) -> Binding<Int> {
        Binding(
            get: { TokenMonitorViewModel.TrendForm.allCases.firstIndex(of: vm.form(for: chart)) ?? 0 },
            set: { vm.setForm(TokenMonitorViewModel.TrendForm.at($0), for: chart) }
        )
    }

    /// The switch between the line and its numbers, one per block and directly under its title — the same
    /// rhythm the filters card sets. Per block rather than for the page, because a reader who needs the numbers
    /// usually needs them for the lines that are hard to tell apart, not for all four charts at once.
    private func trendFormPicker(for chart: TokenMonitorViewModel.LegendChart) -> some View {
        HXSegmented(
            TokenMonitorViewModel.TrendForm.allCases.enumerated()
                .map { HXSegmentOption(id: $0.offset, $0.element.titleKey) },
            selection: formBinding(for: chart)
        )
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
        donut("monitor.pie.model", chart: .modelPie, slices: vm.modelSlices)
        donut("monitor.pie.agent", chart: .agentPie, slices: vm.agentSlices)
        donut("monitor.pie.session", chart: .sessionPie, slices: vm.sessionSlices)
    }

    @ViewBuilder
    private func donut(
        _ titleKey: String,
        chart: TokenMonitorViewModel.LegendChart,
        slices: [TokenShareSlice]
    ) -> some View {
        switch vm.state(for: chart.block) {
        case .loading:
            HXCard { HXStateView(.loading) }
        case let .failed(message):
            blockFailure(titleKey, message: message, block: chart.block)
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
                        let drawn = vm.visibleSlices(slices, in: chart)
                        if drawn.isEmpty {
                            HXText("monitor.legend.allHidden")
                                .font(.footnote)
                                .foregroundStyle(Color.hx(.textSecondary))
                        } else {
                            TokenDonutChart(slices: drawn) { angle in
                                guard let picked = vm.slice(atAngle: angle, in: chart) else { return }
                                vm.drill(picked, in: chart)
                            }
                        }
                        if let drilled = vm.drilledSlice(in: chart) {
                            TokenSliceDetail(slice: drilled)
                        }
                        VStack(alignment: .leading, spacing: 6) {
                            ForEach(slices) { slice in
                                let off = vm.isHidden(slice.id, in: chart)
                                Button {
                                    vm.toggleLegend(slice.id, in: chart)
                                } label: {
                                    TokenLegendLine(
                                        slot: slice.slot,
                                        title: slice.legendText,
                                        detail: slice.detail,
                                        trailing: slice.share + " · " + slice.value,
                                        isHidden: off
                                    )
                                }
                                .buttonStyle(.plain)
                                .accessibilityValue(Self.legendValue(off))
                                .accessibilityAction(named: Text(verbatim: hx("monitor.legend.detail"))) {
                                    vm.drill(slice, in: chart)
                                }
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
        trend("monitor.trend.overall", chart: .overallTrend, series: vm.overallSeries)
        trend("monitor.trend.model", chart: .modelTrend, series: vm.modelSeries)
        trend("monitor.trend.agent", chart: .agentTrend, series: vm.agentSeries)
        trend("monitor.trend.session", chart: .sessionTrend, series: vm.sessionSeries)
    }

    @ViewBuilder
    private func trend(
        _ titleKey: String,
        chart: TokenMonitorViewModel.LegendChart,
        series: [TokenTrendSeries]
    ) -> some View {
        switch vm.state(for: chart.block) {
        case .loading:
            HXCard { HXStateView(.loading) }
        case let .failed(message):
            blockFailure(titleKey, message: message, block: chart.block)
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
                        trendFormPicker(for: chart)
                        let drawn = vm.visibleSeries(series, in: chart)
                        if drawn.isEmpty {
                            HXText("monitor.legend.allHidden")
                                .font(.footnote)
                                .foregroundStyle(Color.hx(.textSecondary))
                        } else {
                            switch vm.form(for: chart) {
                            case .chart:
                                TokenLineChart(
                                    series: drawn,
                                    measure: vm.measure,
                                    selectedBucket: bucketBinding(for: chart)
                                )
                                .frame(height: 190)
                                let readings = vm.readings(in: chart)
                                if let bucket = vm.readoutBucket(in: chart), !readings.isEmpty {
                                    TokenBucketReadout(bucket: bucket, readings: readings)
                                }
                            case .list:
                                TokenTrendNumberList(rows: vm.trendListRows(in: chart))
                            }
                        }
                        HXFlow(spacing: 10) {
                            ForEach(series) { item in
                                let off = vm.isHidden(item.id, in: chart)
                                Button {
                                    vm.toggleLegend(item.id, in: chart)
                                } label: {
                                    HXStack(slot: item.slot, title: item.legendText, isHidden: off)
                                }
                                .buttonStyle(.plain)
                                .accessibilityValue(Self.legendValue(off))
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
    let onPick: (Double) -> Void

    /// The touched position as a running total of the sector values, which is what `chartAngleSelection` reports
    /// for a ring. Nilled as soon as it is read: were it left set, a second tap on the same wedge would be no
    /// change at all, and the panel that wedge opened could never be closed by tapping it again.
    @State private var rawAngle: Double?

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
        .chartAngleSelection(value: $rawAngle)
        .onChange(of: rawAngle) { _, angle in
            guard let angle else { return }
            rawAngle = nil
            onPick(angle)
        }
        .frame(height: 170)
        .accessibilityHidden(true)
    }
}

/// The row under a wedge that was tapped: the four figures the aggregation read already carried for it.
///
/// This is what a tap buys, and the console has nothing equivalent — its legend is a plain list and neither of
/// its pie tooltips (`harnax-webui/src/pages/token-monitor/index.tsx:403-412`, `:521-530`) shows more than the
/// one column currently plotted. The input/output split and the fee of the same row are in the reply and the
/// ring can only draw one of them, so the tap resolves the other three.
private struct TokenSliceDetail: View {
    let slice: TokenShareSlice

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                HXStatusDot(tone: slice.slot)
                Text(verbatim: slice.legendText)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Color.hx(.textPrimary))
                    .lineLimit(1)
                Spacer(minLength: 6)
                HXChip(slice.share, tone: slice.slot)
            }
            Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 6) {
                GridRow {
                    figure("monitor.card.input", slice.breakdown.input)
                    figure("monitor.card.output", slice.breakdown.output)
                }
                GridRow {
                    figure("monitor.card.total", slice.breakdown.total)
                    figure("monitor.card.fee", slice.breakdown.fee)
                }
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.hx(.surfaceAlt), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }

    private func figure(_ titleKey: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HXText(titleKey)
                .font(.caption2)
                .foregroundStyle(Color.hx(.textTertiary))
            Text(verbatim: value)
                .font(.footnote.weight(.medium).monospacedDigit())
                .foregroundStyle(Color.hx(.textPrimary))
                .lineLimit(1)
        }
    }
}

/// The numbers of every drawn line at the bucket a tap landed on.
///
/// The console answers the same question with a hover tooltip that carries one row per series
/// (`harnax-webui/src/pages/token-monitor/index.tsx:642-650`); a finger has no hover, so the content becomes a
/// panel under the chart that stays until another tap moves it or the same bucket is tapped again. A line
/// switched off is absent from it, because it is absent from the chart too — and so is a line that has no point
/// in that bucket, which the server can send for a dimension that was quiet that day.
private struct TokenBucketReadout: View {
    let bucket: String
    let readings: [TokenBucketReading]

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(verbatim: bucket)
                .font(.caption2)
                .foregroundStyle(Color.hx(.textTertiary))
            ForEach(readings) { reading in
                HStack(spacing: 8) {
                    HXStatusDot(tone: reading.slot)
                    Text(verbatim: reading.name)
                        .font(.footnote)
                        .foregroundStyle(Color.hx(.textPrimary))
                        .lineLimit(1)
                    Spacer(minLength: 8)
                    Text(verbatim: reading.value)
                        .font(.footnote.monospacedDigit())
                        .foregroundStyle(Color.hx(.textSecondary))
                        .lineLimit(1)
                }
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.hx(.surfaceAlt), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

/// A line per series. The x value is the server's own bucket text: it is zoneless, and
/// `yyyy-MM-dd HH:mm:ss` orders itself as text, so no local-time round trip is involved
/// (`harnax-ios/HARNESS-NOTES.md` contract conventions; `TokenStatsAggregationResponse.kt:96-104`).
private struct TokenLineChart: View {
    let series: [TokenTrendSeries]
    let measure: TokenMeasure
    /// The bucket whose numbers are read out under the chart. Bound to the view model rather than to local
    /// state, so a second tap on the same bucket closes it, a hidden line drops out of the readout with the line
    /// itself, and a reload that retires the bucket takes the marker with it.
    let selectedBucket: Binding<String?>

    private var timeLabel: String { hx("monitor.axis.time") }

    var body: some View {
        Chart {
            if let bucket = selectedBucket.wrappedValue, labels[bucket] != nil {
                RuleMark(x: .value(Text(verbatim: timeLabel), bucket))
                    .foregroundStyle(Color.hx(.textTertiary))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 3]))
                    .zIndex(-1)
            }
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
        .chartXSelection(value: selectedBucket)
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

/// The numbers a trend block holds, in place of its line — the reading `DESIGN.md` §10 asks for, and the only
/// way onto these figures that does not begin by seeing a stroke.
///
/// One stacked block per bucket rather than a `Grid` of one column per series: a dimension name and a
/// `¥12345.00` both have to survive 320 points, and a column grid answers a width it cannot fit by clipping —
/// which for a numeric alternative is the same as taking the number away. The value keeps its own line and is
/// never truncated; a name may take a second line before it gives anything up. Each bucket is one VoiceOver
/// element, so a reader hears `09-22 qwen-max 1.50K gpt-4o 20` as one fact about one bucket rather than four
/// fragments.
private struct TokenTrendNumberList: View {
    let rows: [TokenTrendListRow]

    var body: some View {
        if rows.isEmpty {
            // The list only comes back with no row when the reply carried no bucket at all — switching one
            // series off leaves the others' buckets standing. A block moved to numbers that has none still has
            // to say so, rather than show a blank panel where the numbers were promised.
            HXText("monitor.trend.empty")
                .font(.footnote)
                .foregroundStyle(Color.hx(.textSecondary))
        } else {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(rows) { row in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(verbatim: row.label)
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(Color.hx(.textTertiary))
                        ForEach(row.values) { value in
                            HStack(alignment: .firstTextBaseline, spacing: 8) {
                                HXStatusDot(tone: value.slot)
                                Text(verbatim: value.name)
                                    .font(.footnote)
                                    .foregroundStyle(Color.hx(.textPrimary))
                                    .lineLimit(2)
                                Spacer(minLength: 8)
                                // The figure carries this form, so it takes the strongest text token the card
                                // has rather than the readout's secondary one — and it keeps its own width.
                                Text(verbatim: value.value)
                                    .font(.footnote.monospacedDigit())
                                    .foregroundStyle(Color.hx(.textPrimary))
                                    .fixedSize(horizontal: true, vertical: false)
                            }
                        }
                    }
                    .padding(.vertical, 8)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityElement(children: .combine)
                    if row.id != rows.last?.id {
                        Color.hx(.separator).frame(height: 1)
                    }
                }
            }
        }
    }
}

/// A legend line: hue, name, and the two numbers the console puts next to it.
///
/// Switched off reads the way ECharts draws an unselected legend entry — dimmed, with the name struck through —
/// so the row stays in the list and still says what it would hide.
private struct TokenLegendLine: View {
    let slot: PaletteSlot
    let title: String
    let detail: String?
    let trailing: String
    var isHidden = false

    var body: some View {
        HStack(spacing: 8) {
            HXStatusDot(tone: slot)
            VStack(alignment: .leading, spacing: 1) {
                Text(verbatim: title)
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.textPrimary))
                    .strikethrough(isHidden)
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
        .opacity(isHidden ? 0.45 : 1)
    }
}

/// A line chart's legend chip. Switched off it dims and strikes through, staying tappable so the line it mutes
/// can be brought back.
private struct HXStack: View {
    let slot: PaletteSlot
    let title: String
    var isHidden = false

    var body: some View {
        HStack(spacing: 5) {
            HXStatusDot(tone: slot)
            Text(verbatim: title)
                .font(.caption)
                .foregroundStyle(Color.hx(.textSecondary))
                .strikethrough(isHidden)
                .lineLimit(1)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(Color.hxFill(slot, alpha: 0.10), in: RoundedRectangle(cornerRadius: 6, style: .continuous))
        .opacity(isHidden ? 0.45 : 1)
    }
}


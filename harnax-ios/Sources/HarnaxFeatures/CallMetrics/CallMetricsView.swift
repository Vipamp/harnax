import SwiftUI
import Charts
import HarnaxCore
import HarnaxKit

/// The value a row of the call table pushes: one subject, read at one window, under one dimension.
///
/// The console keeps the same eleven facts in two component states and re-derives the detail predicate from them
/// every time the drawer opens (`harnax-webui/src/pages/call-metrics/index.tsx:62-66,114-148`). Here they travel
/// as one `Hashable` value, because that is what a push needs, and because a drawer that outlives the query it
/// came from is the defect that file names itself (`index.tsx:105-112`): a moved table with a frozen list beside
/// it. Carrying the window and the dimension in the value is what makes that impossible — the pushed screen asks
/// for the interval the row was counted over and nothing else.
///
/// `window` is `Equatable` but not `Hashable`, so conformance is written rather than derived: two refs are the
/// same subject when every field matches, and the hash takes the day count, which is all of a window that a query
/// can depend on.
public struct CallMetricsSubjectRef: Hashable, Sendable {
    /// The id the detail predicate is built from, and the reason a row without one is not pushable
    /// (`ToolMetrics.swift:43-45,71-73`).
    public let subjectId: Int64?
    /// The row's key: the session id by session, the id as text by mcp or cli, the tool name by tool
    /// (`ToolMetrics.swift:40-42`). A session dimension has no id to carry, so its key is all the predicate gets.
    public let subjectKey: String
    /// The title the pushed screen puts in its own header — already fallen back to the key by the server
    /// (`ToolMetrics.swift:48-51`).
    public let title: String
    public let toolName: String
    /// The row's own origin text, kept as text: an origin this app cannot name still filters
    /// (`index.tsx:128`, `CallMetricsLabels.kind(_:)`).
    public let kind: String
    public let group: CallMetricsGroup
    /// The screen's origin filter, which the console uses as the predicate's fallback for a row that carries no
    /// origin of its own (`index.tsx:126-128`).
    public let filterKind: CallMetricsKind?
    public let window: CallMetricsWindow

    public init(
        subjectId: Int64?,
        subjectKey: String,
        title: String,
        toolName: String,
        kind: String,
        group: CallMetricsGroup,
        filterKind: CallMetricsKind?,
        window: CallMetricsWindow
    ) {
        self.subjectId = subjectId
        self.subjectKey = subjectKey
        self.title = title
        self.toolName = toolName
        self.kind = kind
        self.group = group
        self.filterKind = filterKind
        self.window = window
    }

    public static func == (lhs: CallMetricsSubjectRef, rhs: CallMetricsSubjectRef) -> Bool {
        lhs.subjectId == rhs.subjectId
            && lhs.subjectKey == rhs.subjectKey
            && lhs.title == rhs.title
            && lhs.toolName == rhs.toolName
            && lhs.kind == rhs.kind
            && lhs.group == rhs.group
            && lhs.filterKind == rhs.filterKind
            && lhs.window == rhs.window
    }

    public func hash(into hasher: inout Hasher) {
        hasher.combine(subjectId)
        hasher.combine(subjectKey)
        hasher.combine(title)
        hasher.combine(toolName)
        hasher.combine(kind)
        hasher.combine(group)
        hasher.combine(filterKind)
        hasher.combine(window.days)
    }

    /// The detail predicate, column for column the console's own (`index.tsx:123-136`).
    ///
    /// Each branch is the one the server can read: the tool dimension filters by tool name, a row whose origin is
    /// `mcp` or `cli` filters by that id, the agent dimension by its id, the session dimension by its key. The
    /// origin falls back to the screen's filter, because a detail-dimension row carries none
    /// (`ToolMetricsServiceImpl.kt:204-212`) and an empty string would drop the predicate altogether and list
    /// every origin bucket under this row (`index.tsx:126-127`).
    public func invocationQuery(num: Int = 1, size: Int = 20, outcome: CallInvocationOutcome? = nil)
        -> CallInvocationQuery {
        CallInvocationQuery(
            window: window,
            kind: CallMetricsKind(rawValue: kind) ?? filterKind,
            toolName: group == .tool ? toolName : nil,
            mcpId: kind == CallMetricsKind.mcp.rawValue ? subjectId : nil,
            cliId: kind == CallMetricsKind.cli.rawValue ? subjectId : nil,
            agentId: group == .agent ? subjectId : nil,
            sessionId: group == .session ? subjectKey : nil,
            outcome: outcome,
            num: num,
            size: size
        )
    }
}

/// Call metrics: how often tools, MCP servers and CLI packages were called, over a window, per subject.
///
/// Layout is the console's, top to bottom — the filters, the four numbers, the trend, the grouped table
/// (`harnax-webui/src/pages/call-metrics/index.tsx:340-511`). What a handset changes is the shape of the last
/// block: the console's six-column table becomes one card per subject, and the drawer a row opens becomes a
/// pushed second screen.
///
/// The chart is Swift Charts, and it draws the reply it was given: three series folded out of the server's
/// zero-filled buckets, with no gap logic added on this side (`CallMetricsTrend`), and no `.animation` anywhere,
/// because two independent replies landing one after another would otherwise redraw the same line twice
/// (`harnax-ios/specs/03-system-domain.md:334`).
public struct CallMetricsView: View {
    @StateObject private var vm: CallMetricsViewModel
    private let catalog: any ToolMetricsReading

    public init(catalog: any ToolMetricsReading) {
        self.catalog = catalog
        _vm = StateObject(wrappedValue: CallMetricsViewModel(catalog: catalog))
    }

    public var body: some View {
        content
            .harnaxScreen()
            .navigationTitle(Text(verbatim: hx("monitor.calls.title")))
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
            .navigationDestination(for: CallMetricsSubjectRef.self) { ref in
                CallInvocationsView(subject: ref, catalog: catalog)
            }
    }

    @ViewBuilder
    private var content: some View {
        switch vm.phase {
        case .loading:
            HXStateView(.loading)
        case .empty:
            HXStateView(
                .empty,
                message: hx(vm.emptyKey),
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
                trendBlock
                tableBlock
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, HXLayout.tabBarClearance)
        }
    }

    // MARK: - filters

    /// The dimension switch, then the two arms that name an interval and a bucket.
    ///
    /// The dimension is first and is a segment row rather than a menu, because it is the control that changes what
    /// every row on the screen *means* (`dimensions.ts:1-19`) — and it is drawn inline here, inside this screen's
    /// own scroll, not handed down from a tab shell.
    private var filters: some View {
        VStack(spacing: 10) {
            HXSegmented(
                CallMetricsGroup.allCases.enumerated()
                    .map { HXSegmentOption(id: $0.offset, $0.element.titleKey) },
                selection: groupBinding
            )
            HXGroupCard {
                HXRow("monitor.filter.range") {
                    Picker(selection: windowBinding) {
                        ForEach(
                            Array(CallMetricsWindows.presets.enumerated()), id: \.offset
                        ) { index, preset in
                            Text(verbatim: CallMetricsWindows.title(preset)).tag(index)
                        }
                    } label: {
                        HXText("monitor.filter.range")
                    }
                    .pickerStyle(.menu)
                    .tint(Color.hx(.brand))
                }
                kindControl
            }
        }
    }

    /// The origin arm, or the sentence that replaces it where the dimension has made it inert.
    @ViewBuilder
    private var kindControl: some View {
        if vm.showsKindNote {
            HXRow(
                "monitor.calls.filter.kind",
                subtitle: hx("monitor.calls.kind.inert"),
                divider: false
            )
        } else {
            HXRow("monitor.calls.filter.kind", divider: false) {
                Picker(selection: kindBinding) {
                    ForEach(Array(kindArms.enumerated()), id: \.offset) { index, arm in
                        Text(verbatim: CallMetricsLabels.kindArm(arm)).tag(index)
                    }
                } label: {
                    HXText("monitor.calls.filter.kind")
                }
                .pickerStyle(.menu)
                .tint(Color.hx(.brand))
            }
        }
    }

    /// `nil` first, so "every origin" is an arm rather than the absence of one.
    private var kindArms: [CallMetricsKind?] {
        [nil] + CallMetricsKind.allCases
    }

    private var groupBinding: Binding<Int> {
        Binding(
            get: { CallMetricsGroup.allCases.firstIndex(of: vm.group) ?? 0 },
            set: { vm.group = CallMetricsGroup.allCases[safe: $0] ?? .tool }
        )
    }

    /// The window arm is an index into the contract's own preset list, which is the only way this control can
    /// offer a span the screen knows how to name (`CallMetricsWindows.presets`).
    private var windowBinding: Binding<Int> {
        Binding(
            get: { CallMetricsWindows.presets.firstIndex(of: vm.window) ?? 1 },
            set: { index in
                let preset = CallMetricsWindows.presets[safe: index] ?? .last30Days
                if preset != vm.window { vm.window = preset }
            }
        )
    }

    private var kindBinding: Binding<Int> {
        Binding(
            get: { kindArms.firstIndex(of: vm.kind) ?? 0 },
            set: { vm.kind = kindArms[safe: $0] ?? nil }
        )
    }

    // MARK: - the four numbers

    /// The cards are the summary read and nothing else, so a failed summary is exactly these four numbers missing
    /// — the trend below keeps its own answer (`ToolMetricsServiceImpl.kt:46-86`).
    @ViewBuilder
    private var cardsBlock: some View {
        switch vm.state(for: .summary) {
        case .loading:
            HXCard { HXStateView(.loading) }
        case let .failed(message):
            blockFailure("monitor.calls.cards", message: message, block: .summary)
        case .loaded:
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 10)], spacing: 10) {
                ForEach(vm.cards) { card in
                    CallMetricsCardTile(card: card)
                }
            }
        }
    }

    // MARK: - the trend

    @ViewBuilder
    private var trendBlock: some View {
        switch vm.state(for: .trend) {
        case .loading:
            HXCard { HXStateView(.loading) }
        case let .failed(message):
            blockFailure("monitor.calls.trend.title", message: message, block: .trend)
        case .loaded:
            HXCard {
                VStack(alignment: .leading, spacing: 12) {
                    HXText("monitor.calls.trend.title")
                        .font(.headline)
                        .foregroundStyle(Color.hx(.textPrimary))
                    if vm.buckets.isEmpty {
                        HXText(vm.trendEmptyKey)
                            .font(.footnote)
                            .foregroundStyle(Color.hx(.textSecondary))
                    } else {
                        HXSegmented(
                            CallMetricsViewModel.TrendForm.allCases.enumerated()
                                .map { HXSegmentOption(id: $0.offset, $0.element.titleKey) },
                            selection: formBinding
                        )
                        switch vm.trendForm {
                        case .chart:
                            CallMetricsTrendChart(buckets: vm.buckets)
                                .frame(height: 190)
                        case .list:
                            CallMetricsTrendList(buckets: vm.buckets)
                        }
                        legend
                        bucketNote
                    }
                }
            }
        }
    }

    /// The three series' names, in the order the lines are drawn. Not a toggle: this screen narrows nothing by
    /// hand, and switching a series off would leave a sum on the cards that the chart no longer shows.
    private var legend: some View {
        HXFlow(spacing: 10) {
            ForEach(CallMetricsSeries.allCases) { series in
                HStack(spacing: 5) {
                    HXStatusDot(tone: series.slot)
                    HXText(series.titleKey)
                        .font(.caption)
                        .foregroundStyle(Color.hx(.textSecondary))
                }
            }
        }
    }

    /// The answered bucket size, in words: `auto` is what goes out and what comes back is the service's decision,
    /// so the screen reports that rather than its own request (`ToolMetrics.swift:193-196`). No sentence at all
    /// while the trend has not answered — a caption naming no bucket would describe a line that is not there.
    @ViewBuilder
    private var bucketNote: some View {
        if let label = CallMetricsLabels.granularity(vm.answeredGranularity ?? "") {
            Text(verbatim: hx("monitor.calls.bucket.note", label))
                .font(.caption2)
                .foregroundStyle(Color.hx(.textTertiary))
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var formBinding: Binding<Int> {
        Binding(
            get: { CallMetricsViewModel.TrendForm.allCases.firstIndex(of: vm.trendForm) ?? 0 },
            set: { vm.trendForm = CallMetricsViewModel.TrendForm.at($0) }
        )
    }

    // MARK: - the subjects

    @ViewBuilder
    private var tableBlock: some View {
        switch vm.state(for: .summary) {
        case .loading:
            EmptyView()
        case let .failed(message):
            blockFailure("monitor.calls.section.subjects", message: message, block: .summary)
        case .loaded:
            VStack(alignment: .leading, spacing: 10) {
                HXSectionHeader("monitor.calls.section.subjects")
                if vm.showsDetailNote {
                    HXBanner(
                        "monitor.calls.detail.note",
                        systemImage: "hourglass",
                        tone: .indigo
                    )
                }
                if vm.rows.isEmpty {
                    // The window answered and named nobody: that is the empty of this block, and it is not the
                    // read failing (`index.tsx:494-496`).
                    HXCard {
                        HXText(vm.emptyKey)
                            .font(.footnote)
                            .foregroundStyle(Color.hx(.textSecondary))
                    }
                } else {
                    // Row identity is the index, as in the draft queue
                    // (`Sources/HarnaxFeatures/SkillDrafts/SkillDraftListView.swift:133-139`): the contract's row
                    // id can collide, and two cards under one `ForEach` identity would drop one.
                    ForEach(Array(vm.rows.enumerated()), id: \.offset) { index, row in
                        CallMetricsSubjectCard(row: row, ref: vm.ref(at: index))
                    }
                }
                Text(verbatim: vm.windowNote)
                    .font(.caption)
                    .foregroundStyle(Color.hx(.textTertiary))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    /// A block that did not answer says so where it would have drawn, and offers its own retry rather than making
    /// the reader reload the page.
    @ViewBuilder
    private func blockFailure(
        _ titleKey: String,
        message: String,
        block: CallMetricsViewModel.Block
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

/// One of the four numbers, with the console's own tooltip as a line of copy under it
/// (`index.tsx:156-173`) — a tooltip a finger cannot hover becomes text or becomes nothing.
private struct CallMetricsCardTile: View {
    let card: CallMetricsSummaryCard

    var body: some View {
        HXCard {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 6) {
                    HXStatusDot(tone: card.kind.slot)
                    HXText(card.kind.titleKey)
                        .font(.footnote)
                        .foregroundStyle(Color.hx(.textSecondary))
                        .lineLimit(2)
                }
                Text(verbatim: card.value)
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(Color.hx(.textPrimary))
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .accessibilityLabel(hx(card.kind.titleKey))
                if let hintKey = card.kind.hintKey {
                    HXText(hintKey)
                        .font(.caption2)
                        .foregroundStyle(Color.hx(.textTertiary))
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }
}

/// The three series over the window's buckets.
///
/// The x value is the server's own bucket text: it is zoneless and `yyyy-MM-dd HH:mm:ss` orders itself as text,
/// so no local-time round trip is involved — the same reason `TokenLineChart` plots the string
/// (`Sources/HarnaxFeatures/TokenMonitor/TokenMonitorView.swift:551-553`). Every bucket the reply carries is
/// plotted, including the ones that hold nothing, because the service zero-filled them on purpose
/// (`ToolMetricsServiceImpl.kt:110-127`).
private struct CallMetricsTrendChart: View {
    let buckets: [CallMetricsBucket]

    private var timeLabel: String { hx("monitor.axis.time") }

    var body: some View {
        Chart {
            ForEach(CallMetricsSeries.allCases) { series in
                ForEach(buckets) { bucket in
                    LineMark(
                        x: .value(Text(verbatim: timeLabel), bucket.timePoint),
                        y: .value(Text(verbatim: hx(series.titleKey)), series.value(of: bucket)),
                        series: .value(Text(verbatim: hx(series.titleKey)), series.id)
                    )
                    .foregroundStyle(Color.hx(series.slot))
                    .lineStyle(StrokeStyle(lineWidth: 2))
                    .interpolationMethod(.catmullRom)
                    PointMark(
                        x: .value(Text(verbatim: timeLabel), bucket.timePoint),
                        y: .value(Text(verbatim: hx(series.titleKey)), series.value(of: bucket))
                    )
                    .foregroundStyle(Color.hx(series.slot))
                    .symbolSize(20)
                }
            }
        }
        .chartLegend(.hidden)
        .chartXAxis {
            AxisMarks { value in
                AxisGridLine()
                AxisValueLabel {
                    if let bucket = value.as(String.self) {
                        Text(verbatim: buckets.first(where: { $0.timePoint == bucket })?.label ?? bucket)
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
                        Text(verbatim: CallMetricsFigures.count(Int64(number)))
                            .font(.caption2)
                    }
                }
            }
        }
        .accessibilityHidden(true)
    }
}

/// The same three series as numbers, in the buckets' own order — the reading `DESIGN.md` §10 asks for and the
/// only way onto these figures that does not begin by seeing a stroke (`CallMetricsTrendList`'s reason is
/// `TokenTrendNumberList`'s: a phone has no hover and no width for a line that crosses two others).
private struct CallMetricsTrendList: View {
    let buckets: [CallMetricsBucket]

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(buckets) { bucket in
                VStack(alignment: .leading, spacing: 5) {
                    Text(verbatim: bucket.label)
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(Color.hx(.textTertiary))
                    ForEach(CallMetricsSeries.allCases) { series in
                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                            HXStatusDot(tone: series.slot)
                            HXText(series.titleKey)
                                .font(.footnote)
                                .foregroundStyle(Color.hx(.textPrimary))
                            Spacer(minLength: 8)
                            // A zero is a zero the server answered, not a gap: it prints as `0` rather than as an
                            // empty cell (`ToolMetricsServiceImpl.kt:114-117`).
                            Text(verbatim: series.formatted(of: bucket))
                                .font(.footnote.monospacedDigit())
                                .foregroundStyle(Color.hx(.textSecondary))
                                .lineLimit(1)
                        }
                    }
                }
                .padding(.vertical, 7)
                if bucket.id != buckets.last?.id {
                    Color.hx(.separator).frame(height: 1)
                }
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// One subject as a card: the name, the two chips, the four figures, and the push the row's own id earns.
///
/// A row the contract says cannot be drilled renders identically with the link simply not there — the same
/// shape as `SkillDraftRowCard` (`Sources/HarnaxFeatures/SkillDrafts/SkillDraftListView.swift:170-209`), and for
/// the same reason: a control that promises to open something it cannot serve is worse than no control.
struct CallMetricsSubjectCard: View {
    let row: CallMetricsSubjectRow
    let ref: CallMetricsSubjectRef?

    var body: some View {
        if let ref {
            NavigationLink(value: ref) { card }
                .buttonStyle(.plain)
        } else {
            card
        }
    }

    private var card: some View {
        HXCard {
            VStack(alignment: .leading, spacing: 7) {
                Text(verbatim: row.title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.hx(.textPrimary))
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                if let parent = row.parent {
                    Text(verbatim: parent)
                        .font(.caption)
                        .foregroundStyle(Color.hx(.textSecondary))
                        .lineLimit(1)
                }
                chips
                figures
                stamp
            }
        }
        .overlay(alignment: .topTrailing) {
            if row.isDrillable {
                HXChevron()
                    .padding(14)
            }
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private var chips: some View {
        if !row.chips.isEmpty {
            HXFlow(spacing: 6) {
                ForEach(row.chips) { chip in
                    HXChip(chip.label, tone: chip.tone)
                        .accessibilityLabel(Text(verbatim: hx(chip.voiceKey)))
                }
            }
        }
    }

    /// The four figures of the row itself. `calls` and the success rate first, because those two are what the
    /// console's own drill-down hangs off (`index.tsx:246-270`); the two durations follow in the table's order
    /// (`:272-283`).
    private var figures: some View {
        Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 6) {
            GridRow {
                figure("monitor.calls.column.calls", row.calls)
                figure("monitor.calls.column.rate", row.rate)
            }
            GridRow {
                figure("monitor.calls.column.average", row.average)
                figure("monitor.calls.column.p95", row.p95)
            }
        }
    }

    /// The failures count in words, and only where the row has any: a zero-failure row says nothing about
    /// failures, and a card that printed `0 failed` beside every figure would read as a warning
    /// (`ToolMetrics.swift:56-58,70`).
    private var stamp: some View {
        Text(verbatim: stampLine)
            .font(.caption2)
            .foregroundStyle(row.hasFailures ? Color.hx(.danger) : Color.hx(.textTertiary))
            .fixedSize(horizontal: false, vertical: true)
    }

    private var stampLine: String {
        row.hasFailures
            ? hx("monitor.calls.row.failed", row.failures, row.successes)
            : hx("monitor.calls.row.succeeded", row.successes, row.calls)
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

private extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}

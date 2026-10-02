import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// One fixed instant for the whole file, so the window the five requests name is a claim about the code rather
/// than about when the test ran.
private let trendListInstant = Date(timeIntervalSince1970: 1_760_000_000)

/// `DESIGN.md` §10's third accessibility demand: a chart has to offer its numbers as a list, so that reading a
/// value off a trend block does not begin by seeing a line.
///
/// What is asserted here is the data behind that list and the choice that turns it on — one row per bucket, the
/// block's own formatter on every number, the legend honoured, the choice owned by one block and surviving a
/// reload. Deliberately not pixels: the list reuses the same resolved cells the tap readout already draws, and
/// whether a stacked row survives 320 points is a screenshot question these fakes cannot answer.
@MainActor
final class TokenMonitorTrendListTests: XCTestCase {
    private func viewModel() -> (TokenMonitorViewModel, FakeTokenStats) {
        let catalog = FakeTokenStats()
        return (TokenMonitorViewModel(catalog: catalog, now: { trendListInstant }), catalog)
    }

    private func queue(
        _ catalog: FakeTokenStats,
        _ block: TokenMonitorViewModel.Block,
        _ rows: [TokenTimePoint]
    ) {
        catalog.trendReplies[block] = [.success(rows)]
    }

    /// A payload big enough that the seven numbers come through, so the page is in `.content` rather than
    /// `.empty` and the aggregation read has an answer for each round asked.
    private func payload(rounds: Int = 1) -> [Result<TokenStatsPayload, APIError>] {
        Array(
            repeating: .success(TokenStub.payload(input: 600_000, output: 100_000, total: 700_000, fee: 12, models: 1)),
            count: rounds
        )
    }

    /// Two dimensions of one block across three buckets, with a token column that exercises all three of the
    /// abbreviations (`1.50K`, `20.00K`, `1.50M`) and a fee column on every row so the fee measure has the same
    /// buckets to plot.
    private func twoSeriesAcrossThreeBuckets() -> [TokenTimePoint] {
        [
            TokenStub.point("2026-09-22 00:00:00", name: "qwen-max", total: 1_500, fee: 4),
            TokenStub.point("2026-09-22 00:00:00", name: "gpt-4o", total: 10, fee: 1),
            TokenStub.point("2026-09-23 00:00:00", name: "qwen-max", total: 20_000, fee: 9),
            TokenStub.point("2026-09-23 00:00:00", name: "gpt-4o", total: 20, fee: 2),
            TokenStub.point("2026-09-24 00:00:00", name: "qwen-max", total: 1_500_000, fee: 12),
            TokenStub.point("2026-09-24 00:00:00", name: "gpt-4o", total: 30, fee: 3),
        ]
    }

    // MARK: - the rows

    func testEveryBucketBecomesOneRowOfTheBlocksOwnFormattedNumbers() async {
        let (vm, catalog) = viewModel()
        catalog.aggregationReplies = payload()
        queue(catalog, .modelTrend, twoSeriesAcrossThreeBuckets())
        await vm.refresh()

        XCTAssertEqual(vm.form(for: .modelTrend), .chart, "the block stays the chart until the reader says otherwise")
        vm.setForm(.list, for: .modelTrend)
        XCTAssertEqual(vm.form(for: .modelTrend), .list)

        let rows = vm.trendListRows(in: .modelTrend)
        XCTAssertEqual(rows.map(\.id), ["2026-09-22 00:00:00", "2026-09-23 00:00:00", "2026-09-24 00:00:00"])
        XCTAssertEqual(
            rows.map(\.label),
            ["09-22", "09-23", "09-24"],
            "the point's own axis text, sliced by granularity — no date re-read on this side"
        )
        XCTAssertEqual(
            rows.map { $0.values.map(\.name) },
            [["qwen-max", "gpt-4o"], ["qwen-max", "gpt-4o"], ["qwen-max", "gpt-4o"]]
        )
        XCTAssertEqual(
            rows.map { $0.values.map(\.value) },
            [["1.50K", "10"], ["20.00K", "20"], ["1.50M", "30"]],
            "the block's own abbreviation and not the raw number the line is plotted from"
        )
        // The hue the legend chip carries travels into the row, so a reader can still tie a number to a line.
        XCTAssertEqual(rows[0].values.map(\.slot), [.brand, .warning])
    }

    func testTheFeeMeasureRunsTheSameRowsThroughTheBlocksOwnFeeFormatter() async {
        let (vm, catalog) = viewModel()
        catalog.aggregationReplies = payload()
        queue(catalog, .modelTrend, twoSeriesAcrossThreeBuckets())
        await vm.refresh()

        vm.measure = .fee
        XCTAssertEqual(vm.trendListRows(in: .modelTrend).map { $0.values.map(\.value) }, [
            ["¥4.00", "¥1.00"],
            ["¥9.00", "¥2.00"],
            ["¥12.00", "¥3.00"],
        ])
    }

    func testTheOverallBlockListsTheColumnsItsLineDrawsAndASingleBucketIsOneRow() async {
        let (vm, catalog) = viewModel()
        catalog.aggregationReplies = payload()
        queue(catalog, .trend, [
            TokenStub.point("2026-09-22 00:00:00", input: 60, output: 40, total: 100, fee: 7)
        ])
        await vm.refresh()

        // The single-point case: one row, and it holds all three of the lines the token measure draws.
        let rows = vm.trendListRows(in: .overallTrend)
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows.first?.label, "09-22")
        XCTAssertEqual(rows.first?.values.map(\.name), [
            hx("monitor.series.input"), hx("monitor.series.output"), hx("monitor.series.total")
        ], "the overall series are named by the app's own keys, as the legend chip names them")
        XCTAssertEqual(rows.first?.values.map(\.value), ["60", "40", "100"])

        // Under the fee measure the overall line is one series, so the row is one cell.
        vm.measure = .fee
        XCTAssertEqual(vm.trendListRows(in: .overallTrend).first?.values.map(\.value), ["¥7.00"])
    }

    func testTheListSaysWhatTheTappedReadoutSaysForTheSameBucket() async {
        let (vm, catalog) = viewModel()
        catalog.aggregationReplies = payload()
        queue(catalog, .modelTrend, twoSeriesAcrossThreeBuckets())
        await vm.refresh()
        vm.pickBucket("2026-09-23 00:00:00", in: .modelTrend)

        // Two readings of one block must not drift into two sets of numbers: the list is the chart's alternative,
        // not a second calculation.
        let row = vm.trendListRows(in: .modelTrend).first { $0.id == "2026-09-23 00:00:00" }
        XCTAssertEqual(row?.label, vm.readoutBucket(in: .modelTrend))
        XCTAssertEqual(row?.values.map(\.name), vm.readings(in: .modelTrend).map(\.name))
        XCTAssertEqual(row?.values.map(\.value), vm.readings(in: .modelTrend).map(\.value))
    }

    func testTheRowsRunInBucketOrderEvenWhenTheReplyDoesNot() async {
        let (vm, catalog) = viewModel()
        catalog.aggregationReplies = payload()
        queue(catalog, .modelTrend, [
            TokenStub.point("2026-09-24 00:00:00", name: "qwen-max", total: 300),
            TokenStub.point("2026-09-22 00:00:00", name: "qwen-max", total: 100),
            TokenStub.point("2026-09-23 00:00:00", name: "qwen-max", total: 200),
        ])
        await vm.refresh()

        // The server text orders itself as text, which is the same claim the chart's x axis rests on.
        XCTAssertEqual(vm.trendListRows(in: .modelTrend).map(\.label), ["09-22", "09-23", "09-24"])
        XCTAssertEqual(vm.trendListRows(in: .modelTrend).map { $0.values.first?.value }, ["100", "200", "300"])
    }

    func testABucketOneLineWasQuietAtCarriesNoCellForThatLine() async {
        let (vm, catalog) = viewModel()
        catalog.aggregationReplies = payload()
        queue(catalog, .modelTrend, [
            TokenStub.point("2026-09-22 00:00:00", name: "qwen-max", total: 100),
            TokenStub.point("2026-09-22 00:00:00", name: "gpt-4o", total: 10),
            // No gpt-4o row on 09-23: the line has nothing to plot there and neither does the list.
            TokenStub.point("2026-09-23 00:00:00", name: "qwen-max", total: 200),
        ])
        await vm.refresh()

        let rows = vm.trendListRows(in: .modelTrend)
        XCTAssertEqual(rows.count, 2)
        XCTAssertEqual(rows[0].values.map(\.name), ["qwen-max", "gpt-4o"])
        XCTAssertEqual(rows[1].values.map(\.name), ["qwen-max"])
        XCTAssertEqual(rows[1].values.map(\.value), ["200"])
    }

    func testABlockWithNothingToPlaceHasNoRowsAtAll() async {
        let (vm, catalog) = viewModel()
        catalog.aggregationReplies = payload()
        // A null `timePoint` and a blank one are the two shapes that keep a row off the axis entirely
        // (`TokenStatsAggregationResponse.kt:241`), and a row the line cannot place is no number to read either.
        queue(catalog, .agentTrend, [
            TokenStub.point(nil, name: "翻译官", total: 5_000),
            TokenStub.point("   ", name: "翻译官", total: 6_000),
        ])
        await vm.refresh()

        XCTAssertEqual(vm.agentSeries.count, 0)
        XCTAssertEqual(vm.trendListRows(in: .agentTrend), [])
    }

    // MARK: - the block states

    /// A block that did not answer has no line and therefore no numbers: the card draws its failure note instead
    /// of the picker, so rows out of an absent reply would be figures the server never sent.
    func testAFailedBlockAnswersNoRowsForItsChart() async {
        let (vm, catalog) = viewModel()
        catalog.aggregationReplies = payload()
        catalog.trendReplies[.modelTrend] = [.failure(.offline)]
        await vm.refresh()

        if case .failed = vm.state(for: .modelTrend) {} else {
            XCTFail("the block was asked to fail, so its card has to say so: \(vm.state(for: .modelTrend))")
        }
        XCTAssertTrue(vm.modelSeries.isEmpty)
        XCTAssertEqual(vm.trendListRows(in: .modelTrend), [])
    }

    /// The stronger half of the same rule: a retry that fails must take back the rows that block was already
    /// listing. Anything else leaves numbers on screen under a card that says it cannot answer them.
    func testARetryThatFailsTakesBackTheRowsTheBlockWasListing() async {
        let (vm, catalog) = viewModel()
        catalog.aggregationReplies = payload()
        queue(catalog, .modelTrend, twoSeriesAcrossThreeBuckets())
        queue(catalog, .agentTrend, TokenStub.series(named: "翻译官", [100, 200]))
        await vm.refresh()
        vm.setForm(.list, for: .modelTrend)
        XCTAssertEqual(vm.trendListRows(in: .modelTrend).count, 3)

        catalog.trendReplies[.modelTrend] = [.failure(.offline)]
        await vm.retry(.modelTrend)

        if case .failed = vm.state(for: .modelTrend) {} else {
            XCTFail("the retry was queued to fail: \(vm.state(for: .modelTrend))")
        }
        XCTAssertEqual(vm.trendListRows(in: .modelTrend), [], "the three rows it listed are gone with the reply")
        // One block's failed retry is that block's own news: the other chart keeps the numbers it answered.
        XCTAssertEqual(vm.trendListRows(in: .agentTrend).map(\.label), ["09-22", "09-23"])
    }

    /// An empty window is the page's own judgement, not four charts of zeroes (`updatePhase` reads
    /// `grandTotalToken == 0`), so no chart on it has a number to read either.
    func testAnEmptyWindowListsNoNumbersForAnyChart() async {
        let (vm, catalog) = viewModel()
        catalog.aggregationReplies = [.success(TokenStub.payload())]
        for block in FakeTokenStats.trendBlocks { queue(catalog, block, []) }
        await vm.refresh()

        XCTAssertEqual(vm.phase, .empty)
        for chart in [
            TokenMonitorViewModel.LegendChart.overallTrend, .modelTrend, .agentTrend, .sessionTrend,
        ] {
            XCTAssertEqual(vm.trendListRows(in: chart), [], "\(chart) has no number to read in an empty window")
        }
    }

    // MARK: - the legend governs the list too

    func testASwitchedOffLineDropsOutOfEveryRowAsItDropsOutOfTheLine() async {
        let (vm, catalog) = viewModel()
        catalog.aggregationReplies = payload()
        queue(catalog, .modelTrend, twoSeriesAcrossThreeBuckets())
        await vm.refresh()
        XCTAssertEqual(vm.trendListRows(in: .modelTrend).map { $0.values.count }, [2, 2, 2])

        vm.toggleLegend("gpt-4o", in: .modelTrend)

        let rows = vm.trendListRows(in: .modelTrend)
        // The buckets stay — they are the other line's buckets — and only the switched-off column's cells go.
        XCTAssertEqual(rows.map(\.label), ["09-22", "09-23", "09-24"])
        XCTAssertEqual(rows.map { $0.values.map(\.name) }, [["qwen-max"], ["qwen-max"], ["qwen-max"]])
        XCTAssertEqual(rows.map { $0.values.map(\.value) }, [["1.50K"], ["20.00K"], ["1.50M"]])

        vm.toggleLegend("gpt-4o", in: .modelTrend)
        XCTAssertEqual(vm.trendListRows(in: .modelTrend).map { $0.values.count }, [2, 2, 2])
    }

    func testSwitchingEveryLineOffLeavesNoRowToRead() async {
        let (vm, catalog) = viewModel()
        catalog.aggregationReplies = payload()
        queue(catalog, .modelTrend, twoSeriesAcrossThreeBuckets())
        await vm.refresh()

        vm.toggleLegend("qwen-max", in: .modelTrend)
        vm.toggleLegend("gpt-4o", in: .modelTrend)

        XCTAssertEqual(vm.trendListRows(in: .modelTrend), [], "a chart drawing nothing has no numbers to list either")
    }

    // MARK: - the choice

    func testTheFormIsOneBlocksOwnDecisionAndCostsNoRequest() async {
        let (vm, catalog) = viewModel()
        catalog.aggregationReplies = payload()
        for block in FakeTokenStats.trendBlocks {
            queue(catalog, block, TokenStub.series(named: "qwen-max", [100, 200]))
        }
        await vm.refresh()

        let before = catalog.totalRequestCount
        vm.setForm(.list, for: .modelTrend)

        XCTAssertEqual(vm.form(for: .modelTrend), .list)
        for chart in [TokenMonitorViewModel.LegendChart.overallTrend, .agentTrend, .sessionTrend] {
            XCTAssertEqual(vm.form(for: chart), .chart, "one block moved to numbers does not move the other three")
        }
        // Both readings come from the rows already in hand, the same as the legend toggle and the measure filter.
        XCTAssertEqual(catalog.totalRequestCount, before)
        // And the block left on the chart still answers the very same rows when read as numbers.
        XCTAssertEqual(vm.trendListRows(in: .agentTrend).map(\.label), ["09-22", "09-23"])
    }

    func testAReloadKeepsTheChosenFormWhileTheTogglesThatNamedRowsGo() async throws {
        let (vm, catalog) = viewModel()
        catalog.aggregationReplies = payload(rounds: 2)
        queue(catalog, .modelTrend, twoSeriesAcrossThreeBuckets())
        for block in [TokenMonitorViewModel.Block.trend, .agentTrend, .sessionTrend] {
            queue(catalog, block, TokenStub.series(named: "qwen-max", [100]))
        }
        await vm.refresh()
        vm.setForm(.list, for: .modelTrend)
        vm.toggleLegend("gpt-4o", in: .modelTrend)
        catalog.trendReplies[.modelTrend] = [.success(TokenStub.series(named: "glm-4", [777]))]
        for block in [TokenMonitorViewModel.Block.trend, .agentTrend, .sessionTrend] {
            catalog.trendReplies[block] = [.success(TokenStub.series(named: "qwen-max", [100]))]
        }

        vm.range = .thirtyDays
        try await waitUntil { catalog.requestCount(for: .modelTrend) == 2 }

        // The form names no row of the reply being replaced, so a new window keeps it; the toggle above does name
        // a position in that reply, so that one goes.
        XCTAssertEqual(vm.form(for: .modelTrend), .list)
        XCTAssertEqual(vm.form(for: .agentTrend), .chart)
        XCTAssertTrue(vm.hiddenIDs[.modelTrend]?.isEmpty ?? true)
        XCTAssertEqual(vm.trendListRows(in: .modelTrend).map { $0.values.map(\.name) }, [["glm-4"]])
        XCTAssertEqual(vm.trendListRows(in: .modelTrend).map { $0.values.map(\.value) }, [["777"]])
    }

    func testTheTwoFormsMapOntoSegmentIdsAndBackAndCarryCopyInBothLanguages() {
        XCTAssertEqual(TokenMonitorViewModel.TrendForm.allCases.map(\.id), ["chart", "list"])
        // What `HXSegmented` writes is an index, and the clamping is why a menu cannot select a form that is gone.
        XCTAssertEqual(TokenMonitorViewModel.TrendForm.at(0), .chart)
        XCTAssertEqual(TokenMonitorViewModel.TrendForm.at(1), .list)
        XCTAssertEqual(TokenMonitorViewModel.TrendForm.at(-3), .chart)
        XCTAssertEqual(TokenMonitorViewModel.TrendForm.at(9), .list)
        for form in TokenMonitorViewModel.TrendForm.allCases {
            XCTAssertNotEqual(hx(form.titleKey), form.titleKey, "\(form.titleKey) is missing from a catalogue")
        }
    }

    // MARK: - plumbing

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }
}

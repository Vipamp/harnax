import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// One fixed instant for the whole file, so "the five requests name the same window" is a claim about the code
/// rather than about how long the test took to run.
private let tokenTestInstant = Date(timeIntervalSince1970: 1_760_000_000)

/// E5 — the behaviour worth guarding on this screen is that five independent reads stay independent: one window
/// sampled once and named on all five requests, a bucket change that costs four requests rather than five, a
/// measure change that costs none, one blocked chart that does not take the seven numbers down with it, and an
/// old window's reply that never lands on a newer screen.
///
/// The wire shapes are covered elsewhere (`TokenStatsContractTests` reads the real envelopes,
/// `TokenStatsEndpointTests` the real URLs); this file is only about what the view model does with them.
@MainActor
final class TokenMonitorViewModelTests: XCTestCase {
    private func viewModel() -> (TokenMonitorViewModel, FakeTokenStats) {
        let catalog = FakeTokenStats()
        let vm = TokenMonitorViewModel(catalog: catalog, now: { tokenTestInstant })
        return (vm, catalog)
    }

    /// The window the screen has to name for a preset: `end` at the sampled instant, `start` a whole number of
    /// days before it.
    private func window(days: Int, granularity: TokenGranularity? = nil) -> TokenRead {
        TokenRead(
            start: hxWallClockString(tokenTestInstant.addingTimeInterval(TimeInterval(-days * 86_400))),
            end: hxWallClockString(tokenTestInstant),
            granularity: granularity
        )
    }

    /// Scripts one chart's reply. The other three charts and the aggregation read stay unqueued, and the fake
    /// answers an unqueued request as a decoding failure rather than as a silent success.
    private func queue(
        _ catalog: FakeTokenStats,
        _ block: TokenMonitorViewModel.Block,
        _ rows: [TokenTimePoint]
    ) {
        catalog.trendReplies[block] = [.success(rows)]
    }

    /// The seven numbers of a window that holds something, and one non-empty reply per chart.
    private func queueEverywhere(
        _ catalog: FakeTokenStats,
        rounds: Int = 1,
        chartRows: [TokenTimePoint] = TokenStub.buckets([100])
    ) {
        catalog.aggregationReplies = Array(
            repeating: .success(TokenStub.payload(
                input: 600_000, output: 100_000, total: 700_000, fee: 12, agents: 3, sessions: 11, models: 4
            )),
            count: rounds
        )
        for block in FakeTokenStats.trendBlocks {
            catalog.trendReplies[block] = Array(repeating: .success(chartRows), count: rounds)
        }
    }

    // MARK: - the five reads

    func testBeforeTheFirstReadThePageClaimsNothing() {
        let (vm, catalog) = viewModel()
        XCTAssertEqual(vm.phase, .loading)
        XCTAssertTrue(vm.cards.isEmpty)
        XCTAssertTrue(vm.overallSeries.isEmpty)
        for block in TokenMonitorViewModel.Block.allCases {
            XCTAssertEqual(vm.state(for: block), .loading)
        }
        XCTAssertEqual(catalog.totalRequestCount, 0)
    }

    func testTheFiveRoutesShareOneWindowSampledFromOneInstant() async {
        let (vm, catalog) = viewModel()
        queueEverywhere(catalog)
        await vm.refresh()

        XCTAssertEqual(catalog.aggregationRequests, [window(days: 7)])
        for block in FakeTokenStats.trendBlocks {
            XCTAssertEqual(catalog.trendRequests(for: block), [window(days: 7, granularity: .day)])
        }
        XCTAssertEqual(catalog.totalRequestCount, 5)
        XCTAssertEqual(vm.phase, .content)
        for block in TokenMonitorViewModel.Block.allCases {
            XCTAssertEqual(vm.state(for: block), .loaded)
        }
    }

    func testAnEmptyWindowIsTheServerReadingEmptyNotAClientInvention() async {
        let (vm, catalog) = viewModel()
        catalog.aggregationReplies = [.success(TokenStub.payload())]
        await vm.refresh()

        // The captured zero reply is what `/aggregation` sends when the window holds nothing, and the console
        // renders nothing at all for it (`harnax-webui/src/pages/token-monitor/index.tsx:878`). The cards are
        // still built — the page is empty, not unreadable.
        XCTAssertEqual(vm.phase, .empty)
        XCTAssertEqual(vm.state(for: .aggregate), .loaded)
        XCTAssertEqual(vm.cards.count, 7)
        XCTAssertEqual(vm.card(.total)?.value, "0")
        XCTAssertEqual(catalog.totalRequestCount, 5)
    }

    func testAFiveWayFailureIsTheOnlyThingThatTurnsThePageIntoAnError() async {
        let (vm, catalog) = viewModel()
        // Nothing queued: the fake answers like an unreadable envelope, and all five routes are asked anyway.
        await vm.refresh()

        XCTAssertEqual(catalog.totalRequestCount, 5)
        XCTAssertEqual(vm.phase, .failed(ErrorMessage.text(for: .decoding)))
        for block in TokenMonitorViewModel.Block.allCases {
            XCTAssertEqual(vm.state(for: block), .failed(ErrorMessage.text(for: .decoding)))
        }
    }

    func testAFailedAggregationLeavesTheFourChartsItCanDraw() async {
        let (vm, catalog) = viewModel()
        catalog.aggregationReplies = [.failure(.offline)]
        for block in FakeTokenStats.trendBlocks {
            catalog.trendReplies[block] = [.success(TokenStub.buckets([100, 200]))]
        }
        await vm.refresh()

        XCTAssertEqual(vm.phase, .content)
        XCTAssertTrue(vm.cards.isEmpty)
        XCTAssertTrue(vm.modelSlices.isEmpty)
        XCTAssertEqual(vm.state(for: .aggregate), .failed(ErrorMessage.text(for: .offline)))
        XCTAssertEqual(vm.overallSeries.count, 3)
        XCTAssertEqual(vm.overallSeries.flatMap(\.points).count, 6)
        for block in FakeTokenStats.trendBlocks {
            XCTAssertEqual(vm.state(for: block), .loaded)
        }
    }

    // MARK: - the seven numbers and the three donuts

    func testTheSevenCardsAreTheAggregationsOwnColumnsInTheConsolesOrder() async {
        let (vm, catalog) = viewModel()
        catalog.aggregationReplies = [.success(TokenStub.payload(
            input: 600_000, output: 100_000, total: 700_000, fee: 12, agents: 3, sessions: 11, models: 4
        ))]
        await vm.refresh()

        XCTAssertEqual(vm.cards.map(\.kind), [.fee, .input, .output, .total, .agents, .sessions, .models])
        XCTAssertEqual(vm.card(.fee)?.value, "¥12.00")
        XCTAssertEqual(vm.card(.input)?.value, "600.00K")
        XCTAssertEqual(vm.card(.output)?.value, "100.00K")
        XCTAssertEqual(vm.card(.total)?.value, "700.00K")
        XCTAssertEqual(vm.card(.agents)?.value, "3")
        XCTAssertEqual(vm.card(.sessions)?.value, "11")
        XCTAssertEqual(vm.card(.models)?.value, "4")
        // The percentage badge sits on the input/output split and nowhere else (`index.tsx:192-204`).
        XCTAssertEqual(vm.card(.input)?.share, "85.7%")
        XCTAssertEqual(vm.card(.output)?.share, "14.3%")
        XCTAssertNil(vm.card(.total)?.share)
        XCTAssertNil(vm.card(.fee)?.share)
    }

    func testTheSessionPieKeepsTheTenRowsThatCostTheMost() async {
        let (vm, catalog) = viewModel()
        let rows = (0..<12).map { index in
            TokenStub.session("会话 \(index)", id: "web-\(index)", total: Int64(120_000 - index * 10_000))
        }
        catalog.aggregationReplies = [.success(TokenStub.payload(total: 1_000_000, sessions: 12, sessionRows: rows))]
        await vm.refresh()

        XCTAssertEqual(vm.sessionSlices.count, TokenMonitorViewModel.sessionPieLimit)
        XCTAssertEqual(vm.sessionSlices.map(\.title), (0..<10).map { "会话 \($0)" })
        XCTAssertEqual(vm.sessionSlices.last?.plot, 30_000)
        // The truncated pie still reports a real share: the denominator is the window, not the ten rows drawn
        // (`index.tsx:275-281`).
        XCTAssertEqual(vm.sessionSlices.first?.share, "12.0%")
    }

    func testTheModelPieIsNotTruncatedAndItsHueFollowsTheRowPosition() async {
        let (vm, catalog) = viewModel()
        catalog.aggregationReplies = [.success(TokenStub.payload(
            total: 1_000_000,
            models: 12,
            modelRows: (0..<12).map { TokenStub.model("m\($0)", id: Int64($0), total: 1000) }
        ))]
        await vm.refresh()

        XCTAssertEqual(vm.modelSlices.count, 12)
        XCTAssertEqual(vm.modelSlices.map(\.index), Array(0..<12))
        // The palette has eight slots and the server sends twelve rows: the ninth slice wraps back to the first
        // hue rather than running out of colours, and a refresh keeps each row on the hue its position gives it.
        XCTAssertEqual(vm.modelSlices[0].slot, .brand)
        XCTAssertEqual(vm.modelSlices[7].slot, .textSecondary)
        XCTAssertEqual(vm.modelSlices[8].slot, .brand)
        XCTAssertEqual(vm.modelSlices[9].slot, .warning)
        XCTAssertEqual(vm.modelSlices[0].id, "0-m0")
    }

    // MARK: - the four lines

    func testADimensionReplyBecomesOneLinePerDimensionInBucketOrder() async {
        let (vm, catalog) = viewModel()
        queue(catalog, .modelTrend, [
            TokenStub.point("2026-09-22 00:00:00", name: "qwen-max", total: 100),
            TokenStub.point("2026-09-22 00:00:00", name: "gpt-4o", total: 10),
            TokenStub.point("2026-09-23 00:00:00", name: "qwen-max", total: 200),
            TokenStub.point("2026-09-23 00:00:00", name: "gpt-4o", total: 20),
        ])
        await vm.refresh()

        XCTAssertEqual(vm.modelSeries.map(\.name), ["qwen-max", "gpt-4o"])
        XCTAssertEqual(vm.modelSeries[0].points.map(\.value), [100, 200])
        XCTAssertEqual(vm.modelSeries[1].points.map(\.value), [10, 20])
        XCTAssertEqual(vm.modelSeries[0].points.map(\.label), ["09-22", "09-23"])
        XCTAssertEqual(vm.modelSeries[0].points[0].formatted, "100")
        XCTAssertEqual(vm.modelSeries.map(\.slot), [.brand, .warning])
    }

    func testTheOverallLineIsThreeSeriesUnderTokensAndOneUnderFee() async {
        let (vm, catalog) = viewModel()
        queue(catalog, .trend, [
            TokenStub.point("2026-09-22 00:00:00", input: 60, output: 40, total: 100, fee: 7)
        ])
        await vm.refresh()

        XCTAssertEqual(vm.overallSeries.map(\.id), ["input", "output", "total"])
        XCTAssertEqual(vm.overallSeries.map(\.nameKey), [
            "monitor.series.input", "monitor.series.output", "monitor.series.total"
        ])
        XCTAssertEqual(vm.overallSeries.map(\.name), [nil, nil, nil])

        vm.measure = .fee
        XCTAssertEqual(vm.overallSeries.count, 1)
        XCTAssertEqual(vm.overallSeries[0].id, "fee")
        XCTAssertEqual(vm.overallSeries[0].points.map(\.value), [7])
        XCTAssertEqual(vm.overallSeries[0].points[0].formatted, "¥7.00")
    }

    func testANamelessDimensionIsStillItsOwnLineBecauseTheIdIsTheFallback() async {
        let (vm, catalog) = viewModel()
        // The name comes from a LEFT JOIN (`harnax-entity/src/main/resources/mapper/TokenStatsMapper.xml:74-76`),
        // so a deleted session still costs tokens and arrives with only its id. Two such rows are two series,
        // not one invented sum.
        queue(catalog, .sessionTrend, [
            TokenStub.point("2026-09-22 00:00:00", name: nil, id: "web-1", total: 100),
            TokenStub.point("2026-09-22 00:00:00", name: "   ", id: "web-2", total: 200),
        ])
        await vm.refresh()

        XCTAssertEqual(vm.sessionSeries.map(\.name), ["web-1", "web-2"])
        XCTAssertEqual(vm.sessionSeries.map(\.points.first?.value), [100, 200])
    }

    func testARowWithNeitherNameNorIdIsOneUnnamedLine() async {
        let (vm, catalog) = viewModel()
        queue(catalog, .agentTrend, [
            TokenStub.point("2026-09-22 00:00:00", name: nil, id: nil, total: 100),
            TokenStub.point("2026-09-23 00:00:00", name: nil, id: nil, total: 200),
        ])
        await vm.refresh()

        XCTAssertEqual(vm.agentSeries.count, 1)
        XCTAssertNil(vm.agentSeries[0].name)
        XCTAssertEqual(vm.agentSeries[0].legendText, hx("monitor.row.unnamed"))
        XCTAssertEqual(vm.agentSeries[0].points.count, 2)
    }

    func testARowWithoutABucketTextNeverReachesTheChart() async {
        let (vm, catalog) = viewModel()
        // `timePoint` is nullable on the wire (`TokenStatsAggregationResponse.kt:241`), and a row with no bucket
        // has no place on a time axis — plotting it at position zero would draw a spike the server never made.
        queue(catalog, .trend, [
            TokenStub.point("2026-09-22 00:00:00", total: 100),
            TokenStub.point(nil, total: 999_999),
            TokenStub.point("   ", total: 888_888),
            TokenStub.point("2026-09-23 00:00:00", total: 200),
        ])
        await vm.refresh()

        XCTAssertEqual(vm.overallSeries[2].points.map(\.value), [100, 200])
        XCTAssertEqual(vm.overallSeries[2].points.map(\.timePoint), [
            "2026-09-22 00:00:00", "2026-09-23 00:00:00"
        ])
    }

    // MARK: - what each filter costs

    func testABucketChangeReloadsTheFourChartsAndLeavesTheCardsAlone() async throws {
        let (vm, catalog) = viewModel()
        queueEverywhere(catalog, rounds: 2)
        for block in FakeTokenStats.trendBlocks {
            catalog.trendReplies[block] = [.success(TokenStub.buckets([100])), .success(TokenStub.buckets([555]))]
        }
        await vm.refresh()

        vm.granularity = .week
        try await waitUntil {
            FakeTokenStats.trendBlocks.allSatisfy { catalog.requestCount(for: $0) == 2 }
        }
        try await waitUntil {
            FakeTokenStats.trendBlocks.allSatisfy { vm.state(for: $0) == .loaded }
        }

        // `/aggregation` does not accept the parameter at all (`TokenStatsController.kt:41-49`), so re-asking it
        // would buy one identical reply.
        XCTAssertEqual(catalog.requestCount(for: .aggregate), 1)
        XCTAssertEqual(vm.card(.total)?.value, "700.00K")
        for block in FakeTokenStats.trendBlocks {
            XCTAssertEqual(catalog.trendRequests(for: block).map(\.granularity), [.day, .week])
        }
        // The newer reply is what is drawn now, and the older one is gone rather than appended to it.
        XCTAssertEqual(vm.overallSeries[2].points.map(\.value), [555])
    }

    func testARangeChangeReAsksAllFiveForTheNewerWindow() async throws {
        let (vm, catalog) = viewModel()
        queueEverywhere(catalog, rounds: 2)
        await vm.refresh()

        vm.range = .thirtyDays
        try await waitUntil {
            TokenMonitorViewModel.Block.allCases.allSatisfy { catalog.requestCount(for: $0) == 2 }
        }

        let older = window(days: 7)
        let newer = window(days: 30)
        XCTAssertNotEqual(older.start, newer.start)
        XCTAssertEqual(catalog.aggregationRequests, [older, newer])
        for block in FakeTokenStats.trendBlocks {
            XCTAssertEqual(catalog.trendRequests(for: block), [
                window(days: 7, granularity: .day), window(days: 30, granularity: .day)
            ])
        }
    }

    func testAMeasureSwitchRedrawsWithoutAnotherRequest() async {
        let (vm, catalog) = viewModel()
        catalog.aggregationReplies = [.success(TokenStub.payload(
            input: 600_000, output: 100_000, total: 1_000_000, fee: 20,
            modelRows: [TokenStub.model("qwen-max", id: 1, provider: "aliyun", total: 500_000, fee: 12)]
        ))]
        queue(catalog, .trend, TokenStub.buckets([7], fee: 7))
        await vm.refresh()

        XCTAssertEqual(vm.modelSlices.first?.value, "500.00K")
        XCTAssertEqual(vm.modelSlices.first?.share, "50.0%")
        XCTAssertEqual(vm.modelSlices.first?.detail, "aliyun")

        // Both columns are already in these rows, so the third filter is the one that must not reach the network.
        let before = catalog.totalRequestCount
        vm.measure = .fee
        XCTAssertEqual(catalog.totalRequestCount, before)
        XCTAssertEqual(vm.modelSlices.count, 1)
        XCTAssertEqual(vm.modelSlices.first?.value, "¥12.00")
        XCTAssertEqual(vm.modelSlices.first?.plot, 12)
        XCTAssertEqual(vm.modelSlices.first?.share, "60.0%")
        XCTAssertEqual(vm.overallSeries.count, 1)
    }

    func testARetriedBlockIsTheOnlyBlockAskedAgain() async {
        let (vm, catalog) = viewModel()
        queueEverywhere(catalog)
        await vm.refresh()
        catalog.trendReplies[.agentTrend] = [.success(TokenStub.series(named: "翻译官", [999]))]

        await vm.retry(.agentTrend)

        XCTAssertEqual(catalog.requestCount(for: .agentTrend), 2)
        XCTAssertEqual(catalog.requestCount(for: .aggregate), 1)
        for block in [TokenMonitorViewModel.Block.trend, .modelTrend, .sessionTrend] {
            XCTAssertEqual(catalog.requestCount(for: block), 1)
            XCTAssertEqual(vm.state(for: block), .loaded)
        }
        XCTAssertEqual(vm.agentSeries.map(\.name), ["翻译官"])
        XCTAssertEqual(vm.agentSeries[0].points.map(\.value), [999])
        // The other blocks keep whatever they already answered.
        XCTAssertEqual(vm.overallSeries[2].points.map(\.value), [100])
        XCTAssertEqual(vm.card(.total)?.value, "700.00K")
    }

    func testAReplyToASupersededWindowIsDroppedRatherThanApplied() async throws {
        let (vm, catalog) = viewModel()
        catalog.gateAggregation = true
        catalog.aggregationReplies = [
            .success(TokenStub.payload(total: 111, models: 1)),
            .success(TokenStub.payload(total: 222, models: 2)),
        ]

        let first = Task { await vm.refresh() }
        try await waitUntil { catalog.requestCount(for: .aggregate) == 1 }
        // The page moves to another window while that read is still out.
        vm.range = .thirtyDays
        try await waitUntil { catalog.requestCount(for: .aggregate) == 2 }
        catalog.releaseAggregation()
        await first.value
        try await waitUntil { vm.card(.total) != nil }

        XCTAssertEqual(catalog.requestCount(for: .aggregate), 2)
        XCTAssertEqual(vm.card(.total)?.value, "222")
        XCTAssertEqual(vm.card(.models)?.value, "2")
        XCTAssertEqual(vm.phase, .content)
    }

    func testOnlyTheFourChartBlocksAreBucketSensitive() {
        XCTAssertEqual(TokenMonitorViewModel.Block.allCases.count, 5)
        XCTAssertFalse(TokenMonitorViewModel.Block.aggregate.isGranularitySensitive)
        for block in FakeTokenStats.trendBlocks {
            XCTAssertTrue(block.isGranularitySensitive)
        }
    }

    // MARK: - the interactive legend

    /// Three models of a window that holds a million, so every slice has a distinct share of it.
    private func threeModels() -> [TokenModelStat] {
        [
            TokenStub.model("qwen-max", id: 1, total: 500_000),
            TokenStub.model("gpt-4o", id: 2, total: 300_000),
            TokenStub.model("claude", id: 3, total: 200_000),
        ]
    }

    func testASwitchedOffSliceLeavesTheOtherRowsSharesAndTheSevenNumbersAlone() async {
        let (vm, catalog) = viewModel()
        catalog.aggregationReplies = [.success(TokenStub.payload(
            total: 1_000_000, models: 3, modelRows: threeModels()
        ))]
        await vm.refresh()
        XCTAssertEqual(vm.modelSlices.map(\.share), ["50.0%", "30.0%", "20.0%"])

        vm.toggleLegend("0-qwen-max", in: .modelPie)

        let drawn = vm.visibleSlices(vm.modelSlices, in: .modelPie)
        XCTAssertEqual(drawn.map(\.id), ["1-gpt-4o", "2-claude"])
        // The percentages keep the window as their denominator: a hidden row is still a row the server counted,
        // so switching one off restates only the ring, never the numbers beside it (`index.tsx:275-281`).
        XCTAssertEqual(drawn.map(\.share), ["30.0%", "20.0%"])
        XCTAssertEqual(vm.card(.total)?.value, "1.00M")
        XCTAssertTrue(vm.isHidden("0-qwen-max", in: .modelPie))
    }

    func testSwitchingTheSameEntryBackDrawsEveryRowAgain() async {
        let (vm, catalog) = viewModel()
        catalog.aggregationReplies = [.success(TokenStub.payload(
            total: 1_000_000, models: 3, modelRows: threeModels()
        ))]
        await vm.refresh()

        vm.toggleLegend("1-gpt-4o", in: .modelPie)
        XCTAssertEqual(vm.visibleSlices(vm.modelSlices, in: .modelPie).count, 2)
        vm.toggleLegend("1-gpt-4o", in: .modelPie)

        XCTAssertFalse(vm.isHidden("1-gpt-4o", in: .modelPie))
        XCTAssertEqual(vm.visibleSlices(vm.modelSlices, in: .modelPie).count, 3)
        XCTAssertEqual(vm.modelSlices.count, 3, "the row was never dropped from the reply, only from the ring")
    }

    func testTheThreeDonutsDoNotShareOneSwitchedOffEntryEvenOnTheSameName() async {
        let (vm, catalog) = viewModel()
        // A model and an agent can be named the same thing, and both are the first row of their own list, so both
        // slices carry the id `0-同名`. Muting the pie being read must not mute the pie beside it.
        catalog.aggregationReplies = [.success(TokenStub.payload(
            total: 1_000_000,
            agents: 1,
            models: 1,
            modelRows: [TokenStub.model("同名", id: 1, total: 400_000)],
            agentRows: [TokenStub.agent("同名", id: 7, total: 600_000)]
        ))]
        await vm.refresh()
        XCTAssertEqual(vm.modelSlices.first?.id, "0-同名")
        XCTAssertEqual(vm.agentSlices.first?.id, "0-同名")

        vm.toggleLegend("0-同名", in: .modelPie)

        XCTAssertTrue(vm.visibleSlices(vm.modelSlices, in: .modelPie).isEmpty)
        XCTAssertEqual(vm.visibleSlices(vm.agentSlices, in: .agentPie).count, 1)
        XCTAssertTrue(vm.visibleSlices(vm.sessionSlices, in: .sessionPie).isEmpty)
        XCTAssertFalse(vm.isHidden("0-同名", in: .agentPie))
    }

    func testASwitchedOffLineIsGoneFromItsOwnChartOnly() async {
        let (vm, catalog) = viewModel()
        queue(catalog, .modelTrend, TokenStub.series(named: "qwen-max", [100, 200]))
        queue(catalog, .agentTrend, TokenStub.series(named: "qwen-max", [50, 60]))
        await vm.refresh()

        vm.toggleLegend("qwen-max", in: .modelTrend)

        XCTAssertTrue(vm.visibleSeries(vm.modelSeries, in: .modelTrend).isEmpty)
        XCTAssertEqual(vm.visibleSeries(vm.agentSeries, in: .agentTrend).count, 1)
        // The series is still what the route answered — the toggle is a reading aid, not a filter.
        XCTAssertEqual(vm.modelSeries.count, 1)
    }

    func testAToggleOnItsOwnCostsNoRequest() async {
        let (vm, catalog) = viewModel()
        queueEverywhere(catalog)
        catalog.aggregationReplies = [.success(TokenStub.payload(
            total: 1_000_000, models: 3, modelRows: threeModels()
        ))]
        await vm.refresh()

        let before = catalog.totalRequestCount
        vm.toggleLegend("0-qwen-max", in: .modelPie)
        vm.toggleLegend("input", in: .overallTrend)

        XCTAssertEqual(catalog.totalRequestCount, before)
        XCTAssertEqual(vm.state(for: .aggregate), .loaded)
        XCTAssertEqual(vm.state(for: .trend), .loaded)
    }

    func testAWindowReloadRetiresTheEntriesSwitchedOffWithTheRowsTheyNamed() async throws {
        let (vm, catalog) = viewModel()
        queueEverywhere(catalog, rounds: 2)
        catalog.aggregationReplies = [
            .success(TokenStub.payload(total: 1_000_000, models: 3, modelRows: threeModels())),
            .success(TokenStub.payload(total: 900_000, models: 1, modelRows: [
                TokenStub.model("glm-4", id: 9, total: 900_000)
            ])),
        ]
        await vm.refresh()
        vm.toggleLegend("0-qwen-max", in: .modelPie)
        XCTAssertEqual(vm.visibleSlices(vm.modelSlices, in: .modelPie).count, 2)

        vm.range = .thirtyDays
        try await waitUntil { catalog.requestCount(for: .aggregate) == 2 }
        try await waitUntil { vm.modelSlices.count == 1 }

        // The newer reply's row zero is a different model, and the older id would silently mute it.
        XCTAssertTrue(vm.hiddenIDs[.modelPie]?.isEmpty ?? true)
        XCTAssertEqual(vm.visibleSlices(vm.modelSlices, in: .modelPie).map(\.id), ["0-glm-4"])
    }

    func testABucketChangeRetiresTheLineTogglesAndKeepsTheDonutOnes() async throws {
        let (vm, catalog) = viewModel()
        queueEverywhere(catalog, rounds: 2, chartRows: TokenStub.series(named: "qwen-max", [100]))
        catalog.aggregationReplies = [.success(TokenStub.payload(
            total: 1_000_000, models: 3, modelRows: threeModels()
        ))]
        await vm.refresh()
        vm.toggleLegend("0-qwen-max", in: .modelPie)
        vm.toggleLegend("qwen-max", in: .modelTrend)

        vm.granularity = .week
        try await waitUntil { catalog.requestCount(for: .modelTrend) == 2 }

        // `/aggregation` was never re-asked, so the pie still holds the same rows and the same toggle still
        // means the same row (`TokenStatsController.kt:41-49`).
        XCTAssertTrue(vm.isHidden("0-qwen-max", in: .modelPie))
        XCTAssertEqual(vm.visibleSlices(vm.modelSlices, in: .modelPie).count, 2)
        XCTAssertTrue(vm.hiddenIDs[.modelTrend]?.isEmpty ?? true)
        XCTAssertFalse(vm.isHidden("qwen-max", in: .modelTrend))
    }

    func testAMeasureSwitchKeepsTheEntriesSwitchedOff() async {
        let (vm, catalog) = viewModel()
        catalog.aggregationReplies = [.success(TokenStub.payload(
            total: 1_000_000, fee: 20, models: 3,
            modelRows: [
                TokenStub.model("qwen-max", id: 1, total: 500_000, fee: 12),
                TokenStub.model("gpt-4o", id: 2, total: 300_000, fee: 5),
                TokenStub.model("claude", id: 3, total: 200_000, fee: 3),
            ]
        ))]
        await vm.refresh()
        vm.toggleLegend("0-qwen-max", in: .modelPie)

        // The third filter redraws from the rows already in hand, so the ids — and the toggle on one of them —
        // survive it.
        vm.measure = .fee
        XCTAssertTrue(vm.isHidden("0-qwen-max", in: .modelPie))
        let drawn = vm.visibleSlices(vm.modelSlices, in: .modelPie)
        XCTAssertEqual(drawn.map(\.value), ["¥5.00", "¥3.00"])
        XCTAssertEqual(drawn.map(\.share), ["25.0%", "15.0%"])
    }

    func testEveryChartWithALegendIsOwnedByExactlyOneRead() {
        XCTAssertEqual(TokenMonitorViewModel.LegendChart.allCases.count, 7)
        for chart in [TokenMonitorViewModel.LegendChart.modelPie, .agentPie, .sessionPie] {
            XCTAssertEqual(chart.block, .aggregate)
        }
        XCTAssertEqual(TokenMonitorViewModel.LegendChart.overallTrend.block, .trend)
        for chart in [TokenMonitorViewModel.LegendChart.modelTrend, .agentTrend, .sessionTrend] {
            XCTAssertEqual(chart.block.rawValue, chart.rawValue)
        }
    }

    // MARK: - the drill-down

    /// The ring's own geometry: `chartAngleSelection` reports the touched position as a running total of the
    /// sector values, so the row is the one whose span on the ring holds it. Each model is read at an angle in
    /// the middle of its own wedge, and the two edges of the whole ring are what decide whether a tap can fall
    /// off the end.
    func testTheAngleUnderAFingerPicksTheRowWhoseSpanItCrosses() async {
        let (vm, catalog) = viewModel()
        catalog.aggregationReplies = [.success(TokenStub.payload(
            total: 1_000_000, models: 3, modelRows: threeModels()
        ))]
        await vm.refresh()

        XCTAssertEqual(vm.slice(atAngle: 0, in: .modelPie)?.id, "0-qwen-max")
        XCTAssertEqual(vm.slice(atAngle: 250_000, in: .modelPie)?.id, "0-qwen-max")
        XCTAssertEqual(vm.slice(atAngle: 650_000, in: .modelPie)?.id, "1-gpt-4o")
        XCTAssertEqual(vm.slice(atAngle: 900_000, in: .modelPie)?.id, "2-claude")
        // The far edge of the last wedge is the window total, and a touch there is still that wedge.
        XCTAssertEqual(vm.slice(atAngle: 1_000_000, in: .modelPie)?.id, "2-claude")
        XCTAssertNil(vm.slice(atAngle: 1_000_001, in: .modelPie))
        XCTAssertNil(vm.slice(atAngle: -1, in: .modelPie))
    }

    func testARowWithNothingToPlotTakesNoAngleSoATapSkipsIt() async {
        let (vm, catalog) = viewModel()
        // Under the fee measure a model with no fee draws no wedge at all, and the wedges beside it close up:
        // the angle where it would have been belongs to the next row.
        catalog.aggregationReplies = [.success(TokenStub.payload(
            total: 1_000_000, fee: 15, models: 3,
            modelRows: [
                TokenStub.model("qwen-max", id: 1, total: 500_000, fee: 12),
                TokenStub.model("gpt-4o", id: 2, total: 300_000, fee: 0),
                TokenStub.model("claude", id: 3, total: 200_000, fee: 3),
            ]
        ))]
        await vm.refresh()
        vm.measure = .fee

        XCTAssertEqual(vm.slice(atAngle: 6, in: .modelPie)?.id, "0-qwen-max")
        XCTAssertEqual(vm.slice(atAngle: 13, in: .modelPie)?.id, "2-claude")
        XCTAssertEqual(vm.slice(atAngle: 15, in: .modelPie)?.id, "2-claude")
        XCTAssertNil(vm.slice(atAngle: 16, in: .modelPie))
    }

    func testASwitchedOffRowIsNotWhatATapPicksBecauseItsWedgeIsGone() async {
        let (vm, catalog) = viewModel()
        catalog.aggregationReplies = [.success(TokenStub.payload(
            total: 1_000_000, models: 3, modelRows: threeModels()
        ))]
        await vm.refresh()

        // 600 000 sits inside gpt-4o while the ring draws all three rows ...
        XCTAssertEqual(vm.slice(atAngle: 600_000, in: .modelPie)?.id, "1-gpt-4o")

        // ... and inside claude once gpt-4o is switched off, because the remaining wedges close up.
        vm.toggleLegend("1-gpt-4o", in: .modelPie)
        XCTAssertEqual(vm.slice(atAngle: 600_000, in: .modelPie)?.id, "2-claude")
        XCTAssertNil(vm.slice(atAngle: 800_000, in: .modelPie))
    }

    func testTappingTheRowAlreadyOpenClosesItsPanel() async {
        let (vm, catalog) = viewModel()
        catalog.aggregationReplies = [.success(TokenStub.payload(
            total: 1_000_000, models: 3, modelRows: threeModels()
        ))]
        await vm.refresh()
        let second = vm.modelSlices[1]

        vm.drill(second, in: .modelPie)
        XCTAssertEqual(vm.drilledSlice(in: .modelPie)?.id, second.id)
        vm.drill(second, in: .modelPie)
        XCTAssertNil(vm.drilledSlice(in: .modelPie))
    }

    /// The point of the panel: the ring draws one column, and the tap resolves the other three out of the same
    /// row the aggregation read already sent.
    func testTheOpenedRowShowsItsOwnFourFiguresNotOnlyTheColumnTheRingDrew() async {
        let (vm, catalog) = viewModel()
        catalog.aggregationReplies = [.success(TokenStub.payload(
            total: 1_000_000, fee: 20, models: 3,
            modelRows: [TokenStub.model(
                "qwen-max", id: 1, input: 300_000, output: 200_000, total: 500_000, fee: 12
            )]
        ))]
        await vm.refresh()

        vm.drill(vm.modelSlices[0], in: .modelPie)
        guard let drilled = vm.drilledSlice(in: .modelPie) else { return XCTFail("no row opened") }
        XCTAssertEqual(drilled.value, "500.00K", "the ring's own column")
        XCTAssertEqual(drilled.share, "50.0%")
        XCTAssertEqual(
            [drilled.breakdown.input, drilled.breakdown.output, drilled.breakdown.total, drilled.breakdown.fee],
            ["300.00K", "200.00K", "500.00K", "¥12.00"]
        )

        // Under the fee measure the wedge shrinks to the fee; the four figures underneath stay the same four.
        vm.measure = .fee
        XCTAssertEqual(vm.drilledSlice(in: .modelPie)?.value, "¥12.00")
        XCTAssertEqual(vm.drilledSlice(in: .modelPie)?.breakdown.total, "500.00K")
    }

    func testAnOpenedRowBelongsToItsOwnDonutAlone() async {
        let (vm, catalog) = viewModel()
        catalog.aggregationReplies = [.success(TokenStub.payload(
            total: 1_000_000,
            agents: 1,
            models: 1,
            modelRows: [TokenStub.model("同名", id: 1, total: 400_000)],
            agentRows: [TokenStub.agent("同名", id: 7, total: 600_000)]
        ))]
        await vm.refresh()

        vm.drill(vm.modelSlices[0], in: .modelPie)

        XCTAssertEqual(vm.drilledSlice(in: .modelPie)?.id, "0-同名")
        XCTAssertNil(vm.drilledSlice(in: .agentPie))
        XCTAssertNil(vm.drilledSlice(in: .sessionPie))
    }

    func testSwitchingAnOpenedRowOffTakesItsPanelWithIt() async {
        let (vm, catalog) = viewModel()
        catalog.aggregationReplies = [.success(TokenStub.payload(
            total: 1_000_000, models: 3, modelRows: threeModels()
        ))]
        await vm.refresh()
        vm.drill(vm.modelSlices[0], in: .modelPie)

        vm.toggleLegend("0-qwen-max", in: .modelPie)

        XCTAssertNil(vm.drilledSlice(in: .modelPie), "a row the ring no longer draws has no panel to keep open")
        vm.toggleLegend("0-qwen-max", in: .modelPie)
        XCTAssertNil(vm.drilledSlice(in: .modelPie), "and switching it back on does not reopen the old read")
    }

    // MARK: - the bucket readout

    func testATappedBucketReadsOutEveryLineThatHasAPointThere() async {
        let (vm, catalog) = viewModel()
        queue(catalog, .modelTrend, [
            TokenStub.point("2026-09-22 00:00:00", name: "qwen-max", total: 100, fee: 4),
            TokenStub.point("2026-09-23 00:00:00", name: "qwen-max", total: 200, fee: 9),
            TokenStub.point("2026-09-22 00:00:00", name: "gpt-4o", total: 10, fee: 1),
            // gpt-4o has no 09-23 row at all: a dimension quiet that day contributes nothing to that bucket.
        ])
        await vm.refresh()

        vm.pickBucket("2026-09-22 00:00:00", in: .modelTrend)
        XCTAssertEqual(vm.readoutBucket(in: .modelTrend), "09-22")
        XCTAssertEqual(vm.readings(in: .modelTrend).map(\.name), ["qwen-max", "gpt-4o"])
        XCTAssertEqual(vm.readings(in: .modelTrend).map(\.value), ["100", "10"])

        vm.measure = .fee
        XCTAssertEqual(vm.readings(in: .modelTrend).map(\.value), ["¥4.00", "¥1.00"])
        XCTAssertEqual(vm.readings(in: .modelTrend).map(\.slot), [.brand, .warning])

        vm.pickBucket("2026-09-23 00:00:00", in: .modelTrend)
        XCTAssertEqual(vm.readings(in: .modelTrend).map(\.name), ["qwen-max"])
        XCTAssertEqual(vm.readings(in: .modelTrend).map(\.value), ["¥9.00"])
    }

    func testATapOnTheBucketAlreadyReadOutClosesTheReadout() async {
        let (vm, catalog) = viewModel()
        queue(catalog, .agentTrend, TokenStub.series(named: "翻译官", [100, 200]))
        await vm.refresh()

        vm.pickBucket("2026-09-22 00:00:00", in: .agentTrend)
        XCTAssertFalse(vm.readings(in: .agentTrend).isEmpty)

        vm.pickBucket("2026-09-22 00:00:00", in: .agentTrend)
        XCTAssertNil(vm.readoutBucket(in: .agentTrend))
        XCTAssertTrue(vm.readings(in: .agentTrend).isEmpty)
    }

    func testABucketChosenOnOneChartMarksNoOtherChart() async {
        let (vm, catalog) = viewModel()
        queue(catalog, .modelTrend, TokenStub.series(named: "qwen-max", [100]))
        queue(catalog, .agentTrend, TokenStub.series(named: "翻译官", [100]))
        await vm.refresh()

        vm.pickBucket("2026-09-22 00:00:00", in: .modelTrend)

        XCTAssertEqual(vm.readings(in: .modelTrend).count, 1)
        XCTAssertTrue(vm.readings(in: .agentTrend).isEmpty)
        XCTAssertNil(vm.readoutBucket(in: .agentTrend))
        XCTAssertTrue(vm.readings(in: .overallTrend).isEmpty)
    }

    func testALineSwitchedOffIsAbsentFromTheReadoutItWasIn() async {
        let (vm, catalog) = viewModel()
        queue(catalog, .modelTrend, [
            TokenStub.point("2026-09-22 00:00:00", name: "qwen-max", total: 100),
            TokenStub.point("2026-09-22 00:00:00", name: "gpt-4o", total: 10),
        ])
        await vm.refresh()
        vm.pickBucket("2026-09-22 00:00:00", in: .modelTrend)
        XCTAssertEqual(vm.readings(in: .modelTrend).count, 2)

        vm.toggleLegend("gpt-4o", in: .modelTrend)

        XCTAssertEqual(vm.readings(in: .modelTrend).map(\.name), ["qwen-max"])
    }

    func testAReadoutForABucketTheNewReplyDoesNotHaveSaysNothing() async throws {
        let (vm, catalog) = viewModel()
        queueEverywhere(catalog, rounds: 2, chartRows: TokenStub.series(named: "qwen-max", [100]))
        catalog.aggregationReplies = [
            .success(TokenStub.payload(total: 1_000_000, models: 3, modelRows: threeModels())),
            .success(TokenStub.payload(total: 900_000, models: 1, modelRows: [
                TokenStub.model("glm-4", id: 9, total: 900_000)
            ])),
        ]
        await vm.refresh()
        vm.drill(vm.modelSlices[1], in: .modelPie)
        vm.pickBucket("2026-09-22 00:00:00", in: .modelTrend)

        vm.granularity = .week
        try await waitUntil { catalog.requestCount(for: .modelTrend) == 2 }
        vm.range = .thirtyDays
        try await waitUntil { catalog.requestCount(for: .aggregate) == 2 }
        try await waitUntil { vm.modelSlices.count == 1 }

        // Both were read off the previous reply: the re-asked window has other rows and its own buckets, so the
        // panel and the marker go with them rather than pointing at nothing.
        XCTAssertNil(vm.drilledSlice(in: .modelPie))
        XCTAssertNil(vm.readoutBucket(in: .modelTrend))
        XCTAssertTrue(vm.readings(in: .modelTrend).isEmpty)
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

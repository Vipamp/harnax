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
            start: hxTokenWindowString(tokenTestInstant.addingTimeInterval(TimeInterval(-days * 86_400))),
            end: hxTokenWindowString(tokenTestInstant),
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

    // MARK: - plumbing

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }
}

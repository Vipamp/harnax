import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// Call metrics — what the screen does with two reads that land apart: the pair fired on entry and on every
/// filter, one generation per block so a retried trend cannot retire the summary beside it, the page's judgement
/// read off the *pair* rather than either half, the rows labelled by the dimension the reply says it grouped by,
/// and a push value taken from the row's position because the contract's own row id collides.
///
/// The wire shape is covered by `CallMetricsWireTests`; this file is only about the view model, and the drill-down
/// screen's paging has its own (`CallInvocationsViewModelTests`).
@MainActor
final class CallMetricsViewModelTests: XCTestCase {
    private func viewModel() -> (CallMetricsViewModel, FakeToolMetrics) {
        let catalog = FakeToolMetrics()
        return (CallMetricsViewModel(catalog: catalog), catalog)
    }

    /// The console's own fixture numbers (`App/HarnaxDebugScreens.swift:2776-2785`): 604 calls, 551 of them good,
    /// 53 failures, and a P95 in the open-ended bucket. Two rows — one MCP server with an id, one builtin tool
    /// without — so a card and a push value have something to disagree about.
    private func populated(
        from: String = "2026-09-12 03:00:00",
        to: String = "2026-10-11 20:00:00",
        groupBy: String = "tool",
        rows: [CallMetricsRow]? = nil
    ) throws -> CallMetricsSummary {
        try CallMetricsSummary.stub(
            from: from,
            to: to,
            groupBy: groupBy,
            totalCalls: 604,
            totalSuccesses: 551,
            successRate: 0.9123,
            failingCalls: 53,
            p95Operator: ">",
            p95Ms: 30_000,
            rows: rows ?? [try .stub(), try .idless()]
        )
    }

    /// A window that answered and named nobody: the four cards read as zeros and the table is empty, which is this
    /// screen's「no calls」(`CallMetricsViewModel.swift:354-355`).
    private func bare() throws -> CallMetricsSummary {
        try CallMetricsSummary.stub()
    }

    /// Three points over two hours, two of them sharing one bucket — the shape the read actually sends
    /// (`ToolMetrics.swift:96-99`: one row per bucket × kind × subject × tool).
    private func trend(
        granularity: String = "hour",
        points: [CallMetricsPoint]? = nil
    ) throws -> CallMetricsTimeSeries {
        try CallMetricsTimeSeries.stub(
            granularity: granularity,
            points: points ?? [
                try .stub(timePoint: "2026-10-11 18:00:00", calls: 10, successes: 9, errors: 1),
                try .stub(
                    timePoint: "2026-10-11 18:00:00", kind: "builtin", dimensionId: nil,
                    dimensionName: "read_file", calls: 4, successes: 4
                ),
                try .stub(timePoint: "2026-10-11 19:00:00", calls: 3, successes: 2, errors: 1, denials: 1),
            ]
        )
    }

    /// A trend the *old* window answered: week buckets, so a bucket that reached the screen would be visible in
    /// `granularity` as well as in the time points.
    private func staleTrend() throws -> CallMetricsTimeSeries {
        try trend(
            granularity: "week",
            points: [try .stub(timePoint: "2026-09-07 00:00:00", calls: 99, successes: 99)]
        )
    }

    /// One page, one pair of replies, both awaited: the pair is what `updatePhase` judges
    /// (`CallMetricsViewModel.swift:346-372`).
    private func page(
        summary: Result<CallMetricsSummary, APIError>,
        trend: Result<CallMetricsTimeSeries, APIError>
    ) async throws -> CallMetricsViewModel {
        let (vm, catalog) = viewModel()
        catalog.summaryReplies = [summary]
        catalog.trendReplies = [trend]
        await vm.refresh()
        return vm
    }

    // MARK: - 1. entry: two reads, never one

    /// The console fires both together on entry (`harnax-webui/src/pages/call-metrics/index.tsx:74-103`), the
    /// window arm is the console's default span, and the trend asks `auto` and narrows to no subject
    /// (`CallMetricsViewModel.swift:296-303`).
    func testThePageOpensUnansweredAndFiresExactlyTheTwoReads() async throws {
        let (vm, catalog) = viewModel()
        XCTAssertEqual(vm.window, CallMetricsWindows.default, "30 days, the console's DEFAULT_RANGE_DAYS")
        XCTAssertEqual(vm.group, .tool)
        XCTAssertNil(vm.kind, "the origin arm opens on「all」(`:109-120`)")
        XCTAssertEqual(vm.phase, .loading)
        for block in CallMetricsViewModel.Block.allCases {
            XCTAssertEqual(vm.state(for: block), .loading)
        }
        XCTAssertTrue(vm.cards.isEmpty)
        XCTAssertTrue(vm.rows.isEmpty)
        XCTAssertTrue(vm.buckets.isEmpty)
        XCTAssertNil(vm.answered)
        XCTAssertNil(vm.answeredGranularity)
        XCTAssertEqual(catalog.totalRequestCount, 0, "constructing the page asks for nothing")

        catalog.summaryReplies = [.success(try populated())]
        catalog.trendReplies = [.success(try trend())]
        await vm.refresh()

        XCTAssertEqual(catalog.summaryRequests, [SummaryRead(days: 30, group: .tool, kind: nil)])
        XCTAssertEqual(catalog.trendRequests, [TrendRead(days: 30)])
        XCTAssertEqual(catalog.totalRequestCount, 2, "two routes, and one read per route")
        XCTAssertEqual(vm.state(for: .summary), .loaded)
        XCTAssertEqual(vm.state(for: .trend), .loaded)
        XCTAssertEqual(vm.phase, .content)
    }

    /// Every filter is a change to both halves of the page: the rows are a different question now, and a line
    /// drawn over a table of another subject is the one thing a switch must not leave behind (`:87-120`,
    /// `index.tsx:91-94`).
    func testEveryFilterChangeSendsBothReadsAgain() async throws {
        let (vm, catalog) = viewModel()
        catalog.summaryReplies = [.success(try populated())]
        catalog.trendReplies = [.success(try trend())]
        await vm.refresh()

        vm.window = .last90Days
        try await waitUntil { catalog.totalRequestCount == 4 }
        vm.group = .agent
        try await waitUntil { catalog.totalRequestCount == 6 }
        vm.kind = .shell
        try await waitUntil { catalog.totalRequestCount == 8 }

        XCTAssertEqual(
            catalog.summaryRequests,
            [
                SummaryRead(days: 30, group: .tool),
                SummaryRead(days: 90, group: .tool),
                SummaryRead(days: 90, group: .agent),
                SummaryRead(days: 90, group: .agent, kind: .shell),
            ]
        )
        XCTAssertEqual(
            catalog.trendRequests,
            [
                TrendRead(days: 30),
                TrendRead(days: 90),
                TrendRead(days: 90),
                TrendRead(days: 90, kind: .shell),
            ],
            "the trend goes out with the table every time, and always with `subjectId: nil`"
        )
    }

    /// A no-op write is not a read: each of the three filters has a `guard … != oldValue`, and a control that
    /// re-sent on every redraw would put four reads on the wire for a finger that changed nothing.
    func testWritingTheSameFilterAgainSendsNothing() async throws {
        let (vm, catalog) = viewModel()
        catalog.summaryReplies = [.success(try populated())]
        catalog.trendReplies = [.success(try trend())]
        await vm.refresh()

        vm.window = CallMetricsWindow.last30Days
        vm.group = CallMetricsGroup.tool
        vm.kind = .none
        vm.window = .last7Days
        try await waitUntil { catalog.totalRequestCount == 4 }

        XCTAssertEqual(catalog.summaryRequests.map(\.days), [30, 7])
        XCTAssertEqual(catalog.trendRequests.map(\.days), [30, 7])
        XCTAssertEqual(catalog.summaryRequests.map(\.group), [.tool, .tool])
        XCTAssertNil(catalog.summaryRequests.last?.kind)
        XCTAssertEqual(catalog.totalRequestCount, 4, "three writes that changed nothing cost no read at all")
    }

    // MARK: - 2. one generation per block

    /// The case the per-block counters exist for: a slow trend from the old window landing after the new window's
    /// two requests went out (`:144-150`). It may take none of the screen with it — not a bucket, not the answer
    /// about the bucket size, and not the trend block's own state.
    func testASupersededTrendReplyLandsOnNothing() async throws {
        let (vm, catalog) = viewModel()
        catalog.gate = true
        catalog.summaryReplies = [
            .success(try populated(from: "2026-07-14 00:00:00")),
            .success(try populated(from: "2026-10-05 00:00:00")),
        ]
        // Only the old window's trend is queued: the new one answers as an unreadable reply, so a stale line that
        // wrongly landed would show up as a *loaded* block full of week buckets.
        catalog.trendReplies = [.success(try staleTrend())]

        vm.window = .last90Days
        try await waitUntil { catalog.summaryRequests.count == 1 && catalog.trendRequests.count == 1 }
        vm.window = .last7Days
        try await waitUntil { catalog.summaryRequests.count == 2 && catalog.trendRequests.count == 2 }
        XCTAssertEqual(catalog.summaryRequests.map(\.days), [90, 7], "queue index is request order")
        XCTAssertEqual(catalog.trendRequests.map(\.days), [90, 7])

        catalog.release()
        // Both routes' held replies land one after another, so the wait covers the pair rather than either half.
        try await waitUntil { vm.answered != nil && vm.state(for: .trend) != .loading }

        XCTAssertTrue(vm.buckets.isEmpty, "the 90-day line never reached the 7-day screen")
        XCTAssertNil(vm.answeredGranularity)
        XCTAssertEqual(vm.state(for: .trend), .failed(ErrorMessage.text(for: .decoding)))
        XCTAssertEqual(
            vm.answered?.from,
            "2026-10-05 00:00:00",
            "and the other route still answered the window in force"
        )
        XCTAssertEqual(vm.window, .last7Days)
    }

    /// The other half of `generations: [Block: Int]`: retrying one block retires only that block's replies, and
    /// the summary that is still on the wire lands. One shared counter would retire it mid-flight and leave its
    /// block spinning for good (`:147-150`, `:265-276`).
    func testRetryingOneBlockLeavesTheOtherBlocksReplyInForce() async throws {
        let (vm, catalog) = viewModel()
        catalog.gate = true
        catalog.summaryReplies = [.success(try populated())]
        catalog.trendReplies = [.success(try staleTrend()), .success(try trend())]

        let entry = Task { await vm.refresh() }
        try await waitUntil { catalog.summaryRequests.count == 1 && catalog.trendRequests.count == 1 }
        let retried = Task { await vm.retry(.trend) }
        try await waitUntil { catalog.trendRequests.count == 2 }
        XCTAssertEqual(catalog.summaryRequests.count, 1, "a retry asks for one block and for nothing else")

        catalog.release()
        await entry.value
        await retried.value

        XCTAssertEqual(vm.state(for: .summary), .loaded, "the in-flight summary reply still landed")
        XCTAssertEqual(vm.cards.count, 4)
        XCTAssertEqual(vm.state(for: .trend), .loaded)
        XCTAssertEqual(vm.answeredGranularity, "hour", "the retried trend is the one in force, not the first")
        XCTAssertEqual(vm.buckets.map(\.timePoint), ["2026-10-11 18:00:00", "2026-10-11 19:00:00"])
        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(catalog.trendRequests.count, 2)
    }

    /// A block that goes out again clears what it is about to replace — including the two echoes, because a
    /// footnote describing a read that is no longer on screen is a lie about the interval (`:245-263`).
    func testASecondReadOfABlockClearsOnlyThatBlock() async throws {
        let (vm, catalog) = viewModel()
        catalog.summaryReplies = [.success(try populated())]
        catalog.trendReplies = [.success(try trend())]
        await vm.refresh()
        XCTAssertFalse(vm.cards.isEmpty)
        XCTAssertFalse(vm.buckets.isEmpty)
        XCTAssertNotNil(vm.answered)
        XCTAssertNotNil(vm.answeredGranularity)

        catalog.gate = true
        let retriedTrend = Task { await vm.retry(.trend) }
        try await waitUntil { catalog.trendRequests.count == 2 }
        XCTAssertTrue(vm.buckets.isEmpty, "the old line is gone the moment a new one goes out")
        XCTAssertNil(vm.answeredGranularity)
        XCTAssertEqual(vm.state(for: .trend), .loading)
        XCTAssertEqual(vm.cards.count, 4, "and the block that was not asked for keeps what it answered")
        XCTAssertNotNil(vm.answered)
        XCTAssertEqual(vm.phase, .content, "a table with a spinner under it is still a table")
        catalog.trendReplies = [.success(try trend())]
        catalog.release()
        await retriedTrend.value

        catalog.gate = true
        let retriedSummary = Task { await vm.retry(.summary) }
        try await waitUntil { catalog.summaryRequests.count == 2 }
        XCTAssertTrue(vm.cards.isEmpty)
        XCTAssertTrue(vm.rows.isEmpty)
        XCTAssertNil(vm.answered)
        XCTAssertEqual(vm.buckets.count, 2, "the trend is the summary's reload's business not to touch")
        XCTAssertNotNil(vm.answeredGranularity)
        XCTAssertEqual(vm.phase, .loading, "with the summary out again the page has nothing left to draw")
        catalog.summaryReplies = [.success(try populated())]
        catalog.release()
        await retriedSummary.value
        XCTAssertEqual(vm.cards.count, 4)
        XCTAssertEqual(vm.phase, .content)
    }

    // MARK: - 3. the judgement is the pair

    /// One route's outage is not the page's: the cards and the rows come from the summary, the buckets from the
    /// trend, and a failed summary over an answered trend is a screen with a hole in it, not a broken screen.
    func testAFailedSummaryOverALoadedTrendIsStillContent() async throws {
        let vm = try await page(summary: .failure(.offline), trend: .success(trend()))
        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(vm.state(for: .summary), .failed(ErrorMessage.text(for: .offline)))
        XCTAssertEqual(vm.state(for: .trend), .loaded)
        XCTAssertTrue(vm.cards.isEmpty, "the four numbers do come from the read that failed")
        XCTAssertTrue(vm.rows.isEmpty)
        XCTAssertEqual(vm.buckets.count, 2)
    }

    /// The summary is the different case: with both halves refusing, what is on the page is the summary's own
    /// sentence, and that stays true while the trend has not answered yet (`:360-361`).
    func testAFailedSummaryWithNoOtherAnswerIsThePagesFailure() async throws {
        let (vm, catalog) = viewModel()
        catalog.gateTrend = true
        catalog.summaryReplies = [.failure(.offline)]
        let loading = Task { await vm.refresh() }
        try await waitUntil { vm.state(for: .summary) == .failed(ErrorMessage.text(for: .offline)) }

        XCTAssertEqual(vm.state(for: .trend), .loading)
        XCTAssertEqual(vm.phase, .failed(ErrorMessage.text(for: .offline)))
        catalog.release()
        await loading.value

        XCTAssertEqual(vm.state(for: .trend), .failed(ErrorMessage.text(for: .decoding)))
        XCTAssertEqual(
            vm.phase,
            .failed(ErrorMessage.text(for: .offline)),
            "two outages, and the page names the one that carries the table"
        )
    }

    /// Both loaded and both empty is the only empty the page may claim (`:354-355`, `:374-377`).
    func testThePageIsOnlyEmptyWhenBothReadsAnsweredWithNothing() async throws {
        let nothingAtAll = try await page(summary: .success(bare()), trend: .success(CallMetricsTimeSeries.stub()))
        XCTAssertEqual(nothingAtAll.phase, .empty)
        XCTAssertFalse(nothingAtAll.cards.isEmpty, "empty is a judgement the page still draws numbers under")

        let lineOnly = try await page(summary: .success(bare()), trend: .success(trend()))
        XCTAssertEqual(lineOnly.phase, .content, "a line over a table that named nobody is still a line")

        let tableOnly = try await page(summary: .success(populated()), trend: .success(CallMetricsTimeSeries.stub()))
        XCTAssertEqual(tableOnly.phase, .content, "and rows over an empty grid are still rows")
    }

    /// `.empty` needs the *pair*: a table that came back empty while the trend is still out is content with a
    /// spinner under it, not an empty window (`:362-366`).
    func testALoadedSummaryWithATrendStillOutIsContentAndNotYetEmpty() async throws {
        let (vm, catalog) = viewModel()
        catalog.gateTrend = true
        catalog.summaryReplies = [.success(try bare())]
        let loading = Task { await vm.refresh() }
        try await waitUntil { vm.state(for: .summary) == .loaded }

        XCTAssertEqual(vm.state(for: .trend), .loading)
        XCTAssertEqual(vm.phase, .content)
        XCTAssertTrue(vm.rows.isEmpty)
        XCTAssertTrue(vm.buckets.isEmpty)
        XCTAssertEqual(vm.cards.count, 4, "four zeros are a judgement, not an absence")

        catalog.trendReplies = [.success(try CallMetricsTimeSeries.stub())]
        catalog.release()
        await loading.value
        XCTAssertEqual(vm.phase, .empty, "the pair is in, both hands empty — now the page may say so")
        XCTAssertEqual(vm.emptyKey, "monitor.calls.empty")
        XCTAssertEqual(vm.trendEmptyKey, "monitor.calls.trend.empty")
    }

    /// A window whose table named nobody *and* whose trend failed is a screen with a hole in it: the failure wins,
    /// and it is the trend's own words, because the summary did answer (`:368-369`).
    func testAFailedTrendUnderAnEmptyTableIsAFailureInTheTrendsOwnWords() async throws {
        let vm = try await page(summary: .success(bare()), trend: .failure(.timeout))
        XCTAssertEqual(vm.phase, .failed(ErrorMessage.text(for: .timeout)))
        XCTAssertEqual(vm.state(for: .summary), .loaded)
        XCTAssertEqual(vm.cards.count, 4)
        XCTAssertTrue(vm.rows.isEmpty)
    }

    /// The same outage beside rows is content: the operator has the table, and the trend says for itself what is
    /// missing (`:369`, `BlockState.failed`'s comment at `:38-41`).
    func testAFailedTrendBesideRowsIsStillContent() async throws {
        let vm = try await page(summary: .success(populated()), trend: .failure(.timeout))
        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(vm.state(for: .trend), .failed(ErrorMessage.text(for: .timeout)))
        XCTAssertEqual(vm.state(for: .summary), .loaded)
        XCTAssertEqual(vm.rows.count, 2)
    }

    // MARK: - 4. what a filter means on the wire

    /// On `mcp` and `cli` the dimension *is* an origin bucket, so the service pins the kind and the picked arm
    /// cannot widen the rows (`ToolMetricsMapper.xml:158-168`, `CallMetricsViewModel.swift:162-178`). The request
    /// and the reply have to describe the same set, so the pinning happens on the way out, not only in the copy.
    func testTheMcpAndCliDimensionsPinTheirOwnOrigin() async throws {
        let (vm, catalog) = viewModel()
        vm.kind = .builtin
        try await waitUntil { catalog.totalRequestCount == 2 }

        vm.group = .mcp
        try await waitUntil { catalog.summaryRequests.count == 2 && catalog.trendRequests.count == 2 }
        XCTAssertEqual(vm.effectiveKind, .mcp, "the arm the operator picked is not the arm that goes out")
        XCTAssertTrue(vm.showsKindNote)
        XCTAssertEqual(catalog.summaryRequests[1], SummaryRead(days: 30, group: .mcp, kind: .mcp))
        XCTAssertEqual(catalog.trendRequests[1], TrendRead(days: 30, kind: .mcp))

        vm.group = .cli
        try await waitUntil { catalog.summaryRequests.count == 3 && catalog.trendRequests.count == 3 }
        XCTAssertEqual(vm.effectiveKind, .cli)
        XCTAssertTrue(vm.showsKindNote)
        XCTAssertEqual(catalog.summaryRequests[2], SummaryRead(days: 30, group: .cli, kind: .cli))
        XCTAssertEqual(catalog.trendRequests[2], TrendRead(days: 30, kind: .cli))
    }

    /// The other three dimensions carry the picked arm, and no arm at all is no predicate server-side
    /// (`ToolMetricsController.kt:45`).
    func testTheOtherThreeDimensionsCarryThePickedOrigin() async throws {
        let (vm, catalog) = viewModel()
        XCTAssertEqual(catalog.totalRequestCount, 0, "constructing the page asks for nothing")

        var reads = 0
        vm.kind = .framework
        reads += 2
        try await waitUntil { catalog.totalRequestCount == reads }

        // `.tool` comes last because it is the arm the page opens on: `agent → session → tool` is three writes
        // that all trip the `guard group != oldValue`, which is what makes each of them cost a read.
        for group in [CallMetricsGroup.agent, .session, .tool] {
            vm.group = group
            reads += 2
            try await waitUntil { catalog.totalRequestCount == reads }
            XCTAssertEqual(vm.effectiveKind, .framework, "\(group) is not itself an origin bucket")
            XCTAssertFalse(vm.showsKindNote)
            XCTAssertEqual(catalog.summaryRequests.last?.group, group)
            XCTAssertEqual(catalog.summaryRequests.last?.kind, .framework)
            XCTAssertEqual(catalog.trendRequests.last?.kind, .framework)
        }

        vm.kind = .none
        reads += 2
        try await waitUntil { catalog.totalRequestCount == reads }
        XCTAssertNil(vm.effectiveKind)
        XCTAssertNil(vm.kind)
        XCTAssertNil(catalog.summaryRequests.last?.kind, "「all」is no predicate at all")
        XCTAssertNil(catalog.trendRequests.last?.kind)
    }

    // MARK: - 5. the dimension the reply says it grouped by

    /// The rows are labelled by the reply's `groupBy`, not by the dimension this screen asked for: an unrecognised
    /// `groupBy` falls back to `tool` server-side without failing, and calling those rows `session` would
    /// describe a grouping that did not happen (`:180-184`, `ToolMetrics.swift:18-21`).
    func testTheRowsAreLabelledByTheDimensionTheReplyAnswered() async throws {
        let (vm, catalog) = viewModel()
        vm.group = .session
        try await waitUntil { catalog.totalRequestCount == 2 }
        catalog.summaryReplies = [.success(try populated(
            groupBy: "tool",
            rows: [try .stub(subjectKey: "7", subjectId: 7, subjectName: "browser-use", parentName: "browser-use")]
        ))]
        catalog.trendReplies = [.success(try trend())]
        await vm.refresh()

        XCTAssertEqual(vm.group, .session, "the control still holds what the reader chose")
        XCTAssertEqual(vm.answeredGroup, .tool, "the rows are the dimension the server says it grouped by")
        XCTAssertEqual(vm.rows.first?.parent, "browser-use", "and the card is derived from that dimension")

        catalog.summaryReplies = [.success(try populated(
            groupBy: "mcp",
            rows: [try .stub(subjectKey: "7", subjectId: 7, subjectName: "browser-use", parentName: "browser-use")]
        ))]
        await vm.refresh()
        XCTAssertEqual(vm.answeredGroup, .mcp)
        XCTAssertNil(
            vm.rows.first?.parent,
            "the owning server is a tool-dimension fact (`CallMetricsPresentation.swift:396`)"
        )
    }

    /// An unrecognised `groupBy` is the same fallback the server applies, and the wire word stays visible in the
    /// echo so a new dimension is not silently read as `tool` (`ToolMetrics.swift:160-162`).
    func testAnUnrecognisedGroupByFallsBackToToolWithoutFailing() async throws {
        let vm = try await page(
            summary: .success(populated(groupBy: "tenant")),
            trend: .success(trend())
        )
        XCTAssertEqual(vm.answeredGroup, .tool)
        XCTAssertEqual(vm.answered?.groupBy, "tenant", "the reply's own word is kept")
        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(vm.rows.count, 2)
    }

    // MARK: - 6. the push value

    /// The push value is built from the row the reply sent, not from the card that dropped the id and the key
    /// (`:139-141`, `:190-211`): the detail predicate is assembled from those two (`CallMetricsView.swift:87-101`).
    func testThePushValueCarriesEveryFieldThePredicateNeeds() async throws {
        let (vm, catalog) = viewModel()
        vm.kind = .shell
        try await waitUntil { catalog.totalRequestCount == 2 }
        vm.window = .last90Days
        try await waitUntil { catalog.totalRequestCount == 4 }
        catalog.summaryReplies = [.success(try populated(rows: [
            try .stub(subjectKey: "7", subjectId: 7, toolName: "web_search", subjectName: "browser-use"),
            try .stub(subjectKey: "9", subjectId: 9, subjectName: ""),
        ]))]
        catalog.trendReplies = [.success(try trend())]
        await vm.refresh()

        let ref = try XCTUnwrap(vm.ref(at: 0))
        XCTAssertEqual(ref.subjectId, 7)
        XCTAssertEqual(ref.subjectKey, "7")
        XCTAssertEqual(ref.title, "browser-use")
        XCTAssertEqual(ref.toolName, "web_search")
        XCTAssertEqual(ref.kind, "mcp", "the row's own origin text, kept as text")
        XCTAssertEqual(ref.group, vm.answeredGroup)
        XCTAssertEqual(ref.group, .tool)
        XCTAssertEqual(ref.filterKind, .shell, "the arm the operator picked, not the pinned one")
        XCTAssertEqual(ref.window, .last90Days, "a drawer may not outlive the query it came from")

        XCTAssertEqual(
            try XCTUnwrap(vm.ref(at: 1)).title, "9",
            "a row the registry names nothing falls back to its key (`CallMetricsViewModel.swift:204`)"
        )
    }

    /// A row with no `subjectId` has no detail predicate to build, so the affordance is withheld rather than
    /// offered and answered empty (`ToolMetrics.swift:71-73`, `CallMetricsView.swift:561-565`). An index the
    /// screen does not have is the same answer.
    func testARowWithNothingToDrillByOffersNoPushValue() async throws {
        let (vm, catalog) = viewModel()
        XCTAssertNil(vm.ref(at: 0), "before anything answered there is no row at any position")
        catalog.summaryReplies = [.success(try populated(rows: [try .idless()]))]
        catalog.trendReplies = [.success(try trend())]
        await vm.refresh()

        XCTAssertEqual(vm.rows.count, 1)
        XCTAssertFalse(vm.rows[0].isDrillable)
        XCTAssertNil(vm.ref(at: 0), "a builtin tool row has no id to filter the detail page by")
        XCTAssertNil(vm.ref(at: -1))
        XCTAssertNil(vm.ref(at: 1))
    }

    /// Position is the contract, not `CallMetricsRow.id`: that identity is only `kind` plus `subjectKey` and the
    /// console refuses to key its table on it for the same reason (`:194-196`, `index.tsx:488`). Two rows that
    /// collide must still open two different subjects.
    func testThePushValueFollowsTheReplyOrderRatherThanTheRowIdentity() async throws {
        let colliding = [
            try CallMetricsRow.stub(subjectKey: "browser", subjectId: 7, subjectName: "browser-use"),
            try CallMetricsRow.stub(subjectKey: "browser", subjectId: 8, subjectName: "browser-use-staging"),
        ]
        XCTAssertEqual(
            colliding.map(\.id), ["mcp-browser", "mcp-browser"],
            "the identity the contract gives both rows is one string"
        )

        let (vm, catalog) = viewModel()
        catalog.summaryReplies = [.success(try populated(rows: colliding))]
        catalog.trendReplies = [.success(try trend())]
        await vm.refresh()

        XCTAssertEqual(vm.rows.map(\.id), ["mcp-browser", "mcp-browser"])
        XCTAssertEqual(vm.rows.map(\.title), ["browser-use", "browser-use-staging"], "the reply's order is on screen")
        let first = try XCTUnwrap(vm.ref(at: 0))
        let second = try XCTUnwrap(vm.ref(at: 1))
        XCTAssertEqual(first.subjectId, 7)
        XCTAssertEqual(second.subjectId, 8)
        XCTAssertNotEqual(first, second, "one position, one subject")
    }

    // MARK: - 7. the copy the replies feed

    /// The footnote under the table quotes the interval the server counted, clamps included, and stands in with
    /// the window this screen asked for only until the first reply lands (`:215-222`, `index.tsx:503-510`).
    func testTheFootnotePrintsTheIntervalTheServerAnswered() async throws {
        let (vm, catalog) = viewModel()
        let asked = vm.window.start
        XCTAssertEqual(vm.windowNote, hx("monitor.calls.window.asked", asked))

        catalog.summaryReplies = [.success(try populated(
            from: "2026-08-01 07:00:00",
            to: "2026-10-11 20:00:00"
        ))]
        catalog.trendReplies = [.success(try trend())]
        await vm.refresh()

        XCTAssertEqual(
            vm.windowNote,
            hx("monitor.calls.window.note", "2026-08-01 07:00:00", "2026-10-11 20:00:00"),
            "the server's own text, not the window this screen named"
        )
        XCTAssertNotEqual(vm.windowNote, hx("monitor.calls.window.asked", asked))
        XCTAssertEqual(vm.answered?.to, "2026-10-11 20:00:00")
    }

    /// Four cards in the console's KPI order, every one read off the summary's own window columns — never out of
    /// the rows below them, which are a different aggregate (`:319-336`, `CallMetricsPresentation.swift:300-306`).
    func testTheFourCardsAreTheSummariesOwnWindowFigures() async throws {
        let (vm, catalog) = viewModel()
        catalog.summaryReplies = [.success(try populated(rows: [try .stub(calls: 10, successes: 9, errors: 1)]))]
        catalog.trendReplies = [.success(try trend())]
        await vm.refresh()

        XCTAssertEqual(vm.cards.map(\.kind), [.calls, .rate, .p95, .failures])
        XCTAssertEqual(vm.card(.calls)?.value, "604", "the window's 604, not the one row's 10")
        XCTAssertEqual(vm.card(.rate)?.value, "91.2%", "one decimal, the console's `(rate * 100).toFixed(1)`")
        XCTAssertEqual(vm.card(.p95)?.value, ">30000 ms", "the open-ended bucket's sign is part of the number")
        XCTAssertEqual(vm.card(.failures)?.value, "53")

        catalog.summaryReplies = [.success(try CallMetricsSummary.stub(p95Operator: "<=", p95Ms: 2000))]
        await vm.refresh()
        XCTAssertEqual(vm.card(.p95)?.value, "<=2000 ms")
        XCTAssertEqual(vm.card(.calls)?.value, "0", "a window that counted nothing prints zeros, not an error")
        XCTAssertEqual(vm.card(.rate)?.value, "0.0%")
    }

    /// The buckets are the reply's points folded per `timePoint`, in the reply's order, with a point that names no
    /// time dropped rather than plotted at position zero (`CallMetricsPresentation.swift:243-277`).
    func testTheBucketsAreFoldedOutOfTheReplyWithoutReorderingIt() async throws {
        let (vm, catalog) = viewModel()
        catalog.summaryReplies = [.success(try populated())]
        catalog.trendReplies = [.success(try trend(points: [
            try .stub(timePoint: "2026-10-11 19:00:00", calls: 3, successes: 2, errors: 1, denials: 1),
            try .stub(timePoint: "2026-10-11 18:00:00", calls: 10, successes: 9, errors: 1),
            try .stub(
                timePoint: "2026-10-11 18:00:00", kind: "builtin", dimensionId: nil,
                dimensionName: "read_file", calls: 4, successes: 4, interruptions: 2
            ),
            try .stub(timePoint: "", calls: 77),
        ]))]
        await vm.refresh()

        XCTAssertEqual(vm.buckets, [
            CallMetricsBucket(
                timePoint: "2026-10-11 19:00:00", label: "10-11 19:00", calls: 3, successes: 2, failures: 2
            ),
            CallMetricsBucket(
                timePoint: "2026-10-11 18:00:00", label: "10-11 18:00", calls: 14, successes: 13, failures: 3
            ),
        ], "first time a bucket is met fixes its place; a point with no time is off the axis")
        XCTAssertEqual(vm.answeredGranularity, "hour", "the bucket the server folded to, read back off the reply")
    }

    // MARK: - 8. the trend's reading form

    /// The reading form is not a filter: both arms come out of the buckets already in hand, so switching them must
    /// cost nothing on the wire (`:44-68`, `:136-138`). An out-of-range segment index is a control promising a form
    /// the enum does not have, so it clamps.
    func testTheTrendFormClampsAndCostsNoRequest() async throws {
        XCTAssertEqual(CallMetricsViewModel.TrendForm.allCases.map(\.rawValue), ["chart", "list"])
        XCTAssertEqual(CallMetricsViewModel.TrendForm.at(-1), .chart)
        XCTAssertEqual(CallMetricsViewModel.TrendForm.at(0), .chart)
        XCTAssertEqual(CallMetricsViewModel.TrendForm.at(1), .list)
        XCTAssertEqual(CallMetricsViewModel.TrendForm.at(2), .list)
        XCTAssertEqual(CallMetricsViewModel.TrendForm.chart.titleKey, "monitor.trend.form.chart")
        XCTAssertEqual(CallMetricsViewModel.TrendForm.list.titleKey, "monitor.trend.form.list")

        let (vm, catalog) = viewModel()
        catalog.summaryReplies = [.success(try populated())]
        catalog.trendReplies = [.success(try trend())]
        await vm.refresh()
        XCTAssertEqual(vm.trendForm, .chart)

        let before = catalog.totalRequestCount
        vm.trendForm = .list
        XCTAssertEqual(vm.trendForm, .list)
        XCTAssertEqual(catalog.totalRequestCount, before, "the same buckets, read as numbers instead of as a line")
        XCTAssertEqual(vm.buckets.count, 2)

        vm.trendForm = .chart
        XCTAssertEqual(catalog.totalRequestCount, before)
        XCTAssertEqual(catalog.summaryRequests.count, 1)
        XCTAssertEqual(catalog.trendRequests.count, 1)
    }

    // MARK: - helpers

    /// Long enough for a `didSet`-launched read to reach the catalog, and short enough that a guard which does
    /// exist cannot be mistaken for one that is merely slow (`SkillUsageViewModelTests.swift:370-378`).
    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }
}

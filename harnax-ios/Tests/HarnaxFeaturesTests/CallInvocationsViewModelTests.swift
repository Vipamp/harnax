import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// The drill-down: one paged read, and the rules that make paging it safe. The predicate is the pushed row's and
/// never a fresh one, an outcome switch discards the pages it was holding instead of continuing them, a tail page
/// that answers after the filter moved is dropped, a tail asked for during a refresh is remembered rather than
/// lost, and only ever one read is on the wire.
///
/// What the route binds is `CallMetricsWireTests`' business; the two replies the list screen folds are
/// `CallMetricsViewModelTests`.
@MainActor
final class CallInvocationsViewModelTests: XCTestCase {
    /// The pushed value, in the shape the table's first card hands over: an MCP server's call of one of its tools.
    private func ref(
        subjectId: Int64? = 7,
        subjectKey: String = "7",
        title: String = "browser-use",
        toolName: String = "web_search",
        kind: String = "mcp",
        group: CallMetricsGroup = .tool,
        filterKind: CallMetricsKind? = nil,
        window: CallMetricsWindow = .last30Days
    ) -> CallMetricsSubjectRef {
        CallMetricsSubjectRef(
            subjectId: subjectId,
            subjectKey: subjectKey,
            title: title,
            toolName: toolName,
            kind: kind,
            group: group,
            filterKind: filterKind,
            window: window
        )
    }

    private func opened(
        as pushed: CallMetricsSubjectRef,
        pageSize: Int = 20
    ) -> (vm: CallInvocationsViewModel, catalog: FakeToolMetrics) {
        let catalog = FakeToolMetrics()
        return (CallInvocationsViewModel(subject: pushed, catalog: catalog, pageSize: pageSize), catalog)
    }

    /// `count` rows numbered from `first`, inside an envelope saying `total`. Ids stay unique across pages so an
    /// append's de-dup (`PagedState.append`) cannot be mistaken for the page never having arrived.
    private func page(
        first: Int64,
        count: Int = 2,
        num: Int = 1,
        total: Int
    ) throws -> Page<CallInvocationRow> {
        var rows: [CallInvocationRow] = []
        for offset in 0..<count { rows.append(try CallInvocationRow.stub(id: first + Int64(offset))) }
        return try Page.stub(rows, pageNum: num, total: total)
    }

    /// The screen, already holding page 1 of a longer list, so the tests below start where a reader who has
    /// something to scroll actually starts.
    private func loaded(
        as pushed: CallMetricsSubjectRef? = nil,
        first: Int64 = 1,
        total: Int = 9
    ) async throws -> (vm: CallInvocationsViewModel, catalog: FakeToolMetrics) {
        let (vm, catalog) = opened(as: pushed ?? ref())
        catalog.invocationReplies = [.success(try page(first: first, total: total))]
        await vm.refresh()
        return (vm, catalog)
    }

    // MARK: - 1. the predicate is the pushed row's

    /// The drawer "may not outlive the query it came from" (`index.tsx:105-112`), and here that is structural: the
    /// window, the origin and the columns all come out of the `CallMetricsSubjectRef`, page 1, unfiltered
    /// (`CallInvocationsViewModel.swift:104-106`).
    func testOpeningThePageSendsThePushedPredicateAndNothingElse() async throws {
        let (vm, catalog) = opened(as: ref(window: .last90Days))
        XCTAssertEqual(vm.phase, .loading)
        XCTAssertTrue(vm.items.isEmpty)
        XCTAssertEqual(vm.total, 0)
        XCTAssertFalse(vm.canLoadMore)
        XCTAssertFalse(vm.isAppending)
        XCTAssertNil(vm.inlineError)
        XCTAssertNil(vm.outcome, "「all」is the absence of the parameter, not a fifth code (`:42-44`)")
        XCTAssertEqual(vm.subject, ref(window: .last90Days), "the pushed value is the screen's whole identity")
        XCTAssertEqual(catalog.totalRequestCount, 0, "constructing the screen asks for nothing")

        let expected = CallInvocationQuery(
            window: .last90Days, kind: .mcp, toolName: "web_search", mcpId: 7
        )
        XCTAssertEqual(vm.currentQuery, expected, "page 1 of the twenty, as the row was counted")
        catalog.invocationReplies = [.success(try page(first: 1, total: 9))]
        await vm.refresh()

        XCTAssertEqual(catalog.invocationRequests, [expected])
        XCTAssertEqual(vm.currentQuery, expected, "a read answers; the query does not move")
        XCTAssertEqual(vm.phase, .content)
    }

    /// `size` is the console's own page size (`index.tsx:135`), and the caller that set it must see it on both
    /// paths — a tail page of a different size would silently skip rows.
    func testThePageSizeTheScreenWasOpenedWithIsThePageSizeOnTheWire() async throws {
        let (vm, catalog) = opened(as: ref(), pageSize: 5)
        XCTAssertEqual(vm.currentQuery.size, 5)
        catalog.invocationReplies = [.success(try page(first: 1, count: 2, total: 9))]
        await vm.refresh()
        XCTAssertEqual(catalog.invocationRequests.first?.size, 5)

        await vm.loadMore()
        XCTAssertEqual(catalog.invocationRequests.last?.size, 5, "the tail is the same page size or nothing")
        XCTAssertEqual(catalog.invocationRequests.last?.num, 2)
    }

    /// A detail-dimension row carries no origin at all — the service leaves the column unset
    /// (`ToolMetricsServiceImpl.kt:204-212`) — so the predicate borrows the list screen's filter: an empty string
    /// would drop the filter altogether and list every origin bucket under this row (`index.tsx:126-128`).
    func testARowThatCarriesNoOriginOfItsOwnBorrowsTheListsFilter() async throws {
        XCTAssertEqual(
            ref(kind: "", filterKind: .shell).invocationQuery().kind, .shell,
            "the arm the operator was standing on, not nothing"
        )
        XCTAssertEqual(
            ref(kind: "webhook", filterKind: .cli).invocationQuery().kind, .cli,
            "an origin this app cannot name still cannot widen the set"
        )
        XCTAssertNil(ref(kind: "").invocationQuery().kind, "no row origin and no filter is genuinely no predicate")
        XCTAssertNil(
            ref(kind: "").invocationQuery().mcpId,
            "and the id columns follow the row's own text, never the borrowed arm (`CallMetricsView.swift:93-94`)"
        )
    }

    /// The tool dimension is the one where a row is a *tool*, so the name is a real predicate; the other four
    /// narrow by the column their own subject lives in.
    func testEachDimensionNarrowsByTheColumnItsSubjectHas() async throws {
        let tool = ref(group: .tool).invocationQuery()
        XCTAssertEqual(tool.toolName, "web_search")
        XCTAssertEqual(tool.mcpId, 7, "the server's calls *of this tool*: the id alone would list them all")
        XCTAssertNil(tool.agentId)
        XCTAssertNil(tool.sessionId)

        let mcp = ref(group: .mcp).invocationQuery()
        XCTAssertNil(mcp.toolName, "by the mcp dimension the same text is not a tool name at all")
        XCTAssertEqual(mcp.mcpId, 7)

        let cli = ref(subjectId: 9, subjectKey: "9", kind: "cli", group: .cli).invocationQuery()
        XCTAssertEqual(cli.cliId, 9, "the row's origin decides which id column, not the dimension")
        XCTAssertNil(cli.mcpId)

        let agent = ref(subjectId: 3, subjectKey: "3", kind: "", group: .agent).invocationQuery()
        XCTAssertEqual(agent.agentId, 3)
        XCTAssertNil(agent.sessionId)

        // The session dimension has no id to carry, so its key is all the predicate gets
        // (`CallMetricsView.swift:22-23,96`).
        let session = ref(subjectId: nil, subjectKey: "sess-42", kind: "", group: .session).invocationQuery()
        XCTAssertEqual(session.sessionId, "sess-42")
        XCTAssertNil(session.agentId)
        XCTAssertNil(session.kind)
    }

    // MARK: - 2. the outcome arm is a different result set

    /// Page 3 of every outcome is not a continuation of the ERROR list, so the switch throws the accumulated rows
    /// away *before* the new read goes out, and the page counter starts again (`:42-50`, `resetPages` at `:185-192`).
    func testChangingTheOutcomeDiscardsThePagesItWasHolding() async throws {
        let (vm, catalog) = try await loaded()
        catalog.invocationReplies = [.success(try page(first: 3, num: 2, total: 9))]
        await vm.loadMore()
        XCTAssertEqual(vm.items.map(\.id), [1, 2, 3, 4], "two pages deep")
        XCTAssertEqual(vm.total, 9)
        XCTAssertTrue(vm.canLoadMore)

        catalog.gate = true
        catalog.invocationReplies = [
            .success(try page(first: 11, total: 5)),
            .success(try page(first: 13, num: 2, total: 5)),
        ]
        vm.outcome = .error
        try await waitUntil { catalog.invocationRequests.count == 3 }
        XCTAssertTrue(vm.items.isEmpty, "the old set is off the screen while the new one is still on the wire")
        XCTAssertEqual(vm.total, 0)
        XCTAssertNil(vm.inlineError)
        XCTAssertEqual(vm.phase, .loading)
        // `canLoadMore` is the one published value `resetPages` leaves alone (`:185-192`): nothing rewrites it
        // until `apply()` runs, so while the new page 1 is still out it describes the set that just went away.
        XCTAssertTrue(vm.canLoadMore)
        XCTAssertEqual(vm.currentQuery, ref().invocationQuery(num: 1, size: 20, outcome: .error))

        catalog.release()
        try await waitUntil { vm.items.count == 2 }
        XCTAssertEqual(vm.items.map(\.id), [11, 12])
        XCTAssertEqual(vm.phase, .content)

        // The counter, proven by the page it asks for next: without the reset this would be page 3 of a brand-new
        // two-row list.
        let tail = Task { await vm.loadMore() }
        try await waitUntil { catalog.invocationRequests.count == 4 }
        XCTAssertEqual(
            catalog.invocationRequests[3],
            ref().invocationQuery(num: 2, size: 20, outcome: .error)
        )
        catalog.release()
        await tail.value
        XCTAssertEqual(vm.items.map(\.id), [11, 12, 13, 14])
        XCTAssertEqual(
            catalog.invocationRequests.map(\.num), [1, 2, 1, 2],
            "the switch restarted the paging rather than continuing it"
        )
        XCTAssertEqual(catalog.invocationRequests.map(\.outcome), [nil, nil, .error, .error])
    }

    /// The arm's `guard outcome != oldValue` is the only thing standing between a segmented control and one read
    /// per redraw.
    func testWritingTheOutcomeTheScreenAlreadyHasSendsNoRead() async throws {
        let (vm, catalog) = try await loaded()
        XCTAssertEqual(catalog.invocationRequests.count, 1)

        vm.outcome = .error
        try await waitUntil { catalog.invocationRequests.count == 2 }
        vm.outcome = .error
        vm.outcome = .denied
        try await waitUntil { catalog.invocationRequests.count == 3 }

        XCTAssertEqual(catalog.invocationRequests.map(\.outcome), [nil, .error, .denied])
        XCTAssertEqual(catalog.totalRequestCount, 3, "the repeat write cost nothing at all")
        XCTAssertEqual(vm.outcome, .denied)
    }

    // MARK: - 3. one read at a time

    /// The append captured the generation it was asked under (`runAppend` at `:158-167`), so a tail page that
    /// answers after the filter moved may not append itself to the new list — this is the whole reason the
    /// drawer's own race is worth a test.
    func testATailPageThatAnswersAfterTheFilterMovedIsDiscarded() async throws {
        let (vm, catalog) = try await loaded()
        catalog.gate = true
        let tail = Task { await vm.loadMore() }
        try await waitUntil { catalog.invocationRequests.count == 2 }
        vm.outcome = .error
        try await waitUntil { catalog.invocationRequests.count == 3 }
        catalog.invocationReplies = [
            .success(try page(first: 21, num: 2, total: 9)),
            .success(try page(first: 31, total: 2)),
        ]

        catalog.release()
        await tail.value
        try await waitUntil { vm.items.count == 2 }
        XCTAssertEqual(
            vm.items.map(\.id), [31, 32],
            "the ERROR list is the refreshed one, not every outcome's page 2 bolted underneath it"
        )
        XCTAssertEqual(vm.total, 2)
        XCTAssertFalse(vm.canLoadMore)
        XCTAssertEqual(catalog.invocationRequests.map(\.num), [1, 2, 1])
        XCTAssertEqual(vm.phase, .content)
    }

    /// An append asked for while a refresh is on the wire is remembered, not dropped (`appendRequested`,
    /// `reissueAppend` at `:177-181`): the reader who scrolled to the bottom still gets the next page, but only
    /// once the refreshed page 1 has said how long the list is.
    func testATailAskedForDuringARefreshIsReissuedAfterIt() async throws {
        let (vm, catalog) = try await loaded()
        catalog.gate = true
        let refreshed = Task { await vm.refresh() }
        try await waitUntil { catalog.invocationRequests.count == 2 }
        let tail = Task { await vm.loadMore() }
        catalog.invocationReplies = [
            .success(try page(first: 41, total: 9)),
            .success(try page(first: 43, num: 2, total: 9)),
        ]

        catalog.release()
        try await waitUntil { catalog.invocationRequests.count == 3 }
        XCTAssertEqual(
            catalog.invocationRequests.map(\.num), [1, 1, 2],
            "one reissued page and no more — the guard on `isRefreshing` is what keeps the second scroll silent"
        )
        XCTAssertEqual(vm.items.map(\.id), [41, 42], "the refreshed page 1 lands first")

        catalog.release()
        await tail.value
        await refreshed.value
        XCTAssertEqual(vm.items.map(\.id), [41, 42, 43, 44])
        XCTAssertEqual(vm.total, 9)
        XCTAssertTrue(vm.canLoadMore)
        XCTAssertFalse(vm.isAppending)
    }

    /// …and the reissue is conditional on the refreshed list still having a tail: a page 1 that ends the list has
    /// nothing left to append, so the remembered scroll is dropped rather than asked for.
    func testATailAskedForDuringARefreshIsDroppedWhenTheNewPageEndsTheList() async throws {
        let (vm, catalog) = try await loaded(total: 9)
        catalog.gate = true
        let refreshed = Task { await vm.refresh() }
        try await waitUntil { catalog.invocationRequests.count == 2 }
        let tail = Task { await vm.loadMore() }
        catalog.invocationReplies = [.success(try page(first: 51, total: 2))]

        catalog.release()
        await refreshed.value
        await tail.value
        XCTAssertEqual(catalog.invocationRequests.count, 2, "no tail read for a list that has no tail")
        XCTAssertEqual(vm.items.map(\.id), [51, 52])
        XCTAssertFalse(vm.canLoadMore)
        // What the guard does not do is clear `appendRequested` on the way out (`:178`), so the flag outlives the
        // drop; this pins only how far the wire goes.
    }

    /// A refresh asked for while one is running queues a rerun instead of starting a second read
    /// (`refresh` at `:108-119`), because the last answer to arrive must not be the one that wins the rows, the
    /// page counter and the total.
    func testASecondRefreshAskedMidFlightRunsAfterTheFirstInsteadOfBesideIt() async throws {
        let (vm, catalog) = opened(as: ref())
        catalog.gate = true
        catalog.invocationReplies = [
            .success(try page(first: 1, total: 9)),
            .success(try page(first: 21, total: 2)),
        ]
        let first = Task { await vm.refresh() }
        try await waitUntil { catalog.invocationRequests.count == 1 }

        let second = Task { await vm.refresh() }
        await second.value
        XCTAssertEqual(
            catalog.invocationRequests.count, 1,
            "the second read waits rather than running beside the first"
        )

        catalog.release()
        try await waitUntil { catalog.invocationRequests.count == 2 }
        XCTAssertEqual(
            catalog.invocationRequests.map(\.num), [1, 1],
            "the queued refresh comes out again as page 1, after the first has been answered"
        )
        XCTAssertTrue(vm.items.isEmpty, "the first answer is already superseded, so it never reaches the screen")
        XCTAssertEqual(vm.total, 0)

        catalog.release()
        await first.value
        XCTAssertEqual(vm.items.map(\.id), [21, 22], "the last word is the last refresh's")
        XCTAssertEqual(vm.total, 2)
        XCTAssertFalse(vm.canLoadMore)
        XCTAssertEqual(catalog.invocationRequests.count, 2, "two refreshes, two reads")
    }

    // MARK: - 4. who gets the outage sentence

    /// Rows on screen are the screen: a refresh that failed beside them is a banner, and the page still reads as
    /// content (`:134-143`, `SkillDraftListViewModel.inlineError`).
    func testARefreshThatFailedBesideRowsIsABannerOverTheList() async throws {
        let (vm, catalog) = try await loaded()
        catalog.invocationReplies = [.failure(.offline)]
        await vm.refresh()

        XCTAssertEqual(vm.inlineError, ErrorMessage.text(for: .offline))
        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(vm.items.map(\.id), [1, 2], "the reader still has the page they were on")
        XCTAssertEqual(vm.total, 9, "and the paging did not shift under it")
        XCTAssertTrue(vm.canLoadMore)
    }

    /// With nothing on screen the same failure is the screen's own, in its own words.
    func testAFailedFirstReadIsTheScreensFailure() async throws {
        let (vm, catalog) = opened(as: ref())
        catalog.invocationReplies = [.failure(.offline)]
        await vm.refresh()

        XCTAssertEqual(vm.phase, .failed(ErrorMessage.text(for: .offline)))
        XCTAssertTrue(vm.items.isEmpty)
        XCTAssertEqual(vm.total, 0)
        XCTAssertNil(vm.inlineError, "a banner over a list that never appeared is a banner over nothing")
        XCTAssertFalse(vm.canLoadMore)
    }

    /// `cancelled` is the reader going away — a pull torn down, a screen dismissed — so it is silence about the
    /// call rather than a verdict on it, and it changes nothing on either path (`ErrorMessage.carriesNews`).
    func testAReadTheReaderToreDownSaysNothing() async throws {
        let (vm, catalog) = opened(as: ref())
        catalog.invocationReplies = [.failure(.cancelled)]
        await vm.refresh()
        XCTAssertEqual(vm.phase, .loading, "an outage the reader caused is not a page that failed")
        XCTAssertNil(vm.inlineError)

        catalog.invocationReplies = [.success(try page(first: 1, total: 9))]
        await vm.refresh()
        XCTAssertEqual(vm.phase, .content)

        catalog.invocationReplies = [.failure(.cancelled)]
        await vm.refresh()
        XCTAssertNil(vm.inlineError, "and it does not raise the banner beside rows either")
        XCTAssertEqual(vm.items.map(\.id), [1, 2])
        XCTAssertEqual(vm.phase, .content)
    }

    /// A tail page that failed leaves the rows up and says what did not arrive; the list stays pageable, because
    /// nothing was served to move the counter.
    func testATailPageThatFailedSaysSoWithoutTouchingTheRows() async throws {
        let (vm, catalog) = try await loaded()
        catalog.invocationReplies = [.failure(.timeout)]
        await vm.loadMore()

        XCTAssertEqual(vm.inlineError, ErrorMessage.text(for: .timeout))
        XCTAssertEqual(vm.items.map(\.id), [1, 2])
        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(vm.total, 9)
        XCTAssertTrue(vm.canLoadMore, "the reader may ask again")
        XCTAssertFalse(vm.isAppending)
        XCTAssertEqual(catalog.invocationRequests.map(\.num), [1, 2])
    }

    // MARK: - 5. what a reply becomes

    /// The route answers `ORDER BY l.ts DESC, l.id DESC` (`ToolInvocationLogMapper.xml:182-184`) and `apply()`
    /// keeps it: nothing is re-sorted, nothing is filtered out, and the two fallbacks the contract promises —
    /// `ts` behind to `startTime`, the session's name behind to its id — resolve per row (`CallMetricsStamp`,
    /// `ToolMetrics.swift:134-139`).
    func testNothingIsReorderedOrDroppedBetweenTheReplyAndTheList() async throws {
        let rows = [
            try CallInvocationRow.stub(id: 9, sessionId: "sess-9", sessionName: "周报整理"),
            try CallInvocationRow.stub(
                id: 4, sessionId: "sess-3", sessionName: nil, startTime: "2026-10-11 18:40:05", ts: nil
            ),
            try CallInvocationRow.stub(
                id: 7, sessionId: "sess-7", sessionName: nil, outcome: "ERROR", errorMessage: "upstream timeout",
                argsJson: #"{"query":"harnax"}"#, startTime: nil, ts: nil
            ),
        ]
        let (vm, catalog) = opened(as: ref())
        catalog.invocationReplies = [.success(try Page.stub(rows, total: 3))]
        await vm.refresh()

        XCTAssertEqual(vm.items.map(\.id), [9, 4, 7], "newest first is the service's order, not one invented here")
        XCTAssertEqual(vm.items.map(\.stamp), [
            "2026-10-11 19:02:11",
            "2026-10-11 18:40:05",
            hx("monitor.calls.stamp.none"),
        ], "the instant, then the start time beside it, then a sentence for nothing at all")
        XCTAssertEqual(vm.items.map(\.session), ["周报整理", "sess-3", "sess-7"])
        XCTAssertEqual(vm.items.map(\.carriesPayload), [false, false, true], "an absent payload is capture being off")
        XCTAssertEqual(vm.items.map(\.outcome), [
            hx(CallInvocationOutcome.success.titleKey),
            hx(CallInvocationOutcome.success.titleKey),
            hx(CallInvocationOutcome.error.titleKey),
        ])
        XCTAssertEqual(vm.phase, .content)
        XCTAssertFalse(vm.canLoadMore, "three served of three")
    }

    /// `total` is the envelope's number and `canLoadMore` is `PagedState`'s `served < total`, never the count on
    /// screen — a page whose rows fell short of the total still promises the next one.
    func testTheCountAndThePromiseOfMoreComeOutOfTheEnvelope() async throws {
        let (vm, catalog) = opened(as: ref())
        catalog.invocationReplies = [.success(try page(first: 1, count: 2, total: 20))]
        await vm.refresh()
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertEqual(vm.total, 20, "the window's calls, not the rows in hand")
        XCTAssertTrue(vm.canLoadMore)
        XCTAssertEqual(vm.totalNote, hxCount("monitor.calls.detail.total", 20))

        catalog.invocationReplies = [.success(try page(first: 3, count: 2, num: 2, total: 20))]
        await vm.loadMore()
        XCTAssertEqual(vm.items.count, 4)
        XCTAssertEqual(vm.total, 20)
        XCTAssertTrue(vm.canLoadMore)
        XCTAssertEqual(vm.totalNote, hxCount("monitor.calls.detail.total", 20), "the count never follows the rows")

        catalog.invocationReplies = [.success(try page(first: 5, count: 16, num: 3, total: 20))]
        await vm.loadMore()
        XCTAssertEqual(vm.items.count, 20)
        XCTAssertFalse(vm.canLoadMore, "the last page closes the promise, and the footer goes with it")
        XCTAssertEqual(vm.totalNote, hxCount("monitor.calls.detail.total", 20))
    }

    /// This screen's empty is its own sentence, and its own key: no recorded call answers here while the same
    /// subject may still have counts in the aggregate above (`CallInvocationsViewModel.swift:86-88`).
    func testAnEmptyPageIsThisReadsOwnEmpty() async throws {
        let (vm, catalog) = opened(as: ref())
        catalog.invocationReplies = [.success(try Page.stub([], total: 0))]
        await vm.refresh()

        XCTAssertEqual(vm.phase, .empty)
        XCTAssertTrue(vm.items.isEmpty)
        XCTAssertEqual(vm.total, 0)
        XCTAssertFalse(vm.canLoadMore)
        XCTAssertEqual(vm.emptyKey, "monitor.calls.detail.empty")
        XCTAssertNotEqual(vm.emptyKey, CallMetricsViewModel(catalog: catalog).emptyKey, "not the table's sentence")
    }

    /// Two guards on the append: a list that has no tail asks for nothing, and a second scroll while the tail is
    /// on the wire is not a second read (`loadMore` at `:147-154`).
    func testAskingForMoreWhereTheListHasNoMoreAsksForNothing() async throws {
        let (vm, catalog) = try await loaded(total: 2)
        XCTAssertFalse(vm.canLoadMore)
        await vm.loadMore()
        XCTAssertEqual(catalog.invocationRequests.count, 1, "the footer cannot page past the last row")
        XCTAssertFalse(vm.isAppending)
    }

    func testTwoScrollsWhileTheTailIsOnTheWireCostOneRead() async throws {
        let (vm, catalog) = try await loaded()
        catalog.gate = true
        let firstTail = Task { await vm.loadMore() }
        try await waitUntil { catalog.invocationRequests.count == 2 }
        XCTAssertTrue(vm.isAppending, "the read is on the wire, so the second scroll has to wait")

        let secondTail = Task { await vm.loadMore() }
        await secondTail.value
        XCTAssertEqual(catalog.invocationRequests.count, 2, "one tail on the wire, not two")

        catalog.invocationReplies = [.success(try page(first: 3, num: 2, total: 9))]
        catalog.release()
        await firstTail.value
        XCTAssertEqual(vm.items.map(\.id), [1, 2, 3, 4])
        XCTAssertFalse(vm.isAppending)
    }

    // MARK: - 6. the copy the pushed value and the reply feed

    /// The header is the pushed row's own words — which subject, over which window — and the retention sentence
    /// belongs wherever the card's count came from the aggregate rather than the detail table
    /// (`:73-84`, `ToolMetrics.swift:158`).
    func testTheHeaderNamesTheSubjectAndTheWindowItWasPushedWith() throws {
        for group in CallMetricsGroup.allCases {
            let pushed = ref(subjectKey: "7", group: group, window: .last7Days)
            let vm = CallInvocationsViewModel(subject: pushed, catalog: FakeToolMetrics())
            let expected = hx(
                "monitor.calls.detail.subject",
                CallMetricsLabels.group(group),
                CallMetricsWindows.title(.last7Days)
            )
            XCTAssertEqual(vm.subjectNote, expected, "\(group)")
            XCTAssertEqual(
                vm.showsRetentionNote, group.readsAggregate,
                "\(group) reads the \(group.readsAggregate ? "aggregate" : "detail table")"
            )
        }
        XCTAssertEqual(CallMetricsGroup.allCases.map(\.readsAggregate), [true, true, true, false, false])
    }

    /// The origin line states the predicate back to the operator (`index.tsx:126-128`) — and is absent exactly
    /// when the predicate carries no origin, so a sentence never promises a filter that is not there.
    func testTheOriginSentenceFollowsTheKindThePredicateResolvedTo() async throws {
        let narrowed = opened(as: ref(kind: "mcp")).vm
        XCTAssertEqual(
            narrowed.originNote,
            hx("monitor.calls.detail.origin", try XCTUnwrap(CallMetricsLabels.kind("mcp")))
        )
        let borrowed = opened(as: ref(kind: "", filterKind: .shell)).vm
        XCTAssertEqual(
            borrowed.originNote,
            hx("monitor.calls.detail.origin", try XCTUnwrap(CallMetricsLabels.kind(CallMetricsKind.shell.rawValue))),
            "the borrowed arm is still what the list is filtered by, so it is still what the line says"
        )
        XCTAssertNil(opened(as: ref(kind: "")).vm.originNote, "no origin, no sentence")
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

import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// One task's execution log, in the order its reads, its filters and its one write interleave.
///
/// Two behaviours here are the backend's rather than the screen's, so each gets its own test: there is no
/// single-log endpoint, so the detail pane can only follow the polled copy of its row (`§5.4`), and an accepted
/// stop reports nothing about what it did, so the row's status is settled by the next read and not by the reply
/// (`§5.5`).
///
/// Like the task list, every sentence this screen can show is the scheduler's own English, forwarded byte for
/// byte (`§0`), so the assertions compare against the literal the server sends.
@MainActor
final class TaskLogListViewModelTests: XCTestCase {
    private let task = Int64(41)

    private func logRow(
        _ id: Int64,
        status: Int = 1,
        creator: String = "admin",
        durationMs: Int64 = 1_500,
        response: String = "汇总完成"
    ) -> [String: Any] {
        [
            "id": id,
            "taskId": task,
            "taskName": "每日晨报",
            "prompt": "汇总昨天的构建失败并给出下一步建议",
            "response": response,
            "sessionId": "task-41-7-3f2b",
            "status": status,
            "errorInfo": [2: "Auto-expired: no completion within 300s (likely service restart)", 4: "Stopping..."][status] ?? "",
            "tokenUsage": "TokenUsage(inputTokens=100, outputTokens=28, totalTokens=128, costTime=1.4, timestamp=1758762005000)",
            "startTime": "2026-09-25 09:00:03",
            "endTime": "2026-09-25 09:00:05",
            "durationMs": durationMs,
            "creator": creator,
            "createTime": "2026-09-25 09:00:03",
        ]
    }

    private func seededLogs(
        _ count: Int,
        total: Int? = nil,
        pageNum: Int = 1,
        status: Int = 1,
        firstID: Int64 = 901
    ) throws -> Page<AgentTaskLog> {
        try PageStub.page(
            AgentTaskLog.self,
            (0..<count).map { index in logRow(firstID + Int64(index), status: status) },
            pageNum: pageNum,
            total: total ?? count,
            pageSize: 10
        )
    }

    private func emptyLogs() throws -> Page<AgentTaskLog> {
        try PageStub.page(AgentTaskLog.self, [], total: 0, pageSize: 10)
    }

    /// The loop is left on its real 5 s idle cadence unless a test asks for a tick: an armed-but-unfired timer is
    /// enough to prove `isPolling`, and a test about a filter must not have a re-read land in the middle of it.
    private func makeVM(
        _ catalog: FakeAgentTasks,
        account: AccountSnapshot? = AccountSnapshot(username: "admin"),
        idleInterval: Duration = .seconds(5),
        stopReloadDelay: Duration = .milliseconds(20)
    ) -> TaskLogListViewModel {
        TaskLogListViewModel(
            taskID: task,
            catalog: catalog,
            account: account,
            idleInterval: idleInterval,
            stopReloadDelay: stopReloadDelay
        )
    }

    private func fresh(
        account: AccountSnapshot? = AccountSnapshot(username: "admin")
    ) async throws -> (TaskLogListViewModel, FakeAgentTasks) {
        let catalog = FakeAgentTasks()
        catalog.logReplies = [.success(try seededLogs(2, total: 27))]
        let vm = makeVM(catalog, account: account)
        await vm.refresh()
        return (vm, catalog)
    }

    /// The same page with a live run on it: only a `3` offers a stop, so the stop tests cannot work off the
    /// settled rows `fresh` seeds.
    private func freshLive(
        account: AccountSnapshot? = AccountSnapshot(username: "admin")
    ) async throws -> (TaskLogListViewModel, FakeAgentTasks) {
        let catalog = FakeAgentTasks()
        catalog.logReplies = [.success(try seededLogs(2, total: 27, status: 3))]
        let vm = makeVM(catalog, account: account)
        await vm.refresh()
        return (vm, catalog)
    }

    // MARK: - the page

    func testTheFirstPageIsReadUnderTheTaskItBelongsTo() async throws {
        let (vm, catalog) = try await fresh()
        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertEqual(catalog.logRequests.count, 1)
        XCTAssertEqual(catalog.logRequests.first?.taskID, task, "the path id is the task, never the log")
        XCTAssertEqual(catalog.logRequests.first?.num, 1)
        XCTAssertEqual(catalog.logRequests.first?.size, 10)
        XCTAssertTrue(catalog.logRequests.first?.filter.isEmpty ?? false)
        XCTAssertFalse(vm.isFiltered)
        XCTAssertNil(vm.inlineError)
    }

    func testTheCounterComesFromTheStackNotFromTheRowsOnScreen() async throws {
        let (vm, _) = try await fresh()
        XCTAssertEqual(vm.total, 27)
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertTrue(vm.canLoadMore)
    }

    func testATaskThatHasNeverFiredIsAnEmptyCardNotAnError() async throws {
        let catalog = FakeAgentTasks()
        catalog.logReplies = [.success(try emptyLogs())]
        let vm = makeVM(catalog)
        await vm.refresh()
        XCTAssertEqual(vm.phase, .empty)
        XCTAssertFalse(vm.isFiltered, "a task with no rows at all may be described as such")
    }

    func testNothingUnderAFilterIsNoMatchesNotAnEmptyTask() async throws {
        let catalog = FakeAgentTasks()
        let vm = makeVM(catalog)
        vm.filter = AgentTaskLogFilter(state: .failed)
        catalog.logReplies = [.success(try emptyLogs())]
        await vm.refresh()
        XCTAssertEqual(vm.phase, .empty)
        XCTAssertTrue(vm.isFiltered)
    }

    func testAFailedFirstLoadBecomesAnErrorStateNotAnEmptyCard() async throws {
        let catalog = FakeAgentTasks()
        catalog.logReplies = [.failure(.offline)]
        let vm = makeVM(catalog)
        await vm.refresh()
        XCTAssertEqual(vm.phase, .failed(hx("error.offline")))
        XCTAssertNil(vm.inlineError)
    }

    func testAFailedRefreshKeepsTheRowsAndRaisesABanner() async throws {
        let (vm, catalog) = try await fresh()
        catalog.logReplies = [.failure(.timeout)]
        await vm.refresh()
        XCTAssertEqual(vm.phase, .content, "a refresh that failed must not blank the log")
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertEqual(vm.inlineError, hx("error.timeout"))
    }

    func testTheSecondPageIsAppendedAndAFailedOneRetriesTheSameNumber() async throws {
        let catalog = FakeAgentTasks()
        catalog.logReplies = [.success(try seededLogs(2, total: 27))]
        let vm = makeVM(catalog)
        await vm.refresh()
        XCTAssertTrue(vm.canLoadMore)

        catalog.logReplies = [.failure(.offline)]
        await vm.loadMore()
        XCTAssertEqual(vm.items.count, 2, "the rows on screen stay")
        XCTAssertEqual(vm.inlineError, hx("error.offline"))

        catalog.logReplies = [.success(try seededLogs(1, total: 27, pageNum: 2, firstID: 903))]
        await vm.loadMore()
        XCTAssertEqual(catalog.logRequests.map(\.num), [1, 2, 2], "the page that failed is asked for again")
        XCTAssertEqual(vm.items.count, 3)
        XCTAssertNil(vm.inlineError)
    }

    // MARK: - the four filters the route answers to

    /// The status flag on this route is plain `status`, where the task page spells the same idea `taskStatus`
    /// (`AgentTaskController.kt:175` against `:58`), so the VM's own filter value is what a call site can get
    /// wrong and what this asserts.
    func testTheStatusChoiceTakesEffectOnTheSpotAndIsOneRequest() async throws {
        let (vm, catalog) = try await fresh()
        catalog.logReplies = [.success(try seededLogs(1, total: 1, status: 3))]
        vm.filter = AgentTaskLogFilter(state: .running)
        try await waitUntil { catalog.logRequests.count == 2 }
        XCTAssertEqual(catalog.logRequests.last?.filter.state, .running)
        XCTAssertTrue(vm.isFiltered)
        XCTAssertEqual(catalog.logRequests.count, 2, "a chosen value is not debounced like a typed one")
    }

    func testTheKeywordIsDebouncedAndJoinedToTheFilterOnlyAtTheRead() async throws {
        let (vm, catalog) = try await fresh()
        catalog.logReplies = [.success(try seededLogs(1, total: 1))]
        vm.keyword = "晨报"
        vm.keyword = "每日晨报"
        try await waitUntil { catalog.logRequests.count == 2 }
        try await Task.sleep(for: .milliseconds(700))
        XCTAssertEqual(catalog.logRequests.count, 2, "one request per pause, not per keystroke")
        XCTAssertEqual(catalog.logRequests.last?.filter.keyword, "每日晨报")
        XCTAssertNil(vm.filter.keyword, "the struct itself never carries the typed text")
    }

    /// The editor's 确定 writes both bounds at once, which is what keeps a window edit to a single request
    /// (`§5.3`'s 时间窗口 bullet); two `@Published` writes would be two refreshes and two page resets.
    func testAWholeWindowIsOneWriteAndOneRequest() async throws {
        let (vm, catalog) = try await fresh()
        catalog.logReplies = [.success(try seededLogs(1, total: 1))]
        let from = Date(timeIntervalSince1970: 1_758_771_600)
        let to = Date(timeIntervalSince1970: 1_758_858_000)
        vm.setWindow(from: from, to: to)
        try await waitUntil { catalog.logRequests.count == 2 }
        XCTAssertEqual(catalog.logRequests.last?.filter.from, from)
        XCTAssertEqual(catalog.logRequests.last?.filter.to, to)
        XCTAssertTrue(vm.isFiltered)
        XCTAssertEqual(catalog.logRequests.count, 2)
    }

    func testClearingTheWindowIsOneRequestAndLeavesTheListUnfiltered() async throws {
        let (vm, catalog) = try await fresh()
        let from = Date(timeIntervalSince1970: 1_758_771_600)
        catalog.logReplies = [.success(try seededLogs(1, total: 1))]
        vm.setWindow(from: from, to: nil)
        try await waitUntil { catalog.logRequests.count == 2 }
        XCTAssertTrue(vm.isFiltered, "one bound is still a filter")

        catalog.logReplies = [.success(try seededLogs(2, total: 27))]
        vm.clearWindow()
        try await waitUntil { catalog.logRequests.count == 3 }
        XCTAssertNil(catalog.logRequests.last?.filter.from)
        XCTAssertNil(catalog.logRequests.last?.filter.to)
        XCTAssertFalse(vm.isFiltered)
    }

    // MARK: - the cadence (`§5.3`)

    /// 3 s while a row is mid-flight, 5 s whatever else, and never off — an open log screen is one somebody is
    /// watching, unlike the task list whose loop retires itself.
    func testTheLoopKeepsRunningAtFiveSecondsUntilARowIsMidFlight() async throws {
        let catalog = FakeAgentTasks()
        let vm = makeVM(catalog, idleInterval: .seconds(5))
        catalog.logReplies = [.success(try seededLogs(1, total: 1, status: 1))]
        await vm.refresh()
        XCTAssertEqual(vm.nextInterval, .seconds(5))
        XCTAssertTrue(vm.isPolling, "the idle cadence is still a cadence")

        catalog.logReplies = [.success(try seededLogs(1, total: 1, status: 3))]
        await vm.refresh()
        XCTAssertEqual(vm.nextInterval, .seconds(3))

        catalog.logReplies = [.success(try seededLogs(1, total: 1, status: 4))]
        await vm.refresh()
        XCTAssertEqual(vm.nextInterval, .seconds(3), "4 is stopping, which is still mid-flight")

        catalog.logReplies = [.success(try emptyLogs())]
        await vm.refresh()
        XCTAssertEqual(vm.nextInterval, .seconds(5), "an empty page falls back rather than switching off")
        vm.stopPolling()
    }

    func testAFailedTickKeepsTheLoopArmedAndRaisesNoBanner() async throws {
        let catalog = FakeAgentTasks()
        let vm = makeVM(catalog, idleInterval: .milliseconds(20))
        catalog.logReplies = [.success(try seededLogs(2, total: 27))]
        await vm.refresh()
        catalog.logReplies = [.failure(.offline), .failure(.offline), .failure(.offline)]
        try await waitUntil { catalog.logRequests.count >= 2 }
        XCTAssertTrue(vm.isPolling, "one failed re-read does not end the loop")
        XCTAssertNil(vm.inlineError)
        XCTAssertEqual(vm.phase, .content, "the rows a failed tick could not refresh stay on screen")
        vm.stopPolling()
    }

    func testABackgroundedScreenParksTheLoopAndComingBackReArmsIt() async throws {
        let catalog = FakeAgentTasks()
        let vm = makeVM(catalog, idleInterval: .milliseconds(20))
        catalog.logReplies = [.success(try seededLogs(2, total: 27))]
        await vm.refresh()
        XCTAssertTrue(vm.isPolling)

        vm.pausePolling()
        XCTAssertFalse(vm.isPolling, "a background timer would spend requests on rows nobody is looking at")
        let requestsWhileParked = catalog.logRequests.count
        try await Task.sleep(for: .milliseconds(120))
        XCTAssertEqual(catalog.logRequests.count, requestsWhileParked)

        vm.resumePolling()
        XCTAssertTrue(vm.isPolling)
        try await waitUntil { catalog.logRequests.count > requestsWhileParked }
        vm.stopPolling()
    }

    // MARK: - the detail pane (`§5.4`)

    /// There is no endpoint for one log, so the pane follows the polled copy of the same row rather than the
    /// snapshot it was opened with — that is the only way a live run's duration and answer appear here.
    func testTheDetailFollowsThePolledCopyOfItsOwnRow() async throws {
        let (vm, catalog) = try await fresh()
        let opened = try XCTUnwrap(vm.items.first)
        vm.beginDetail(opened)
        XCTAssertEqual(vm.selected?.id, opened.id)

        catalog.logReplies = [.success(
            try PageStub.page(
                AgentTaskLog.self,
                [logRow(901, status: 3, durationMs: 16_340, response: "还在汇总"), logRow(902)],
                total: 27,
                pageSize: 10
            )
        )]
        await vm.refresh()
        let followed = try XCTUnwrap(vm.selected)
        XCTAssertEqual(followed.id, opened.id, "the selection stays on the row the user opened")
        XCTAssertEqual(followed.durationMs, 16_340, "…and shows the fresh copy, not the one it opened with")
        XCTAssertEqual(followed.durationText, "16.3s")
        XCTAssertEqual(followed.answerText, "还在汇总")
    }

    /// A row that drops off the page while its pane is open keeps showing what was already in hand: the screen
    /// has no second read to fall back to and re-loading the whole page for one detail would be that read.
    func testADetailOfARowTheNextPageNoLongerHasStaysOnTheOldCopy() async throws {
        let (vm, catalog) = try await fresh()
        let opened = try XCTUnwrap(vm.items.first)
        vm.beginDetail(opened)

        catalog.logReplies = [.success(
            try PageStub.page(AgentTaskLog.self, [logRow(902, response: "另一条")], total: 27, pageSize: 10)
        )]
        let readsBefore = catalog.logRequests.count
        await vm.refresh()
        XCTAssertEqual(vm.selected, opened, "no request exists that could replace it")
        XCTAssertEqual(catalog.logRequests.count, readsBefore + 1, "the pane costs the page nothing extra")
    }

    func testClosingTheDetailSendsNoReadAtAll() async throws {
        let (vm, catalog) = try await fresh()
        vm.beginDetail(try XCTUnwrap(vm.items.first))
        let reads = catalog.logRequests.count
        vm.closeDetail()
        XCTAssertNil(vm.selected)
        XCTAssertEqual(catalog.logRequests.count, reads)
    }

    // MARK: - the stop (`§5.5`)

    /// Asking first, because the route really does interrupt a live agent execution.
    func testAnAcceptedStopIsAddressedByTheLogIdAndSaysOnlyThatItWasSent() async throws {
        let (vm, catalog) = try await freshLive()
        let live = try XCTUnwrap(vm.items.first)
        catalog.logReplies = [.success(try seededLogs(2, total: 27, status: 3))]

        vm.beginStop(live)
        XCTAssertEqual(vm.stopTarget, live, "the sheet opens on the caller's choice, not on the answer")

        catalog.stopReplies = [.success(EmptyResponse())]
        await vm.confirmStop()
        XCTAssertEqual(catalog.stopRequests, [live.id], "the address is the log, never the task")
        XCTAssertNil(vm.stopTarget)
        XCTAssertEqual(vm.noticeLine, hx("task.log.stop.sent"))
        XCTAssertNil(vm.inlineError, "an accepted request is not a failure the banner should claim")

        try await waitUntil { catalog.logRequests.count == 2 }
        XCTAssertEqual(
            vm.noticeLine,
            hx("task.log.stop.sent"),
            "the settled-by-the-next-read reload is silent, so it cannot take the notice back"
        )
    }

    /// 500 "Task is not running or already completed" is what a row that finished between the tap and the request
    /// answers with (`SchedulerController.kt:144-150`), and it is the only explanation on offer.
    func testARefusedStopSaysWhyInTheSchedulersWordsAndStartsNoReRead() async throws {
        let (vm, catalog) = try await freshLive()
        let live = try XCTUnwrap(vm.items.first)
        catalog.stopReplies = [
            .failure(.business(code: 500, message: "Task is not running or already completed"))
        ]
        vm.beginStop(live)
        await vm.confirmStop()
        XCTAssertEqual(vm.inlineError, "Task is not running or already completed")
        XCTAssertNil(vm.noticeLine, "a refusal is not a stop that was sent")
        XCTAssertEqual(catalog.logRequests.count, 1, "nothing changed, so there is nothing to re-read")
    }

    func testTheRowShowsASpinnerWhileItsStopIsOutstanding() async throws {
        let (vm, catalog) = try await freshLive()
        let live = try XCTUnwrap(vm.items.first)
        catalog.gateWrites = true
        catalog.stopReplies = [.success(EmptyResponse())]
        let write = Task { await vm.stop(live) }
        try await waitUntil { catalog.stopRequests.count == 1 }
        XCTAssertEqual(vm.stoppingIDs, [live.id])
        catalog.releaseWrites()
        await write.value
        XCTAssertTrue(vm.stoppingIDs.isEmpty)
    }

    func testACancelledConfirmationSendsNoStop() async throws {
        let (vm, catalog) = try await freshLive()
        vm.beginStop(try XCTUnwrap(vm.items.first))
        vm.cancelStop()
        XCTAssertNil(vm.stopTarget)
        await vm.confirmStop()
        XCTAssertTrue(catalog.stopRequests.isEmpty)
        XCTAssertNil(vm.noticeLine)
    }

    /// A `4` is already on its way down, either to `5` or to `2` (`§5.2`), so the screen offers nothing on it
    /// and `stop` refuses it even when called directly.
    func testARowAlreadyStoppingIsNotOfferedASecondStop() async throws {
        let catalog = FakeAgentTasks()
        catalog.logReplies = [.success(try seededLogs(1, total: 1, status: 4))]
        let vm = makeVM(catalog)
        await vm.refresh()
        let stopping = try XCTUnwrap(vm.items.first)
        XCTAssertFalse(vm.stoppable(stopping))

        vm.beginStop(stopping)
        XCTAssertNil(vm.stopTarget)
        await vm.stop(stopping)
        XCTAssertTrue(catalog.stopRequests.isEmpty)
    }

    func testAFinishedRowIsNotStoppableEvenForItsOwnCreator() async throws {
        let catalog = FakeAgentTasks()
        catalog.logReplies = [.success(
            try PageStub.page(
                AgentTaskLog.self,
                [logRow(901, status: 1), logRow(902, status: 2), logRow(903, status: 5)],
                total: 3,
                pageSize: 10
            )
        )]
        let vm = makeVM(catalog)
        await vm.refresh()
        XCTAssertEqual(vm.items.map { vm.stoppable($0) }, [false, false, false])
        vm.beginStop(vm.items[0])
        XCTAssertNil(vm.stopTarget)
    }

    /// Seeing a log is wider than stopping its execution: the read joins on the task's visibility while the stop
    /// runs `requireOwnedLog` with no administrator pass (`§5.5`).
    func testSomeoneElsesLiveRowIsReadableButNotStoppable() async throws {
        let catalog = FakeAgentTasks()
        catalog.logReplies = [
            .success(try PageStub.page(AgentTaskLog.self, [logRow(901, status: 3, creator: "liwei")], pageSize: 10))
        ]
        let vm = makeVM(catalog, account: AccountSnapshot(username: "admin", isAdministrator: true))
        await vm.refresh()
        let someoneElses = try XCTUnwrap(vm.items.first)
        XCTAssertEqual(someoneElses.status, 3, "the row is live, and readable, but not this account's")
        XCTAssertFalse(vm.stoppable(someoneElses))
        vm.beginStop(someoneElses)
        XCTAssertNil(vm.stopTarget)
        await vm.stop(someoneElses)
        XCTAssertTrue(catalog.stopRequests.isEmpty)
    }

    func testAnAccountWithNoUsernameStopsNothing() async throws {
        let catalog = FakeAgentTasks()
        catalog.logReplies = [.success(try seededLogs(1, total: 1, status: 3))]
        let vm = makeVM(catalog, account: nil)
        await vm.refresh()
        XCTAssertFalse(vm.stoppable(try XCTUnwrap(vm.items.first)))
    }

    // MARK: - leaving the screen

    func testStopPollingTakesTheDelayedStopReloadWithIt() async throws {
        let catalog = FakeAgentTasks()
        let vm = makeVM(catalog, stopReloadDelay: .milliseconds(40))
        catalog.logReplies = [.success(try seededLogs(1, total: 1, status: 3))]
        await vm.refresh()
        catalog.stopReplies = [.success(EmptyResponse())]
        await vm.stop(try XCTUnwrap(vm.items.first))
        vm.stopPolling()
        try await Task.sleep(for: .milliseconds(200))
        XCTAssertEqual(catalog.logRequests.count, 1, "the one-second re-read was cancelled with the sheet")
        XCTAssertFalse(vm.isPolling)
    }

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }
}

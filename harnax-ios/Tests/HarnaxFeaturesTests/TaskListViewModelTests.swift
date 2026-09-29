import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// `/agent/task`'s list, in the order its writes and their answers interleave.
///
/// Three behaviours here are not the app's taste but the scheduler's shape, so each gets its own test: the page
/// is the only verdict on a switch (`ResultVo<Void>`, `§1`), a successful save pauses the task whatever the body
/// said (`AgentTaskCrudServiceImpl.kt:156-157`), and the 3-second poll exists only while a row is mid-flight
/// (`harnax-webui/src/pages/agent-task/index.tsx:67-80`).
///
/// The server's sentences are shown verbatim and never routed through the catalogue: admin forwards the
/// scheduler's English byte for byte and `Accept-Language` buys nothing on this domain (`§0`).
@MainActor
final class TaskListViewModelTests: XCTestCase {
    private func row(
        _ name: String,
        id: Any? = 41,
        taskStatus: Int = 1,
        lastRunStatus: Int? = 1,
        creator: String = "admin"
    ) -> [String: Any] {
        var json: [String: Any] = [
            "name": name,
            "agentName": "翻译助手",
            "prompt": "汇总昨天的构建失败",
            "cronExpression": "0 0 9 * * ?",
            "taskStatus": taskStatus,
            "concurrent": 0,
            "timeoutSeconds": 300,
            "description": "",
            "isPublic": 1,
            "creator": creator,
            "active": 1,
            "createTime": "2026-09-20 09:14:02",
            "updateTime": "2026-09-25 18:40:11",
        ]
        if let id { json["id"] = id }
        if let agentId = id as? Int { json["agentId"] = agentId }
        if let lastRunStatus { json["lastRunStatus"] = lastRunStatus }
        return json
    }

    private func seeded(
        _ count: Int,
        total: Int? = nil,
        pageNum: Int = 1,
        taskStatus: Int = 1,
        lastRunStatus: Int? = 1,
        firstID: Int = 41
    ) throws -> Page<AgentTaskSummary> {
        try PageStub.page(
            AgentTaskSummary.self,
            (0..<count).map { index in
                row(
                    "任务 \(firstID + index)",
                    id: firstID + index,
                    taskStatus: taskStatus,
                    lastRunStatus: lastRunStatus
                )
            },
            pageNum: pageNum,
            total: total ?? count
        )
    }

    private func empty() throws -> Page<AgentTaskSummary> {
        try PageStub.page(AgentTaskSummary.self, [], total: 0)
    }

    /// The poll is left disarmed unless a test asks for it: an in-flight row arms a real timer, and a test that
    /// is only looking at a write must not have a re-read land in the middle of it.
    private func makeVM(
        _ catalog: FakeAgentTasks,
        pollInterval: Duration = .seconds(3_600),
        reloadDelay: Duration = .milliseconds(20)
    ) -> TaskListViewModel {
        TaskListViewModel(catalog: catalog, pollInterval: pollInterval, triggerReloadDelay: reloadDelay)
    }

    private func fresh() async throws -> (TaskListViewModel, FakeAgentTasks) {
        let catalog = FakeAgentTasks()
        catalog.pageReplies = [.success(try seeded(2))]
        let vm = makeVM(catalog)
        await vm.refresh()
        return (vm, catalog)
    }

    // MARK: - the page

    func testTheFirstPageBecomesRowsNotASpinner() async throws {
        let (vm, catalog) = try await fresh()
        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertEqual(catalog.pageRequests.map(\.num), [1], "this page is addressed from one, not zero")
        XCTAssertEqual(catalog.pageRequests.map(\.size), [20])
        XCTAssertFalse(vm.canLoadMore)
        XCTAssertFalse(vm.isFiltered)
        XCTAssertNil(vm.inlineError)
    }

    func testTheCounterComesFromTheStackNotFromTheRowsOnScreen() async throws {
        let catalog = FakeAgentTasks()
        catalog.pageReplies = [.success(try seeded(2, total: 5))]
        let vm = makeVM(catalog)
        await vm.refresh()
        XCTAssertEqual(vm.total, 5)
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertTrue(vm.canLoadMore)
    }

    /// The running flag is `taskStatus` on this route and takes `0` as a real value
    /// (`harnax-webui/src/pages/agent-task/index.tsx:288-298`), so "paused" must not degrade into "all".
    func testTheStatusFilterGoesOutAsTaskStatusAndReloadsThePage() async throws {
        let (vm, catalog) = try await fresh()
        catalog.pageReplies = [.success(try seeded(1, total: 1, taskStatus: 0))]
        vm.filter = .disabled
        try await waitUntil { catalog.pageRequests.count == 2 }
        XCTAssertEqual(catalog.pageRequests.last?.taskStatus, 0, "0 is the paused filter, not an absence")

        catalog.pageReplies = [.success(try seeded(2))]
        vm.filter = .enabled
        try await waitUntil { catalog.pageRequests.count == 3 }
        XCTAssertEqual(catalog.pageRequests.last?.taskStatus, 1)

        catalog.pageReplies = [.success(try seeded(2))]
        vm.filter = .all
        try await waitUntil { catalog.pageRequests.count == 4 }
        XCTAssertNil(catalog.pageRequests.last?.taskStatus, "all leaves the parameter off the query")
        XCTAssertFalse(vm.isFiltered)
    }

    func testAnAccountThatOwnsNoTaskIsNotASpinningWheel() async throws {
        let catalog = FakeAgentTasks()
        catalog.pageReplies = [.success(try empty())]
        let vm = makeVM(catalog)
        await vm.refresh()
        XCTAssertEqual(vm.phase, .empty)
        XCTAssertFalse(vm.isFiltered, "the card may then promise there is no task at all")
    }

    func testNothingUnderAFilterIsNoMatchesNotAnEmptyAccount() async throws {
        let catalog = FakeAgentTasks()
        let vm = makeVM(catalog)
        vm.keyword = "找不到的任务"
        catalog.pageReplies = [.success(try empty())]
        await vm.refresh()
        XCTAssertEqual(vm.phase, .empty)
        XCTAssertTrue(vm.isFiltered)
    }

    func testAFailedFirstLoadBecomesAnErrorStateNotAnEmptyCard() async throws {
        let catalog = FakeAgentTasks()
        catalog.pageReplies = [.failure(.offline)]
        let vm = makeVM(catalog)
        await vm.refresh()
        XCTAssertEqual(vm.phase, .failed(hx("error.offline")))
        XCTAssertNil(vm.inlineError, "there is nothing on screen for a banner to sit above")
    }

    func testAFailedRefreshKeepsTheRowsAndRaisesABanner() async throws {
        let (vm, catalog) = try await fresh()
        catalog.pageReplies = [.failure(.timeout)]
        await vm.refresh()
        XCTAssertEqual(vm.phase, .content, "a refresh that failed must not blank the list")
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertEqual(vm.inlineError, hx("error.timeout"))
    }

    func testTheKeywordGoesOutAsTypedAndThePageResetsToOne() async throws {
        let (vm, catalog) = try await fresh()
        catalog.pageReplies = [.success(try seeded(1, total: 1))]
        vm.keyword = "每日 晨报"
        try await waitUntil { catalog.pageRequests.count == 2 }
        XCTAssertEqual(catalog.pageRequests.last?.name, "每日 晨报", "no trimming, no case folding — it is a LIKE")
        XCTAssertEqual(catalog.pageRequests.last?.num, 1)
    }

    func testAStoppedTypingKeywordIsOneRequestNotOnePerKeystroke() async throws {
        let (vm, catalog) = try await fresh()
        catalog.pageReplies = [.success(try seeded(1, total: 1))]
        vm.keyword = "每日"
        vm.keyword = "每日晨"
        vm.keyword = "每日晨报"
        try await waitUntil { catalog.pageRequests.count == 2 }
        try await Task.sleep(for: .milliseconds(700))
        XCTAssertEqual(catalog.pageRequests.count, 2, "the earlier two keystrokes never went out")
        XCTAssertEqual(catalog.pageRequests.last?.name, "每日晨报")
    }

    func testTheSecondPageIsAppendedAndAFailedOneRetriesTheSameNumber() async throws {
        let catalog = FakeAgentTasks()
        catalog.pageReplies = [.success(try seeded(2, total: 5))]
        let vm = makeVM(catalog)
        await vm.refresh()
        XCTAssertTrue(vm.canLoadMore)

        catalog.pageReplies = [.failure(.offline)]
        await vm.loadMore()
        XCTAssertEqual(vm.items.count, 2, "the rows on screen stay")
        XCTAssertEqual(vm.inlineError, hx("error.offline"))

        catalog.pageReplies = [.success(try seeded(1, total: 5, pageNum: 2, firstID: 43))]
        await vm.loadMore()
        XCTAssertEqual(catalog.pageRequests.map(\.num), [1, 2, 2], "the page that failed is asked for again")
        XCTAssertEqual(vm.items.count, 3, "the fresh ids are the ones append keeps — a repeated id is a duplicate")
        XCTAssertNil(vm.inlineError)
    }

    // MARK: - the switch

    func testTheSwitchFlipsTheRowBeforeTheStackAnswers() async throws {
        let (vm, catalog) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        let id = try XCTUnwrap(row.id)
        XCTAssertTrue(vm.status(of: row))

        catalog.gateWrites = true
        catalog.statusReplies = [.success(EmptyResponse())]
        let write = Task { await vm.setStatus(false, for: row) }
        try await waitUntil { catalog.statusRequests.count == 1 }
        XCTAssertFalse(vm.status(of: row), "a tap has to look decided on a slow link, not inert")
        XCTAssertEqual(catalog.statusRequests.last?.enabled, false)
        XCTAssertEqual(vm.pendingIDs, [id])
        catalog.releaseWrites()
        await write.value
        XCTAssertFalse(vm.status(of: row))
        XCTAssertTrue(vm.pendingIDs.isEmpty)
        XCTAssertEqual(catalog.pageRequests.count, 1, "the switch is not confirmed by re-reading the page")
    }

    /// Starting is the branch the client cannot pre-judge: the backend only counts cron fields
    /// (`AgentTaskCrudServiceImpl.kt:212-225`), so an expression Quartz rejects is refused here, at `start`,
    /// in the scheduler's own English.
    func testARefusedStartSnapsTheRowBackAndSaysWhyInTheSchedulersWords() async throws {
        let (vm, catalog) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        catalog.gateWrites = true
        catalog.statusReplies = [
            .failure(.business(code: 500, message: "Invalid cron expression: not enough fields"))
        ]
        let write = Task { await vm.setStatus(true, for: row) }
        try await waitUntil { catalog.statusRequests.count == 1 }
        XCTAssertTrue(vm.status(of: row))
        catalog.releaseWrites()
        await write.value
        XCTAssertTrue(vm.status(of: row), "the override is dropped, so the row reads as the page left it")
        XCTAssertEqual(vm.inlineError, "Invalid cron expression: not enough fields")
        XCTAssertEqual(vm.total, 2)
    }

    /// The override is a stopgap: once the write has answered, the page carries the value it was standing in
    /// for, and keeping it would let a poll leave a switch pointing the wrong way for good.
    func testAReReadPageOutranksACompletedSwitch() async throws {
        let (vm, catalog) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        catalog.statusReplies = [.success(EmptyResponse())]
        await vm.setStatus(false, for: row)
        XCTAssertFalse(vm.status(of: row))

        catalog.pageReplies = [.success(try seeded(2, taskStatus: 1))]
        await vm.refresh()
        XCTAssertTrue(vm.status(of: row))
    }

    // MARK: - run now

    func testAnAcceptedRunMarksTheRowAndBringsTheListBackAroundAgain() async throws {
        let (vm, catalog) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        let id = try XCTUnwrap(row.id)
        catalog.triggerReplies = [.success(EmptyResponse())]
        catalog.pageReplies = [.success(try seeded(2, lastRunStatus: 3))]

        let accepted = await vm.trigger(row)
        XCTAssertTrue(accepted, "the sheet only opens the log on a yes")
        XCTAssertEqual(catalog.triggerRequests, [id])
        XCTAssertEqual(vm.triggeredIDs, [id], "the row says a run is on its way before the stack does")
        try await waitUntil { catalog.pageRequests.count == 2 }
        XCTAssertTrue(vm.triggeredIDs.isEmpty, "the re-read is what settles the badge")
        XCTAssertEqual(catalog.pageRequests.last?.num, 1)
    }

    func testARefusedRunSaysWhyAndStartsNoReRead() async throws {
        let (vm, catalog) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        // 40901: a run is already in flight and this task forbids it (`§7.2`).
        catalog.triggerReplies = [
            .failure(.business(code: 40901, message: "Task is already running"))
        ]
        let accepted = await vm.trigger(row)
        XCTAssertFalse(accepted)
        XCTAssertEqual(vm.inlineError, "Task is already running")
        XCTAssertTrue(vm.triggeredIDs.isEmpty)
        XCTAssertEqual(catalog.pageRequests.count, 1, "a refusal is not news that needs the page re-read")
    }

    /// `§2.4` lists edit, delete and start/stop as creator-only; a run request goes through the same visibility
    /// read as the list, so whoever can see a shared task can start it.
    func testRunNowNeedsNoOwnership() async throws {
        let catalog = FakeAgentTasks()
        catalog.pageReplies = [
            .success(
                try PageStub.page(
                    AgentTaskSummary.self,
                    [row("共享任务", lastRunStatus: nil, creator: "liwei")],
                    total: 1
                )
            )
        ]
        let vm = makeVM(catalog, reloadDelay: .seconds(3_600))
        await vm.refresh()
        let someoneElses = try XCTUnwrap(vm.items.first)
        XCTAssertEqual(someoneElses.creator, "liwei", "the row must not be this account's own")

        catalog.triggerReplies = [.success(EmptyResponse())]
        let accepted = await vm.trigger(someoneElses)
        XCTAssertTrue(accepted)
        XCTAssertEqual(catalog.triggerRequests.count, 1)
    }

    func testARowTheWireGaveNoIdForTakesNoWriteAtAll() async throws {
        let catalog = FakeAgentTasks()
        catalog.pageReplies = [.success(try PageStub.page(AgentTaskSummary.self, [row("无 id", id: nil)], total: 1))]
        let vm = makeVM(catalog)
        await vm.refresh()
        let keyless = try XCTUnwrap(vm.items.first)

        catalog.statusReplies = [.success(EmptyResponse())]
        catalog.triggerReplies = [.success(EmptyResponse())]
        await vm.setStatus(false, for: keyless)
        let accepted = await vm.trigger(keyless)
        XCTAssertFalse(accepted, "the run route is addressed by the task id, which this row has none of")
        XCTAssertTrue(catalog.statusRequests.isEmpty)
        XCTAssertTrue(catalog.triggerRequests.isEmpty)
        vm.beginDelete(keyless)
        XCTAssertNil(vm.deleteTarget)
        XCTAssertTrue(catalog.deleteRequests.isEmpty)
    }

    // MARK: - delete

    func testADeleteTakesTheRowOutOfTheListAndOffTheCounter() async throws {
        let (vm, catalog) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        let id = try XCTUnwrap(row.id)
        catalog.deleteReplies = [.success(EmptyResponse())]
        vm.beginDelete(row)
        XCTAssertEqual(vm.deleteTarget, row, "the sheet opens on the caller's choice, not on the answer")
        await vm.confirmDelete()
        XCTAssertEqual(catalog.deleteRequests, [id])
        XCTAssertNil(vm.deleteTarget)
        XCTAssertEqual(vm.items.count, 1)
        XCTAssertEqual(vm.total, 1, "otherwise hasMore promises a page with one more row than it has")
    }

    func testACancelledConfirmationSendsNoDelete() async throws {
        let (vm, catalog) = try await fresh()
        vm.beginDelete(try XCTUnwrap(vm.items.first))
        vm.cancelDelete()
        XCTAssertNil(vm.deleteTarget)
        await vm.confirmDelete()
        XCTAssertTrue(catalog.deleteRequests.isEmpty)
        XCTAssertEqual(vm.items.count, 2)
    }

    func testARefusedDeleteLeavesTheRowUpWithTheReason() async throws {
        let (vm, catalog) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        catalog.deleteReplies = [
            .failure(.business(code: 400, message: "Only the task creator can delete this task"))
        ]
        vm.beginDelete(row)
        await vm.confirmDelete()
        XCTAssertEqual(vm.items.count, 2, "the server kept it, so the list keeps it")
        XCTAssertEqual(vm.inlineError, "Only the task creator can delete this task")
        XCTAssertNil(vm.deleteTarget)
        XCTAssertTrue(vm.noticeLines.isEmpty)
    }

    /// 40902 on a delete is "gone from the table, still in someone's Quartz store". The console treats it as
    /// done (`harnax-webui/src/pages/agent-task/index.tsx:158-162`), so the row leaves and the sentence goes to
    /// the warning band rather than the error one.
    func testASyncFailureOnDeleteIsACompletedWriteWithNewsAttached() async throws {
        let (vm, catalog) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        catalog.deleteReplies = [
            .failure(.business(code: 40902, message: "Task deleted but scheduler sync failed"))
        ]
        vm.beginDelete(row)
        await vm.confirmDelete()
        XCTAssertEqual(vm.items.count, 1)
        XCTAssertEqual(vm.total, 1)
        XCTAssertNil(vm.inlineError, "a red banner under a write that succeeded teaches distrust of the banner")
        XCTAssertEqual(vm.noticeLines, ["Task deleted but scheduler sync failed"])
    }

    // MARK: - what the form leaves behind

    /// Re-read first, then speak: a saved edit has paused the task server-side and the row must stop claiming
    /// otherwise before the note explains why it changed (`§3.2`, `§6.4`).
    func testSavingAFormReReadsThePageBeforeItSpeaks() async throws {
        let (vm, catalog) = try await fresh()
        catalog.pageReplies = [.success(try seeded(2, taskStatus: 0)), .success(try seeded(2, taskStatus: 0))]
        await vm.formDidSave(
            TaskFormSaveOutcome(didPauseScheduledTask: true, schedulerSyncWarning: "Scheduler sync failed")
        )
        XCTAssertEqual(catalog.pageRequests.count, 2, "the save is followed by exactly one re-read")
        XCTAssertEqual(vm.noticeLines, [hx("task.form.paused.notice"), "Scheduler sync failed"])
        XCTAssertNil(vm.inlineError)
    }

    func testACreateLeavesNoPauseNoticeBecauseTheServerStartedItPaused() async throws {
        let (vm, catalog) = try await fresh()
        catalog.pageReplies = [.success(try seeded(2))]
        await vm.formDidSave(TaskFormSaveOutcome(didPauseScheduledTask: false))
        XCTAssertTrue(vm.noticeLines.isEmpty)
        XCTAssertEqual(catalog.pageRequests.count, 2)
    }

    // MARK: - the poll (`§2.5`)

    /// The whole rule in one line: this page is only worth re-reading while a row is mid-flight, and the loop
    /// retires itself the moment the data stops asking.
    func testThePagePollsOnlyWhileARowIsMidFlight() async throws {
        let catalog = FakeAgentTasks()
        // `isPolling` answers whether the timer is armed, so a loop that never fires inside the test still
        // proves the arm-and-retire rule without racing its own queued replies.
        let vm = makeVM(catalog)

        catalog.pageReplies = [.success(try seeded(1, total: 1, lastRunStatus: nil))]
        await vm.refresh()
        XCTAssertFalse(vm.shouldPoll, "a task that never fired has nothing in flight")
        XCTAssertFalse(vm.isPolling)

        catalog.pageReplies = [.success(try seeded(1, total: 1, lastRunStatus: 3))]
        await vm.refresh()
        XCTAssertTrue(vm.shouldPoll, "3 is running")
        XCTAssertTrue(vm.isPolling)

        catalog.pageReplies = [.success(try seeded(1, total: 1, lastRunStatus: 4))]
        await vm.refresh()
        XCTAssertTrue(vm.shouldPoll, "4 is stopping, which is still in flight")

        catalog.pageReplies = [.success(try seeded(1, total: 1, lastRunStatus: 1))]
        await vm.refresh()
        XCTAssertFalse(vm.shouldPoll, "…and a settled badge cancels the timer on the spot")
        XCTAssertFalse(vm.isPolling)
        vm.stopPolling()
    }

    func testAStoppableRowIsNotAReasonToKeepPolling() {
        // `isInFlight` is the pair the web keys on; 5 stopped must not join it.
        XCTAssertEqual(AgentTaskLastRun.stopped.isInFlight, false)
        XCTAssertEqual(AgentTaskLastRun.timedOut.isInFlight, false)
        XCTAssertEqual([AgentTaskLastRun.running, .stopping].map(\.isInFlight), [true, true])
    }

    /// A poll is not news. The console's loop swallows a failed tick outright
    /// (`harnax-webui/src/pages/agent-task/components/TaskLogModal.tsx:107`), because a banner every three
    /// seconds over a list that is merely stale is worse than the staleness.
    func testAFailedTickKeepsTheLoopArmedAndRaisesNoBanner() async throws {
        let catalog = FakeAgentTasks()
        let vm = makeVM(catalog, pollInterval: .milliseconds(20))
        catalog.pageReplies = [.success(try seeded(1, total: 1, lastRunStatus: 3))]
        await vm.refresh()
        catalog.pageReplies = [.failure(.offline), .failure(.offline), .failure(.offline)]
        try await waitUntil { catalog.pageRequests.count >= 2 }
        XCTAssertTrue(vm.isPolling, "one failed re-read does not end the loop")
        XCTAssertNil(vm.inlineError)
        XCTAssertEqual(vm.phase, .content, "the rows a failed tick could not refresh stay on screen")
        vm.stopPolling()
    }

    func testAPollDoesNotWipeTheNoticeTheSaveLeft() async throws {
        let catalog = FakeAgentTasks()
        let vm = makeVM(catalog, pollInterval: .milliseconds(20))
        let inFlight = try seeded(1, total: 1, lastRunStatus: 3)
        catalog.pageReplies = Array(repeating: .success(inFlight), count: 4)
        await vm.refresh()
        await vm.formDidSave(TaskFormSaveOutcome(didPauseScheduledTask: true))
        XCTAssertEqual(vm.noticeLines, [hx("task.form.paused.notice")])
        try await waitUntil { catalog.pageRequests.count >= 3 }
        XCTAssertEqual(
            vm.noticeLines,
            [hx("task.form.paused.notice")],
            "…and the tick three seconds later must not take it back"
        )
        vm.stopPolling()
    }

    /// `scenePhase != .active`: parked, not stopped, so coming back re-arms from the data rather than a pull.
    func testABackgroundedScreenParksTheLoopAndComingBackReArmsIt() async throws {
        let catalog = FakeAgentTasks()
        let vm = makeVM(catalog, pollInterval: .milliseconds(20))
        catalog.pageReplies = [.success(try seeded(1, total: 1, lastRunStatus: 3))]
        await vm.refresh()
        XCTAssertTrue(vm.isPolling)

        vm.pausePolling()
        XCTAssertFalse(vm.isPolling, "a background timer would spend requests on rows nobody is looking at")
        let requestsWhileParked = catalog.pageRequests.count
        try await Task.sleep(for: .milliseconds(120))
        XCTAssertEqual(catalog.pageRequests.count, requestsWhileParked)

        vm.resumePolling()
        XCTAssertTrue(vm.isPolling)
        try await waitUntil { catalog.pageRequests.count > requestsWhileParked }
        vm.stopPolling()
    }

    /// Leaving the screen cancels the timer outright, so a later `resumePolling` cannot resurrect a view nobody
    /// is watching.
    func testStoppingThePollTakesTheDelayedRunReloadWithIt() async throws {
        let catalog = FakeAgentTasks()
        let vm = makeVM(catalog, pollInterval: .milliseconds(20), reloadDelay: .milliseconds(40))
        catalog.pageReplies = [.success(try seeded(1, total: 1, lastRunStatus: nil))]
        await vm.refresh()
        catalog.triggerReplies = [.success(EmptyResponse())]
        let runTarget = try XCTUnwrap(vm.items.first)
        let accepted = await vm.trigger(runTarget)
        XCTAssertTrue(accepted)
        vm.stopPolling()
        try await Task.sleep(for: .milliseconds(200))
        XCTAssertEqual(catalog.pageRequests.count, 1, "the 1.5 s re-read was cancelled with the screen")
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

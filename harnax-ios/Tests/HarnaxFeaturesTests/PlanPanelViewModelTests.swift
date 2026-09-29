import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// The plan drawer's two reads, its one cadence and its accordion (`§计划面板（右侧滑出）`).
///
/// The interesting behaviours are all asymmetries the console carries and a fresh implementation would "fix":
/// the current plan re-reads every 2 s while the history never re-reads on a timer at all
/// (`ChatWindow.tsx:649-669` against the declared-but-never-started `plansListTimerRef` at `:603`); a manual
/// refresh prepends unseen `planId`s instead of rebuilding the rows (`:2603-2648`); and a plan with no name is
/// absent rather than blank (`hasValidCurrentPlan`, `:2965-3038`). Each gets its own test below.
///
/// Copy assertions go through `PlanState.titleKey` rather than restating the four words, because the contract
/// owns them and the catalogues already define them.
@MainActor
final class PlanPanelViewModelTests: XCTestCase {
    private let session = PlanFixtures.session

    /// The panel's real 2 s cadence would make the non-polling tests wait seconds and the polling test wait
    /// longer than it is worth.
    private func makeVM(
        _ reading: FakePlanReading,
        isEnabled: Bool = true,
        pollInterval: Duration = .seconds(2)
    ) -> PlanPanelViewModel {
        PlanPanelViewModel(sessionId: session, reading: reading, isEnabled: isEnabled, pollInterval: pollInterval)
    }

    private func seeded(
        history: [PlanNote],
        current: PlanNote?,
        isEnabled: Bool = true,
        pollInterval: Duration = .seconds(2)
    ) async -> (PlanPanelViewModel, FakePlanReading) {
        let reading = FakePlanReading()
        reading.historyReplies = [.success(history)]
        reading.currentReplies = [PlanFixtures.open(current)]
        let vm = makeVM(reading, isEnabled: isEnabled, pollInterval: pollInterval)
        await vm.open()
        return (vm, reading)
    }

    // MARK: - opening

    func testOpeningReadsEachSideOnceAndAddressesBothToTheSession() async {
        let (vm, reading) = await seeded(history: [PlanFixtures.note("p1")], current: PlanFixtures.note("p2"))
        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(reading.historyRequests, [session], "the history is read once on open, not once per pane")
        XCTAssertEqual(reading.currentRequests, [session])
        XCTAssertEqual(vm.history.map(\.planId), ["p1"])
        XCTAssertEqual(vm.current?.planId, "p2")
        XCTAssertNil(vm.inlineError)
        XCTAssertFalse(vm.isLoadingHistory, "the read's own spinner goes with the reply")
    }

    func testOpeningThePanelTwiceDoesNotReadItTwice() async {
        let (vm, reading) = await seeded(history: [PlanFixtures.note("p1")], current: nil)
        await vm.open()
        await vm.open()
        XCTAssertEqual(reading.historyRequests.count, 1)
        XCTAssertEqual(reading.currentRequests.count, 1)
    }

    func testAPlanWithNoNameIsAbsentRatherThanAnEmptyCard() async {
        // The runtime can hand back a half-written note. `subtasks` alone does not make it a plan: the console's
        // validity flag is the name, and `PlanNote.isValid` is that rule.
        let nameless = PlanFixtures.note("p9", name: nil, subtasks: [PlanFixtures.subtask("读路由配置")])
        let (vm, _) = await seeded(history: [], current: nameless)
        XCTAssertNil(vm.current, "an unnamed plan must not become a card, nor a row, nor a polling subject")
        XCTAssertEqual(vm.phase, .empty, "nothing on either read is an empty panel, not a failed one")
        XCTAssertNil(vm.nextInterval, "and a panel with no plan open asks no questions about a plan")
    }

    // MARK: - the cadence

    func testTheLoopIsArmedForTwoSecondsWhileAllThreeConditionsHold() async {
        let (vm, _) = await seeded(history: [], current: PlanFixtures.note("p2"))
        XCTAssertEqual(vm.nextInterval, .seconds(2), "the console's planRefreshTimerRef interval")
        XCTAssertTrue(vm.isPolling)
        XCTAssertTrue(vm.isLive)
    }

    func testNothingThePanelCanChangeTurnsTheLoopOff() async {
        let (vm, reading) = await seeded(history: [PlanFixtures.note("p1")], current: PlanFixtures.note("p2"))
        XCTAssertTrue(vm.isPolling)

        vm.toggleCurrentPlan()
        XCTAssertFalse(vm.isPolling, "a collapsed card is not a plan anybody is watching")
        vm.toggleCurrentPlan()
        XCTAssertTrue(vm.isPolling)

        vm.isEnabled = false
        XCTAssertFalse(vm.isPolling, "enablePlan off is the console's third guard")
        vm.isEnabled = true
        XCTAssertTrue(vm.isPolling)

        vm.close()
        XCTAssertFalse(vm.isPolling, "closing the drawer clears the timer")
        XCTAssertEqual(reading.currentRequests.count, 1, "and nothing re-reads while it is shut")
    }

    func testNoCurrentPlanMeansNoPollingAtAll() async {
        let (vm, reading) = await seeded(history: [PlanFixtures.note("p1")], current: nil)
        XCTAssertFalse(vm.isPolling, "a session with a history and no open plan still must not poll")
        XCTAssertNil(vm.nextInterval)
        try? await Task.sleep(for: .milliseconds(150))
        XCTAssertEqual(reading.currentRequests.count, 1, "and the loop never fires, so it never asks again")
    }

    func testThePollReReadsTheCurrentPlanAndNeverTheHistory() async {
        let reading = FakePlanReading()
        reading.historyReplies = [.success([PlanFixtures.note("p1")])]
        reading.currentReplies = [PlanFixtures.open(PlanFixtures.note("p2"))]
        // The queue holds one answer each: the fallback is what keeps the fourth tick from failing on an empty
        // queue, so `historyRequests` staying at one is a statement about the loop rather than about the stub.
        reading.currentFallback = PlanFixtures.open(PlanFixtures.note("p2"))
        let vm = makeVM(reading, pollInterval: .milliseconds(20))
        await vm.open()
        try? await Task.sleep(for: .milliseconds(160))
        XCTAssertGreaterThan(reading.currentRequests.count, 2, "2 s at the console's cadence; here, 20 ms")
        XCTAssertEqual(reading.historyRequests.count, 1, "the history reloads on open and on a manual refresh only")
        vm.stopPolling()
    }

    func testAFailedTickStaysQuietAndKeepsTheLoop() async {
        let reading = FakePlanReading()
        reading.historyReplies = [.success([PlanFixtures.note("p1")])]
        reading.currentReplies = [PlanFixtures.open(PlanFixtures.note("p2"))]
        // `loadCurrentPlan` returns in silence on a non-OK response (`ChatWindow.tsx:2489-2492`), so a tick that
        // fails must not raise a band over a card that is still readable.
        reading.currentFallback = .failure(.offline)
        let vm = makeVM(reading, pollInterval: .milliseconds(20))
        await vm.open()
        try? await Task.sleep(for: .milliseconds(100))
        XCTAssertNil(vm.inlineError)
        XCTAssertGreaterThan(reading.currentRequests.count, 2)
        XCTAssertEqual(vm.current?.planId, "p2", "the last good plan stays on screen")
        XCTAssertTrue(vm.isPolling, "one failed re-read does not end the loop")
        vm.stopPolling()
    }

    // MARK: - the history, and its incremental refresh

    func testManualRefreshPrependsOnlyPlansThePanelHasNotSeen() async {
        let old = PlanFixtures.note("p1", name: "旧名字")
        let (vm, reading) = await seeded(history: [old], current: nil)
        // The second reply carries p1 again with its name moved on, plus a genuinely new plan.
        reading.historyReplies = [.success([PlanFixtures.note("p3"), PlanFixtures.note("p1", name: "改过的名字")])]
        await vm.refreshHistory()

        XCTAssertEqual(vm.history.map(\.planId), ["p3", "p1"])
        XCTAssertEqual(vm.history[1].name, "旧名字", "a row already displayed is never rebuilt out from under the reader")
        XCTAssertEqual(reading.historyRequests.count, 2, "open, then the one refresh the user asked for")
    }

    func testARefreshWithNoNewPlanLeavesTheRowsAlone() async {
        let (vm, reading) = await seeded(history: [PlanFixtures.note("p2"), PlanFixtures.note("p1")], current: nil)
        reading.historyReplies = [.success([PlanFixtures.note("p2"), PlanFixtures.note("p1")])]
        await vm.refreshHistory()
        XCTAssertEqual(vm.history.map(\.planId), ["p2", "p1"])
        XCTAssertFalse(vm.isLoadingHistory)
        XCTAssertEqual(reading.currentRequests.count, 1, "the refresh button is handleRefreshPlans and touches no other read")
    }

    func testAnIncrementalRefreshOnAnEmptyPanelIsAFullLoad() async {
        // `loadPlans(true)` only takes the incremental branch `plans.length > 0`
        // (`ChatWindow.tsx:2621-2624`); with nothing displayed there is nothing to prepend to.
        let reading = FakePlanReading()
        reading.historyReplies = [.failure(.offline), .success([PlanFixtures.note("p2"), PlanFixtures.note("p1")])]
        reading.currentReplies = [PlanFixtures.open(PlanFixtures.note("p3"))]
        let vm = makeVM(reading)
        await vm.open()
        XCTAssertEqual(vm.phase, .content, "the current plan answered, so the panel has content to keep")
        XCTAssertEqual(vm.inlineError, ErrorMessage.text(for: .offline))
        XCTAssertTrue(vm.history.isEmpty)
        await vm.refreshHistory()
        XCTAssertEqual(vm.history.map(\.planId), ["p2", "p1"])
        XCTAssertEqual(vm.expandedPlanID, "p2", "a full load re-picks the newest row")
    }

    func testARefreshThatFailsKeepsWhateverIsOnScreen() async {
        let (vm, _) = await seeded(
            history: [PlanFixtures.note("p1", name: "读配置"), PlanFixtures.note("p0", name: "写路由")],
            current: PlanFixtures.note("p2")
        )
        let before = vm.history
        await vm.refreshHistory()
        XCTAssertEqual(vm.history, before, "an unqueued reply fails, and the rows it would have replaced stay put")
        XCTAssertEqual(vm.phase, .content, "the failure is a band over the panel, never an empty panel")
        XCTAssertNotNil(vm.inlineError)
    }

    func testTheFirstLoadThatFailsWithNothingToPreserveReportsItself() async {
        let reading = FakePlanReading()
        let vm = makeVM(reading)
        await vm.open()
        XCTAssertEqual(vm.phase, .failed(ErrorMessage.text(for: .decoding)))
        XCTAssertNil(vm.inlineError, "a screen that is itself the error does not also carry a band")
        XCTAssertFalse(vm.isPolling)
    }

    // MARK: - the accordion

    func testOnlyTheNewestHistoryRowOpensByDefault() async {
        let (vm, _) = await seeded(
            history: [PlanFixtures.note("p3"), PlanFixtures.note("p2"), PlanFixtures.note("p1")],
            current: nil
        )
        // `Collapse accordion defaultActiveKey=[plans[0].planId]`.
        XCTAssertEqual(vm.expandedPlanID, "p3")
        XCTAssertTrue(vm.isHistoryRowExpanded(PlanFixtures.note("p3")))
        XCTAssertFalse(vm.isHistoryRowExpanded(PlanFixtures.note("p2")))
    }

    func testOpeningAnotherRowClosesTheOneThatWasOpen() async {
        let (vm, reading) = await seeded(
            history: [PlanFixtures.note("p2"), PlanFixtures.note("p1")],
            current: nil
        )
        vm.toggleHistoryRow(PlanFixtures.note("p1"))
        XCTAssertEqual(vm.expandedPlanID, "p1", "accordion mode: one row at a time")
        vm.toggleHistoryRow(PlanFixtures.note("p1"))
        XCTAssertNil(vm.expandedPlanID, "and tapping the open row shuts it")
        vm.toggleHistoryRow(PlanFixtures.note("p2"))
        XCTAssertEqual(vm.expandedPlanID, "p2")
        XCTAssertEqual(reading.historyRequests.count, 1, "expansion is a display choice, not a read")
    }

    func testARefreshDoesNotMoveTheRowThatIsOpen() async {
        let (vm, reading) = await seeded(history: [PlanFixtures.note("p2"), PlanFixtures.note("p1")], current: nil)
        vm.toggleHistoryRow(PlanFixtures.note("p1"))
        reading.historyReplies = [.success([PlanFixtures.note("p3"), PlanFixtures.note("p2"), PlanFixtures.note("p1")])]
        await vm.refreshHistory()
        XCTAssertEqual(vm.history.map(\.planId), ["p3", "p2", "p1"])
        XCTAssertEqual(vm.expandedPlanID, "p1", "defaultActiveKey is a mount-time prop; the mount already happened")
    }

    // MARK: - subtask copy and numbers

    func testStateWordsComeFromTheContractsOwnTitleKey() {
        XCTAssertEqual(
            PlanState.allCases.map(\.titleKey),
            ["chat.plan.todo", "chat.plan.inProgress", "chat.plan.done", "chat.plan.abandoned"]
        )
        HarnaxCatalog.shared.language = .en
        XCTAssertEqual(hx(PlanState.done.titleKey), "Done")
        HarnaxCatalog.shared.language = .zhHans
        XCTAssertEqual(hx(PlanState.inProgress.titleKey), "进行中")
        // A row the runtime left without a state is an unfinished row (`PlanSubTask.stateOrTodo`).
        XCTAssertEqual(PlanFixtures.subtask("读配置", state: nil).stateOrTodo, .todo)
    }

    func testAbsentSecondsRenderAsNothingAndRealOnesReuseTheExistingFormatter() {
        XCTAssertNil(PlanPanelViewModel.elapsedText(nil), "no value is not 0s, and not a dash")
        XCTAssertNil(PlanPanelViewModel.elapsedText(0), "the console's costTimeSeconds > 0 guard")
        XCTAssertEqual(
            PlanPanelViewModel.elapsedText(45),
            AgentTaskLog.spelledDuration(45_000),
            "one duration dialect in this app, and it is the one the task log already uses"
        )
        XCTAssertEqual(PlanPanelViewModel.elapsedText(12), "12.0s")
        XCTAssertEqual(PlanPanelViewModel.elapsedText(PlanFixtures.subtask("读配置", seconds: nil)), nil)
        XCTAssertEqual(PlanPanelViewModel.elapsedText(PlanFixtures.subtask("读配置", seconds: 3)), "3.0s")
    }

    func testTheProgressBadgeOnlyAppearsWhenThereAreSubtasks() {
        XCTAssertNil(PlanPanelViewModel.progressText(PlanFixtures.note("p1", subtasks: [])))
        let counted = PlanFixtures.note(
            "p1",
            subtasks: [
                PlanFixtures.subtask("读配置", state: .done),
                PlanFixtures.subtask("写路由", state: .inProgress),
                PlanFixtures.subtask("跑测试", state: .todo),
            ]
        )
        XCTAssertEqual(PlanPanelViewModel.progressText(counted), "1/3")
        XCTAssertEqual(counted.progress.done, 1)
        XCTAssertEqual(counted.progress.total, 3)
    }

    // MARK: - scene

    func testParkingForTheBackgroundSpendsNoRequestsAndComingBackReArms() async {
        let (vm, reading) = await seeded(history: [], current: PlanFixtures.note("p2"), pollInterval: .milliseconds(20))
        let seen = reading.currentRequests.count
        vm.pausePolling()
        XCTAssertFalse(vm.isPolling)
        try? await Task.sleep(for: .milliseconds(80))
        XCTAssertEqual(reading.currentRequests.count, seen, "a background panel is nobody watching")
        vm.resumePolling()
        XCTAssertTrue(vm.isPolling)
        vm.stopPolling()
    }

    override func tearDown() {
        HarnaxCatalog.shared.language = .system
        super.tearDown()
    }
}

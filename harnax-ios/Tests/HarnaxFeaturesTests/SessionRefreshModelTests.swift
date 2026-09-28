import XCTest
import HarnaxCore
import HarnaxKit
import HarnaxFeatures

/// The panel is shared by three domains, so its behaviour belongs to the panel — not to whichever list
/// happened to open it. These tests drive `SessionRefreshModel` directly.
@MainActor
final class SessionRefreshModelTests: XCTestCase {
    private func sessions(_ ids: [String]) throws -> [RelatedSession] {
        try PageStub.list([RelatedSession].self, ids.map {
            ["sessionId": $0, "sourceType": "session", "sourceName": "会话 \($0)"]
        })
    }

    private func outcomes(_ lines: [[String: Any]]) throws -> [SessionRefreshOutcome] {
        try PageStub.list([SessionRefreshOutcome].self, lines)
    }

    private func model(
        _ sessions: [RelatedSession],
        reply: Result<[SessionRefreshOutcome], APIError> = .success([])
    ) -> (SessionRefreshModel, FakeRefresher) {
        let refresher = FakeRefresher()
        refresher.reply = reply
        let target = SessionRefreshTarget(id: 7, name: "翻译助手", source: .agent) { .success(sessions) }
        return (SessionRefreshModel(target: target, refresher: refresher), refresher)
    }

    func testEverySessionArrivesCheckedBecauseStaleSnapshotsAreTheDefault() async throws {
        let (vm, _) = model(try sessions(["s-1", "s-2", "s-3"]))
        await vm.load()
        XCTAssertEqual(vm.phase, .ready)
        XCTAssertEqual(vm.selectedCount, 3)
        XCTAssertEqual(vm.primaryLabel, hx("session.submit", 3))
        XCTAssertEqual(vm.subjectName, "翻译助手")
        XCTAssertEqual(vm.hintKey, "session.hint.agent")
    }

    func testAnEmptyListIsNotRenderedAsAFailure() async throws {
        let (vm, _) = model([])
        await vm.load()
        XCTAssertEqual(vm.phase, .empty)
        XCTAssertNil(vm.summaryText)
    }

    func testAFailedReadSaysTheOppositeOfAnEmptyList() async throws {
        let refresher = FakeRefresher()
        let target = SessionRefreshTarget(id: 7, name: "翻译助手", source: .cli) { .failure(.offline) }
        let vm = SessionRefreshModel(target: target, refresher: refresher)
        await vm.load()
        XCTAssertEqual(vm.phase, .failed(ErrorMessage.text(for: .offline)))
        XCTAssertEqual(vm.hintKey, "session.hint.cli")
    }

    func testUntickingEverythingMakesThePrimaryActionASkipWithNoRequest() async throws {
        let (vm, refresher) = model(try sessions(["s-1", "s-2"]))
        await vm.load()
        for row in vm.rows { vm.toggle(row) }
        XCTAssertEqual(vm.selectedCount, 0)
        XCTAssertEqual(vm.primaryLabel, hx("session.skip"))
        await vm.submit()
        XCTAssertTrue(refresher.batches.isEmpty, "a skip cannot put zero sessions on the wire")
        XCTAssertTrue(vm.isFinished)
    }

    func testTheBatchIsSentInRowOrderNotSetOrder() async throws {
        let (vm, refresher) = model(try sessions(["s-1", "s-2", "s-3"]))
        await vm.load()
        if let second = vm.rows.first(where: { $0.id == "s-2" }) { vm.toggle(second) }
        await vm.submit()
        XCTAssertEqual(refresher.batches, [["s-1", "s-3"]])
    }

    func testACleanBatchClosesThePanelWithOneCount() async throws {
        let (vm, _) = model(
            try sessions(["s-1", "s-2"]),
            reply: .success(try outcomes([
                ["sessionId": "s-1", "success": true],
                ["sessionId": "s-2", "success": true],
            ]))
        )
        await vm.load()
        await vm.submit()
        XCTAssertTrue(vm.isFinished)
        XCTAssertEqual(vm.summaryText, hx("session.outcome.all", 2))
        XCTAssertEqual(vm.failedCount, 0)
    }

    func testAPartialBatchStaysOnScreenAndKeepsEachRowItsOwnReason() async throws {
        let (vm, _) = model(
            try sessions(["s-1", "s-2"]),
            reply: .success(try outcomes([
                ["sessionId": "s-1", "success": true],
                ["sessionId": "s-2", "success": false, "error": "session is running"],
            ]))
        )
        await vm.load()
        await vm.submit()
        XCTAssertFalse(vm.isFinished, "a refused session has not taken the new configuration yet")
        XCTAssertEqual(vm.failedCount, 1)
        XCTAssertEqual(vm.succeededCount, 1)
        XCTAssertEqual(vm.summaryText, hx("session.outcome.partial", 1, 1))
        let refused = try XCTUnwrap(vm.rows.first { $0.id == "s-2" })
        XCTAssertEqual(vm.outcome(for: refused)?.reason, "session is running")
    }

    func testRowsLockOnceTheVerdictsAreIn() async throws {
        let (vm, _) = model(
            try sessions(["s-1"]),
            reply: .success(try outcomes([["sessionId": "s-1", "success": false, "error": "busy"]]))
        )
        await vm.load()
        await vm.submit()
        vm.toggle(vm.rows[0])
        XCTAssertEqual(vm.selectedCount, 1, "ticking a verdict line again would hide the reason")
    }

    func testACallThatNeverLandsReportsItsOwnFailureNotPerRowVerdicts() async throws {
        let (vm, _) = model(try sessions(["s-1"]), reply: .failure(.offline))
        await vm.load()
        await vm.submit()
        XCTAssertEqual(vm.submitError, ErrorMessage.text(for: .offline))
        XCTAssertNil(vm.summaryText)
        XCTAssertFalse(vm.isFinished)
    }
}

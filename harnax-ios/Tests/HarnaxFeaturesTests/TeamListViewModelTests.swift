import XCTest
import HarnaxCore
import HarnaxKit
import HarnaxFeatures

@MainActor
final class TeamListViewModelTests: XCTestCase {
    private func page(_ records: [[String: Any]], total: Int? = nil) throws -> Page<TeamSummary> {
        try PageStub.page(TeamSummary.self, records, total: total)
    }

    private func sessions(_ ids: [String]) throws -> [RelatedSession] {
        try PageStub.list([RelatedSession].self, ids.map { ["sessionId": $0, "sourceType": "session", "sourceName": $0] })
    }

    private func seededTeams(_ count: Int, total: Int? = nil) throws -> Page<TeamSummary> {
        try page((1...count).map { team($0, "团队 \($0)") }, total: total)
    }

    /// `TeamResponse.kt` gives these four keys non-null defaults, so they are never absent on the wire and
    /// the modelled properties are non-optional.
    private func team(_ id: Int?, _ name: String) -> [String: Any] {
        var row: [String: Any] = [
            "name": name,
            "systemPrompt": "",
            "modelId": 0,
            "skillList": [[String: Any]](),
            "memberList": [[String: Any]](),
        ]
        if let id { row["id"] = id }
        return row
    }

    private func fresh(_ teams: FakeTeams = FakeTeams()) async throws -> (TeamListViewModel, FakeTeams) {
        teams.replies = [.success(try seededTeams(2, total: 2))]
        let vm = TeamListViewModel(teams: teams)
        await vm.refresh()
        return (vm, teams)
    }

    func testTheFirstPageBecomesRowsNotASpinner() async throws {
        let (vm, teams) = try await fresh()
        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertEqual(vm.total, 2)
        XCTAssertEqual(teams.requests.map(\.num), [1])
    }

    func testAnEmptyAccountIsNotASpinningWheel() async throws {
        let teams = FakeTeams()
        teams.replies = [.success(try page([], total: 0))]
        let vm = TeamListViewModel(teams: teams)
        await vm.refresh()
        XCTAssertEqual(vm.phase, .empty)
        XCTAssertTrue(vm.items.isEmpty)
    }

    func testTheStatusFilterIsSentAsTheZeroOneColumn() async throws {
        let (vm, teams) = try await fresh()
        teams.replies = [.success(try seededTeams(1, total: 1))]
        vm.filter = .disabled
        // The didSet spawns its own task; wait for the reply it consumed.
        try await waitUntil { teams.filters.count == 2 }
        XCTAssertEqual(teams.filters.last?.status, 0)
        vm.filter = .all
        try await waitUntil { teams.filters.count == 3 }
        XCTAssertNil(teams.filters.last?.status)
    }

    func testAFailedPreflightReportsTheFailureAndOpensNoDialog() async throws {
        let (vm, teams) = try await fresh()
        teams.relatedReply = .failure(.offline)
        await vm.requestDelete(vm.items[0])
        XCTAssertNil(vm.deleteTarget, "an unreadable list cannot claim the delete is safe")
        XCTAssertEqual(vm.inlineError, hx("team.delete.loadFailed"))
        XCTAssertTrue(teams.deleteCalls.isEmpty, "the delete must not fire on an unanswered pre-flight")
    }

    func testAnEmptyRelatedListOpensTheDialogWithTheFreeToDeleteCount() async throws {
        let (vm, teams) = try await fresh()
        teams.relatedReply = .success([])
        await vm.requestDelete(vm.items[0])
        XCTAssertEqual(vm.deleteTarget?.boundSessions, 0)
    }

    func testTheCountOfStillBoundSessionsRidesIntoTheDialog() async throws {
        let (vm, teams) = try await fresh()
        teams.relatedReply = .success(try sessions(["s-1", "s-2", "s-3"]))
        await vm.requestDelete(vm.items[0])
        XCTAssertEqual(vm.deleteTarget?.boundSessions, 3)
        XCTAssertEqual(teams.relatedRequests, [1])
    }

    func testConfirmingDeletesTheRowThatWasCheckedAndDropsItFromTheList() async throws {
        let (vm, teams) = try await fresh()
        teams.relatedReply = .success([])
        let target = vm.items[1]
        await vm.requestDelete(target)
        await vm.confirmDelete()
        XCTAssertEqual(teams.deleteCalls, [2])
        XCTAssertEqual(vm.items.count, 1)
        XCTAssertEqual(vm.total, 1, "the counter follows the row out or the next page looks short")
        XCTAssertNil(vm.deleteTarget)
    }

    func testARefusedDeleteKeepsTheRowAndShowsTheServersOwnSentence() async throws {
        let (vm, teams) = try await fresh()
        teams.relatedReply = .success(try sessions(["s-1"]))
        teams.deleteReplies = [.failure(.business(code: 409, message: "2 session(s) still bind team 2"))]
        await vm.requestDelete(vm.items[1])
        await vm.confirmDelete()
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertEqual(vm.inlineError, ErrorMessage.text(for: .business(code: 409, message: "2 session(s) still bind team 2")))
    }

    func testCancellingTheDialogDeletesNothing() async throws {
        let (vm, teams) = try await fresh()
        teams.relatedReply = .success(try sessions(["s-1"]))
        await vm.requestDelete(vm.items[0])
        vm.cancelDelete()
        XCTAssertNil(vm.deleteTarget)
        XCTAssertTrue(teams.deleteCalls.isEmpty)
    }

    func testTheSwitchAnswersBeforeTheServerAndSnapsBackWhenRefused() async throws {
        let (vm, teams) = try await fresh()
        teams.statusReplies = [.failure(.offline)]
        let team = vm.items[0]
        await vm.setStatus(false, for: team)
        XCTAssertEqual(vm.status(of: team), true, "a refused write returns to what the stack last said")
        XCTAssertEqual(teams.statusCalls.map(\.id), [1])
        XCTAssertEqual(teams.statusCalls.map(\.enabled), [false])
        XCTAssertEqual(vm.inlineError, ErrorMessage.text(for: .offline))
    }

    func testARowWithNoIdCannotBeWrittenSoNothingIsSent() async throws {
        let (vm, teams) = try await fresh()
        let nameless = try TeamSummary.stub(team(nil, "无 id 团队"))
        await vm.setStatus(false, for: nameless)
        await vm.requestDelete(nameless)
        XCTAssertTrue(teams.statusCalls.isEmpty)
        XCTAssertTrue(teams.relatedRequests.isEmpty)
        XCTAssertNil(vm.refreshTarget(for: nameless))
    }

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }
}

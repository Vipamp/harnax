import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// Everything worth guarding on this screen lives in the order a write and its answer interleave: the
/// switch answers before the stack does, the rename answers after, a delete only leaves when the envelope
/// says so, and a clear reports the runtime's own sentence instead of a local success string
/// (`harnax-webui/src/pages/session/index.tsx:180-232`, `components/ChatWindow.tsx:1000-1020`).
@MainActor
final class SessionListViewModelTests: XCTestCase {
    private func rowJSON(_ title: String, id: Any?, businessKey: String?) -> [String: Any] {
        // The two binding lists always arrive (`SessionResponse.kt:52-54` defaults them to empty), which is
        // why they are non-optional here and must be spelled out in a stub row.
        var json: [String: Any] = ["title": title, "status": 1, "mcpList": [], "skillList": []]
        if let id { json["id"] = id }
        if let businessKey { json["sessionId"] = businessKey }
        return json
    }

    private func seeded(
        _ count: Int,
        total: Int? = nil,
        pageNum: Int = 1,
        firstID: Int = 1,
        rowKey: Bool = true,
        businessKey: Bool = true
    ) throws -> Page<SessionSummary> {
        try PageStub.page(
            SessionSummary.self,
            (0..<count).map { index in
                let id = firstID + index
                let key: Any? = rowKey ? id : nil
                return rowJSON(
                    "会话 \(id)",
                    id: key,
                    businessKey: businessKey ? "web-\(id)" : nil
                )
            },
            pageNum: pageNum,
            total: total ?? count
        )
    }

    private func fresh() async throws -> (SessionListViewModel, FakeSessions) {
        let sessions = FakeSessions()
        sessions.replies = [.success(try seeded(2))]
        let vm = SessionListViewModel(sessions: sessions)
        await vm.refresh()
        return (vm, sessions)
    }

    // MARK: - the list itself

    func testTheFirstPageBecomesRowsNotASpinner() async throws {
        let (vm, sessions) = try await fresh()
        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertEqual(vm.total, 2)
        XCTAssertEqual(sessions.requests.map(\.num), [1])
        XCTAssertEqual(sessions.requests.map(\.size), [20])
        XCTAssertFalse(vm.isFiltered)
    }

    func testAnAccountWithNoConversationsIsNotASpinningWheel() async throws {
        let sessions = FakeSessions()
        sessions.replies = [.success(try PageStub.page(SessionSummary.self, [], total: 0))]
        let vm = SessionListViewModel(sessions: sessions)
        await vm.refresh()
        XCTAssertEqual(vm.phase, .empty)
        XCTAssertFalse(vm.isFiltered, "the empty card may then promise there is nothing at all")
    }

    func testNothingUnderAKeywordIsReportedAsNoMatchesNotAsAnEmptyAccount() async throws {
        let sessions = FakeSessions()
        let vm = SessionListViewModel(sessions: sessions)
        vm.keyword = "找不到的词"
        sessions.replies = [.success(try PageStub.page(SessionSummary.self, [], total: 0))]
        await vm.refresh()
        XCTAssertEqual(vm.phase, .empty)
        XCTAssertTrue(vm.isFiltered, "…but not while a filter is on screen")
    }

    func testTheStatusFilterIsSentAsTheZeroOneColumn() async throws {
        let (vm, sessions) = try await fresh()
        sessions.replies = [.success(try seeded(1)), .success(try seeded(2))]
        vm.filter = .disabled
        // The didSet spawns its own task; wait for the request it sends.
        try await waitUntil { sessions.requests.count == 2 }
        XCTAssertEqual(sessions.requests.last?.status, 0)
        vm.filter = .all
        try await waitUntil { sessions.requests.count == 3 }
        XCTAssertNil(sessions.requests.last?.status)
    }

    func testAStoppedTypingKeywordIsOneRequestNotOnePerKeystroke() async throws {
        let (vm, sessions) = try await fresh()
        sessions.replies = [.success(try seeded(1, total: 1))]
        vm.keyword = "会"
        vm.keyword = "会话"
        vm.keyword = "会话 2"
        try await waitUntil { sessions.requests.count == 2 }
        // Past the 500 ms window the console waits by, the two superseded tasks have been cancelled.
        try await Task.sleep(for: .milliseconds(700))
        XCTAssertEqual(sessions.requests.count, 2, "the last keyword wins and the earlier two never go out")
        XCTAssertEqual(sessions.requests.last?.keyword, "会话 2")
    }

    func testAFailedFirstLoadBecomesAnErrorStateNotAnEmptyCard() async throws {
        let sessions = FakeSessions()
        sessions.replies = [.failure(.offline)]
        let vm = SessionListViewModel(sessions: sessions)
        await vm.refresh()
        XCTAssertEqual(vm.phase, .failed(hx("error.offline")))
    }

    func testAFailedRefreshKeepsTheRowsAndRaisesABanner() async throws {
        let (vm, sessions) = try await fresh()
        sessions.replies = [.failure(.timeout)]
        await vm.refresh()
        XCTAssertEqual(vm.phase, .content, "a refresh that failed must not blank the list")
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertEqual(vm.inlineError, hx("error.timeout"))
    }

    func testTheNextSuccessfulRefreshWipesTheBanner() async throws {
        let (vm, sessions) = try await fresh()
        sessions.replies = [.failure(.timeout)]
        await vm.refresh()
        XCTAssertNotNil(vm.inlineError)
        sessions.replies = [.success(try seeded(2))]
        await vm.refresh()
        XCTAssertNil(vm.inlineError)
    }

    func testTheSecondPageIsAppendedWhenTheFirstReportsMoreRowsThanItCarried() async throws {
        let sessions = FakeSessions()
        sessions.replies = [.success(try seeded(2, total: 3))]
        let vm = SessionListViewModel(sessions: sessions)
        await vm.refresh()
        XCTAssertTrue(vm.canLoadMore)
        sessions.replies = [.success(try seeded(1, total: 3, pageNum: 2, firstID: 3))]
        await vm.loadMore()
        XCTAssertEqual(vm.items.count, 3)
        XCTAssertEqual(sessions.requests.map(\.num), [1, 2])
        XCTAssertFalse(vm.canLoadMore, "the counter says the list is exhausted, so no third request goes out")
    }

    func testAFailedPageKeepsTheCounterSoTheNextScrollRetriesTheSamePage() async throws {
        let sessions = FakeSessions()
        sessions.replies = [.success(try seeded(2, total: 5))]
        let vm = SessionListViewModel(sessions: sessions)
        await vm.refresh()
        sessions.replies = [.failure(.offline)]
        await vm.loadMore()
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertEqual(vm.inlineError, hx("error.offline"))
        sessions.replies = [.success(try seeded(1, total: 5, pageNum: 2, firstID: 3))]
        await vm.loadMore()
        XCTAssertEqual(sessions.requests.map(\.num), [1, 2, 2])
        XCTAssertEqual(vm.items.count, 3)
        XCTAssertNil(vm.inlineError, "the page that failed is not still being reported")
    }

    // MARK: - what a row may be asked to do

    func testARowTheStackNeverGaveAKeyToTakesNoWriteAtAll() async throws {
        let sessions = FakeSessions()
        sessions.replies = [.success(try seeded(1, rowKey: false))]
        let vm = SessionListViewModel(sessions: sessions)
        await vm.refresh()
        let keyless = try XCTUnwrap(vm.items.first)
        XCTAssertNil(keyless.id)
        XCTAssertFalse(vm.canWrite(keyless), "every write here is addressed by the numeric primary key")
        await vm.setStatus(false, for: keyless)
        await vm.delete(keyless)
        XCTAssertTrue(sessions.statusRequests.isEmpty)
        XCTAssertTrue(sessions.deleteRequests.isEmpty)
        XCTAssertTrue(vm.pendingIDs.isEmpty)
    }

    func testTheSwitchFlipsTheRowBeforeTheStackAnswers() async throws {
        let (vm, sessions) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        let id = try XCTUnwrap(row.id)
        sessions.gateWrites = true
        sessions.statusReplies = [.success(EmptyResponse())]
        let write = Task { await vm.setStatus(false, for: row) }
        try await waitUntil { sessions.statusRequests.count == 1 }
        XCTAssertFalse(vm.status(of: row), "a tap has to look decided on a slow link, not inert")
        XCTAssertEqual(vm.pendingIDs, [id])
        sessions.releaseWrites()
        await write.value
        XCTAssertFalse(vm.status(of: row))
        XCTAssertTrue(vm.pendingIDs.isEmpty)
        XCTAssertNil(vm.inlineError)
    }

    func testARefusedSwitchSnapsTheRowBackToWhatTheStackLastSaid() async throws {
        let (vm, sessions) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        sessions.gateWrites = true
        sessions.statusReplies = [.failure(.business(code: 403, message: "无权操作该会话"))]
        let write = Task { await vm.setStatus(false, for: row) }
        try await waitUntil { sessions.statusRequests.count == 1 }
        XCTAssertFalse(vm.status(of: row))
        sessions.releaseWrites()
        await write.value
        XCTAssertTrue(vm.status(of: row), "the override is dropped, so the row reads as the page left it")
        XCTAssertEqual(vm.inlineError, "无权操作该会话", "the server's own sentence is the actionable part")
    }

    // MARK: - rename

    func testTheTitleIsTrimmedOnTheWayOut() async throws {
        let (vm, sessions) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        let id = try XCTUnwrap(row.id)
        sessions.renameReplies = [.success(EmptyResponse())]
        let outcome = await vm.rename(row, to: "  新标题  ")
        XCTAssertEqual(outcome, .saved)
        XCTAssertEqual(sessions.renameRequests.map(\.id), [id])
        XCTAssertEqual(sessions.renameRequests.map(\.title), ["新标题"])
    }

    func testABlankTitleIsRefusedWithoutARequest() async throws {
        let (vm, sessions) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        let outcome = await vm.rename(row, to: "   ")
        XCTAssertEqual(outcome, .invalid(hx("chat.rename.required")))
        XCTAssertTrue(sessions.renameRequests.isEmpty)
    }

    func testATitlePastTheColumnWidthIsRefusedBeforeTheDatabaseRaisesItsError() async throws {
        let (vm, sessions) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        // The update route runs no bean validation, so a 101-character title would fail as a column error
        // nobody can act on (`SessionController.kt:95` has no `@Validated`).
        let outcome = await vm.rename(row, to: String(repeating: "长", count: 101))
        XCTAssertEqual(outcome, .invalid(hx("chat.rename.long")))
        XCTAssertTrue(sessions.renameRequests.isEmpty)
        sessions.renameReplies = [.success(EmptyResponse())]
        let boundary = await vm.rename(row, to: String(repeating: "长", count: 100))
        XCTAssertEqual(boundary, .saved, "the limit is the column's, not a round number below it")
    }

    func testTheNewTitleIsHeldBackUntilTheStackAgrees() async throws {
        let (vm, sessions) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        sessions.gateWrites = true
        sessions.renameReplies = [.success(EmptyResponse())]
        let write = Task { await vm.rename(row, to: "改过的标题") }
        try await waitUntil { sessions.renameRequests.count == 1 }
        XCTAssertEqual(vm.title(of: row), "会话 1", "a rename is not a switch — nothing changes before the answer")
        sessions.releaseWrites()
        let outcome = await write.value
        XCTAssertEqual(outcome, .saved)
        XCTAssertEqual(vm.title(of: row), "改过的标题")
        XCTAssertTrue(vm.pendingIDs.isEmpty)
        XCTAssertNil(vm.inlineError)
        XCTAssertEqual(
            sessions.requests.count,
            1,
            "the update confirms with an empty body, so nothing re-reads the page to find out what it wrote"
        )
    }

    func testARefusedRenameLeavesTheCardSayingWhatItSaidBefore() async throws {
        let (vm, sessions) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        // A team row that names an agent, or an agent from another tenant, comes back as a message rather
        // than as a status code (`SessionServiceImpl.kt:249-251`).
        sessions.renameReplies = [.failure(.business(code: 500, message: "智能体不存在"))]
        let outcome = await vm.rename(row, to: "改过的标题")
        XCTAssertEqual(outcome, .failed("智能体不存在"))
        XCTAssertEqual(vm.title(of: row), "会话 1")
    }

    func testAReReadListIsAuthoritativeEvenOverAFreshRename() async throws {
        let (vm, sessions) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        sessions.renameReplies = [.success(EmptyResponse())]
        let outcome = await vm.rename(row, to: "改过的标题")
        XCTAssertEqual(outcome, .saved)
        XCTAssertEqual(vm.title(of: row), "改过的标题")
        sessions.replies = [.success(try seeded(2))]
        await vm.refresh()
        XCTAssertEqual(vm.title(of: row), "会话 1", "the override is a stopgap until the page itself says otherwise")
    }

    // MARK: - delete

    func testADeleteTakesTheRowOutOfTheListAndOffTheCounter() async throws {
        let (vm, sessions) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        let id = try XCTUnwrap(row.id)
        sessions.deleteReplies = [.success(EmptyResponse())]
        await vm.delete(row)
        XCTAssertEqual(sessions.deleteRequests, [id])
        XCTAssertEqual(vm.items.count, 1)
        XCTAssertEqual(vm.total, 1, "otherwise hasMore promises a page that has one fewer row in it")
        XCTAssertNil(vm.inlineError)
    }

    func testARefusedDeleteLeavesTheRowUpWithTheReason() async throws {
        let (vm, sessions) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        // The runtime can hold the delete back and the envelope names what is still in the way
        // (`SessionServiceImpl.kt:326-346`).
        sessions.deleteReplies = [.failure(.business(code: 500, message: "会话正在运行中"))]
        await vm.delete(row)
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertEqual(vm.total, 2)
        XCTAssertEqual(vm.inlineError, "会话正在运行中")
        XCTAssertEqual(vm.phase, .content)
    }

    func testADeleteLeavesTheOtherRowsSettledStateAlone() async throws {
        let (vm, sessions) = try await fresh()
        let second = vm.items[1]
        sessions.statusReplies = [.success(EmptyResponse())]
        await vm.setStatus(false, for: second)
        XCTAssertFalse(vm.status(of: second))
        sessions.deleteReplies = [.success(EmptyResponse())]
        await vm.delete(vm.items[0])
        XCTAssertEqual(vm.items.count, 1)
        XCTAssertFalse(
            vm.status(of: second),
            "a delete re-reads nothing, so the switch the stack already confirmed still shows"
        )
    }

    // MARK: - clear

    func testTheRuntimeSentenceIsShownVerbatimAfterAClear() async throws {
        let (vm, sessions) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        let sessionId = try XCTUnwrap(row.sessionId)
        sessions.clearReplies = [.success(AgentCommandReply(success: true, message: "已清空历史消息"))]
        await vm.clearMessages(row)
        XCTAssertEqual(sessions.clearRequests, [sessionId])
        XCTAssertEqual(vm.notice, "已清空历史消息")
        XCTAssertNil(vm.inlineError)
        XCTAssertEqual(vm.items.count, 2, "the row stays — only its history went")
    }

    func testAClearThatAnsweredWithNothingSaysSoRatherThanPassingAsSilence() async throws {
        let (vm, sessions) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        sessions.clearReplies = [.success(AgentCommandReply(success: true, message: nil))]
        await vm.clearMessages(row)
        XCTAssertEqual(vm.notice, hx("chat.clear.emptyReply"))
    }

    func testAClearTheRuntimeRefusedBecomesABannerNotASuccessNote() async throws {
        let (vm, sessions) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        sessions.clearReplies = [.success(AgentCommandReply(success: false, message: "会话不存在"))]
        await vm.clearMessages(row)
        XCTAssertEqual(vm.inlineError, "会话不存在")
        XCTAssertNil(vm.notice)
    }

    func testARefusalWithNoExplanatoryMessageStillGetsLocalCopy() async throws {
        let (vm, sessions) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        sessions.clearReplies = [.success(AgentCommandReply(success: false, message: "  "))]
        await vm.clearMessages(row)
        XCTAssertEqual(vm.inlineError, hx("chat.clear.refused"))
    }

    func testARowWithNoBusinessKeyIsNeverAddressedToTheRuntime() async throws {
        let (vm, sessions) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        let keyless = SessionSummary(id: row.id, title: "无键行", sessionId: "   ")
        await vm.clearMessages(keyless)
        XCTAssertTrue(sessions.clearRequests.isEmpty, "a numeric row id would address a conversation not there")
        XCTAssertNil(vm.notice)
    }

    func testTheNoticeOnlyLivesUntilTheNextRead() async throws {
        let (vm, sessions) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        sessions.clearReplies = [.success(AgentCommandReply(success: true, message: "已清空历史消息"))]
        await vm.clearMessages(row)
        XCTAssertNotNil(vm.notice)
        sessions.replies = [.success(try seeded(2))]
        await vm.refresh()
        XCTAssertNil(vm.notice, "a note about one row should not sit above a list that has since re-read")
    }

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }
}

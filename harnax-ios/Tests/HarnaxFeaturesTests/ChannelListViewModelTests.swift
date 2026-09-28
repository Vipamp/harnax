import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// E1 — the channel list. The shape is the API Key list's, with the one difference this domain forces: the
/// light on the row is not on the channel API at all. It comes from the runtime's bulk workspace lookup, keyed
/// by `sessionId`, asked for *after* each page — so the four behaviours that matter here are that it is asked
/// for only when a row has an id, that it is kept across pages, that it never blanks the list when it fails,
/// and that it is read as "unknown" rather than "stopped" (`harnax-webui/src/pages/channel/index.tsx:111-128`
/// swallows the failure outright; this side keeps the two states apart).
@MainActor
final class ChannelListViewModelTests: XCTestCase {
    /// A row as the wire makes it: the two switch columns are 0/1 integers and `configJson` is a string.
    private func row(
        _ name: String,
        id: Any? = 1,
        type: String? = "feishu",
        sessionId: String? = nil,
        status: Int? = 1
    ) -> [String: Any] {
        var json: [String: Any] = ["name": name]
        if let id { json["id"] = id }
        if let type { json["type"] = type }
        if let sessionId { json["sessionId"] = sessionId }
        if let status { json["status"] = status }
        return json
    }

    private func seeded(
        _ count: Int,
        total: Int? = nil,
        pageNum: Int = 1,
        firstID: Int = 1,
        sessionPrefix: String? = nil
    ) throws -> Page<ChannelSummary> {
        try PageStub.page(
            ChannelSummary.self,
            (0..<count).map { index in
                let id = firstID + index
                return row(
                    "渠道 \(id)",
                    id: id,
                    sessionId: sessionPrefix.map { "\($0)-\(id)" },
                    status: 1
                )
            },
            pageNum: pageNum,
            total: total ?? count
        )
    }

    private func fresh(
        sessionIDs: Bool = false,
        sandbox: [String: Bool] = [:]
    ) async throws -> (ChannelListViewModel, FakeChannels) {
        let catalog = FakeChannels()
        catalog.replies = [.success(try seeded(2, sessionPrefix: sessionIDs ? "chn" : nil))]
        if sessionIDs { catalog.sandboxReplies = [.success(SandboxStub.map(sandbox))] }
        let vm = ChannelListViewModel(catalog: catalog)
        await vm.refresh()
        return (vm, catalog)
    }

    // MARK: - the page and its second read

    func testTheFirstPageBecomesRowsNotASpinner() async throws {
        let (vm, catalog) = try await fresh()
        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertEqual(catalog.requests.map(\.num), [1], "the console addresses this page from one, not zero")
        XCTAssertEqual(catalog.requests.map(\.size), [20])
        XCTAssertFalse(vm.canLoadMore)
        XCTAssertFalse(vm.isFiltered)
        XCTAssertNil(vm.inlineError)
    }

    /// The runtime read is keyed by the row's own session address, and it goes out in one batch.
    func testTheSandboxReadAsksForEveryRowThatHasASessionId() async throws {
        let (vm, catalog) = try await fresh(sessionIDs: true)
        XCTAssertEqual(catalog.sandboxRequests, [["chn-1", "chn-2"]])
        XCTAssertEqual(vm.items.count, 2)
    }

    /// A create that has not been listened to still has a session id, but a page of rows without one must not
    /// cost a request — the server refuses a blank `sessionIds` list.
    func testAPageOfRowsWithoutSessionIdsSendsNoSandboxReadAtAll() async throws {
        let (vm, catalog) = try await fresh()
        XCTAssertTrue(catalog.sandboxRequests.isEmpty, "an empty id list is refused by the runtime")
        XCTAssertEqual(vm.sandboxStatus(of: vm.items[0]), .unknown)
    }

    func testTheSandboxAnswerColoursTheRowItBelongsTo() async throws {
        let (vm, catalog) = try await fresh(
            sessionIDs: true, sandbox: ["chn-1": true, "chn-2": false])
        XCTAssertEqual(vm.sandboxStatus(of: vm.items[0]), .running)
        XCTAssertEqual(vm.sandboxStatus(of: vm.items[1]), .idle)
        XCTAssertEqual(catalog.sandboxRequests.count, 1, "one batched read per page, not one per row")
    }

    /// "No answer" and "stopped" read very differently on a row that claims to be running, so a refused
    /// lookup is not an error state and not a red light either.
    func testAFailedSandboxReadLeavesTheRowsUpAndSaysNothing() async throws {
        let catalog = FakeChannels()
        catalog.replies = [.success(try seeded(2, sessionPrefix: "chn"))]
        catalog.sandboxReplies = [.failure(.offline)]
        let vm = ChannelListViewModel(catalog: catalog)
        await vm.refresh()
        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertNil(vm.inlineError, "the channel list itself answered fine")
        XCTAssertEqual(vm.sandboxStatus(of: vm.items[0]), .unknown)
    }

    /// An already-answered row must not flicker back to `unknown` while the next page's lookup is in flight.
    func testASandboxAnswerSurvivesTheNextPage() async throws {
        let catalog = FakeChannels()
        catalog.replies = [.success(try seeded(1, total: 2, sessionPrefix: "chn"))]
        catalog.sandboxReplies = [.success(SandboxStub.map(["chn-1": true]))]
        let vm = ChannelListViewModel(catalog: catalog)
        await vm.refresh()
        XCTAssertEqual(vm.sandboxStatus(of: vm.items[0]), .running)

        catalog.replies = [.success(try seeded(1, total: 2, pageNum: 2, firstID: 2, sessionPrefix: "chn"))]
        catalog.sandboxReplies = [.failure(.timeout)]
        await vm.loadMore()
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertEqual(vm.sandboxStatus(of: vm.items[0]), .running, "the second lookup failed; the first answer stands")
        XCTAssertEqual(vm.sandboxStatus(of: vm.items[1]), .unknown)
    }

    // MARK: - the filters

    func testTheTypeFilterSendsTheExactCodeAndTheStatusFilterTheColumn() async throws {
        let (vm, catalog) = try await fresh()
        catalog.replies = [.success(try seeded(1, total: 1))]
        vm.typeFilter = .type(.feishu)
        try await waitUntil { catalog.requests.count == 2 }
        XCTAssertEqual(catalog.requests.last?.type, "feishu", "fromCode is case-sensitive server-side")
        XCTAssertEqual(catalog.requests.last?.status, nil)

        catalog.replies = [.success(try seeded(1, total: 1))]
        vm.filter = .disabled
        try await waitUntil { catalog.requests.count == 3 }
        XCTAssertEqual(catalog.requests.last?.status, 0, "the wire takes the 0/1 column, not a name")
        XCTAssertTrue(vm.isFiltered)
    }

    /// Both menus start on `all`, and `all` is the one option that leaves its parameter off the URL rather
    /// than sending it blank.
    func testTheTwoAllOptionsLeaveTheirParametersOffTheRequest() async throws {
        let (vm, catalog) = try await fresh()
        XCTAssertNil(catalog.requests.last?.type)
        XCTAssertNil(catalog.requests.last?.status)
        XCTAssertFalse(vm.isFiltered)
        XCTAssertEqual(ChannelTypeFilter.all.code, nil)
        XCTAssertEqual(ChannelTypeFilter.type(.dingtalk).code, "dingtalk")
        XCTAssertEqual(ChannelTypeFilter.allCases.count, ChannelType.allCases.count + 1)
    }

    func testTheKeywordGoesOutAsItWasTypedAndThePageResetsToOne() async throws {
        let (vm, catalog) = try await fresh()
        catalog.replies = [.success(try seeded(1, total: 1))]
        vm.keyword = "运营 助理"
        try await waitUntil { catalog.requests.count == 2 }
        XCTAssertEqual(catalog.requests.last?.keyword, "运营 助理", "no trimming, no case folding")
        XCTAssertEqual(catalog.requests.last?.num, 1)
        XCTAssertTrue(vm.isFiltered)
    }

    func testAStoppedTypingKeywordIsOneRequestNotOnePerKeystroke() async throws {
        let (vm, catalog) = try await fresh()
        catalog.replies = [.success(try seeded(1, total: 1))]
        vm.keyword = "a"
        vm.keyword = "ab"
        vm.keyword = "abc"
        try await waitUntil { catalog.requests.count == 2 }
        try await Task.sleep(for: .milliseconds(700))
        XCTAssertEqual(catalog.requests.count, 2, "the last keyword wins and the earlier two never go out")
        XCTAssertEqual(catalog.requests.last?.keyword, "abc")
    }

    func testAFilteredEmptyPageIsNoMatchesNotAnEmptyAccount() async throws {
        let catalog = FakeChannels()
        let vm = ChannelListViewModel(catalog: catalog)
        vm.keyword = "找不到的名字"
        catalog.replies = [.success(try PageStub.page(ChannelSummary.self, [], total: 0))]
        await vm.refresh()
        XCTAssertEqual(vm.phase, .empty)
        XCTAssertTrue(vm.isFiltered)
    }

    func testAFailedFirstLoadBecomesAnErrorStateNotAnEmptyCard() async throws {
        let catalog = FakeChannels()
        catalog.replies = [.failure(.offline)]
        let vm = ChannelListViewModel(catalog: catalog)
        await vm.refresh()
        XCTAssertEqual(vm.phase, .failed(hx("error.offline")))
        XCTAssertTrue(vm.items.isEmpty)
        XCTAssertTrue(catalog.sandboxRequests.isEmpty, "there is no page to colour")
    }

    func testAFailedRefreshKeepsTheRowsAndRaisesABanner() async throws {
        let (vm, catalog) = try await fresh()
        catalog.replies = [.failure(.timeout)]
        await vm.refresh()
        XCTAssertEqual(vm.phase, .content, "a refresh that failed must not blank the list")
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertEqual(vm.inlineError, hx("error.timeout"))
    }

    // MARK: - the switch

    /// `status` is the list's switch and the toggle answers nothing, so the flag is the row's own state until
    /// the answer says otherwise — and it snaps back when the answer is a refusal.
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
        XCTAssertEqual(catalog.statusRequests.last?.running, false)
        XCTAssertEqual(vm.pendingIDs, [id])
        catalog.releaseWrites()
        await write.value
        XCTAssertFalse(vm.status(of: row))
        XCTAssertTrue(vm.pendingIDs.isEmpty)
        XCTAssertNil(vm.inlineError)
    }

    func testARefusedSwitchSnapsTheRowBackAndSaysWhy() async throws {
        let (vm, catalog) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        catalog.gateWrites = true
        catalog.statusReplies = [.failure(.business(code: 500, message: "渠道不存在"))]
        let write = Task { await vm.setStatus(false, for: row) }
        try await waitUntil { catalog.statusRequests.count == 1 }
        XCTAssertFalse(vm.status(of: row))
        catalog.releaseWrites()
        await write.value
        XCTAssertTrue(vm.status(of: row), "the override is dropped, so the row reads as the page left it")
        XCTAssertEqual(vm.inlineError, "渠道不存在")
        XCTAssertTrue(vm.pendingIDs.isEmpty)
        XCTAssertEqual(catalog.requests.count, 1, "and a refused switch is not answered by re-reading the page")
    }

    /// The override is a stopgap: a re-read page wins over it, and a delete clears it.
    func testAReReadPageIsAuthoritativeOverAFreshSwitch() async throws {
        let (vm, catalog) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        catalog.statusReplies = [.success(EmptyResponse())]
        await vm.setStatus(false, for: row)
        XCTAssertFalse(vm.status(of: row))
        catalog.replies = [.success(try seeded(2))]
        await vm.refresh()
        XCTAssertTrue(vm.status(of: row))
    }

    func testARowTheStackNeverGaveAnIdToTakesNoWriteAtAll() async throws {
        let catalog = FakeChannels()
        catalog.replies = [.success(try PageStub.page(ChannelSummary.self, [row("无名", id: nil)]))]
        let vm = ChannelListViewModel(catalog: catalog)
        await vm.refresh()
        let keyless = try XCTUnwrap(vm.items.first)
        XCTAssertNil(keyless.id)
        XCTAssertTrue(vm.status(of: keyless))
        await vm.setStatus(false, for: keyless)
        XCTAssertTrue(catalog.statusRequests.isEmpty, "the toggle is addressed by the row id")
        vm.beginDelete(keyless)
        XCTAssertNil(vm.deleteTarget, "a confirmation nobody could carry out is not a confirmation")
        await vm.confirmDelete()
        XCTAssertTrue(catalog.deleteRequests.isEmpty)
        XCTAssertEqual(vm.items.count, 1)
    }

    // MARK: - delete

    /// Deleting releases the runtime first, so the row and its sandbox light both go on a success.
    func testADeleteTakesTheRowAndItsSandboxAnswerWithIt() async throws {
        let (vm, catalog) = try await fresh(sessionIDs: true, sandbox: ["chn-1": true])
        let row = try XCTUnwrap(vm.items.first)
        XCTAssertEqual(vm.sandboxStatus(of: row), .running)

        catalog.deleteReplies = [.success(EmptyResponse())]
        vm.beginDelete(row)
        XCTAssertEqual(vm.deleteTarget, row, "the sheet opens on the caller's choice, not on the answer")
        await vm.confirmDelete()
        XCTAssertEqual(catalog.deleteRequests, [try XCTUnwrap(row.id)])
        XCTAssertNil(vm.deleteTarget)
        XCTAssertEqual(vm.items.count, 1)
        XCTAssertEqual(vm.total, 1)
        XCTAssertEqual(vm.sandboxStatus(of: row), .unknown, "its runtime answer leaves with it")
        XCTAssertNil(vm.inlineError)
    }

    /// A refused release is an ordinary outcome of this call, and the row has to stay where it is with the
    /// server's sentence attached (`ChannelServiceImpl.kt:163-169`).
    func testARefusedDeleteLeavesTheRowUpWithTheReason() async throws {
        let (vm, catalog) = try await fresh(sessionIDs: true, sandbox: ["chn-1": true])
        let row = try XCTUnwrap(vm.items.first)
        catalog.deleteReplies = [.failure(.business(code: 500, message: "会话正在使用，无法删除"))]
        vm.beginDelete(row)
        await vm.confirmDelete()
        XCTAssertEqual(vm.items.count, 2, "the server kept it, so the list keeps it")
        XCTAssertEqual(vm.inlineError, "会话正在使用，无法删除")
        XCTAssertEqual(vm.sandboxStatus(of: row), .running, "and nothing about the row's own state moved")
    }

    func testACancelledConfirmationSendsNoDelete() async throws {
        let (vm, catalog) = try await fresh()
        vm.beginDelete(try XCTUnwrap(vm.items.first))
        XCTAssertNotNil(vm.deleteTarget)
        vm.cancelDelete()
        XCTAssertNil(vm.deleteTarget)
        await vm.confirmDelete()
        XCTAssertTrue(catalog.deleteRequests.isEmpty)
        XCTAssertEqual(vm.items.count, 2)
    }

    func testADeleteLeavesTheOtherRowsSettledStateAlone() async throws {
        let (vm, catalog) = try await fresh()
        let second = vm.items[1]
        catalog.statusReplies = [.success(EmptyResponse())]
        await vm.setStatus(false, for: second)
        XCTAssertFalse(vm.status(of: second))
        catalog.deleteReplies = [.success(EmptyResponse())]
        vm.beginDelete(try XCTUnwrap(vm.items.first))
        await vm.confirmDelete()
        XCTAssertEqual(vm.items.count, 1)
        XCTAssertFalse(vm.status(of: second), "a delete re-reads nothing")
    }

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }
}

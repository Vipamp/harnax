import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// S3's list screen, in the order its writes and their answers interleave: the switch answers before the
/// stack does and snaps back when the stack refuses, a delete only leaves when the envelope says so, and a
/// refresh that fails over rows keeps the rows and raises a banner instead of blanking the page
/// (`harnax-webui/src/pages/env-variable/index.tsx:140-248`, `EnvVariableServiceImpl.kt:180-219`).
///
/// The counter is the stack's, not the array's (`EnvVariableResponse` carries no total of its own — the page
/// envelope does), so every paging assertion reads `vm.total` rather than `vm.items.count`.
@MainActor
final class EnvVarListViewModelTests: XCTestCase {
    /// A row as the wire makes it: the two flag columns are 0/1 integers, and a sensitive row's value is
    /// already the mask the service computed (`EnvVariableServiceImpl.kt:221-243`).
    private func row(
        _ key: String,
        id: Any? = 1,
        value: String? = "v",
        sensitive: Bool = false,
        enabled: Bool = true
    ) -> [String: Any] {
        var json: [String: Any] = [
            "envKey": key,
            "sensitive": sensitive ? 1 : 0,
            "enabled": enabled ? 1 : 0,
        ]
        if let value { json["envValue"] = value }
        if let id { json["id"] = id }
        return json
    }

    private func seeded(
        _ count: Int,
        total: Int? = nil,
        pageNum: Int = 1,
        firstID: Int = 1,
        rowKey: Bool = true,
        enabled: Bool = true
    ) throws -> Page<EnvVarSummary> {
        try PageStub.page(
            EnvVarSummary.self,
            (0..<count).map { index in
                let id = firstID + index
                return row("KEY_\(id)", id: rowKey ? id : nil, value: "值 \(id)", enabled: enabled)
            },
            pageNum: pageNum,
            total: total ?? count
        )
    }

    private func empty() throws -> Page<EnvVarSummary> {
        try PageStub.page(EnvVarSummary.self, [], total: 0)
    }

    private func fresh() async throws -> (EnvVarListViewModel, FakeEnvVars) {
        let catalog = FakeEnvVars()
        catalog.replies = [.success(try seeded(2))]
        let vm = EnvVarListViewModel(catalog: catalog)
        await vm.refresh()
        return (vm, catalog)
    }

    // MARK: - the list itself

    func testTheFirstPageBecomesRowsNotASpinner() async throws {
        let (vm, catalog) = try await fresh()
        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertEqual(catalog.requests.map(\.num), [1], "the console addresses this page from one, not zero")
        XCTAssertEqual(catalog.requests.map(\.size), [20])
        XCTAssertFalse(vm.canLoadMore, "2 of 2 held, so there is no second page to ask for")
        XCTAssertFalse(vm.isFiltered)
        XCTAssertNil(vm.inlineError)
    }

    func testTheCounterComesFromTheStackNotFromTheRowsOnScreen() async throws {
        let catalog = FakeEnvVars()
        catalog.replies = [.success(try seeded(2, total: 5))]
        let vm = EnvVarListViewModel(catalog: catalog)
        await vm.refresh()
        XCTAssertEqual(vm.total, 5)
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertTrue(vm.canLoadMore, "reading the counter off the array would stop the list at two rows")
    }

    func testAnAccountThatTypedNoVariablesIsNotASpinningWheel() async throws {
        let catalog = FakeEnvVars()
        catalog.replies = [.success(try empty())]
        let vm = EnvVarListViewModel(catalog: catalog)
        await vm.refresh()
        XCTAssertEqual(vm.phase, .empty)
        XCTAssertFalse(vm.isFiltered, "the empty card may then promise there is nothing at all")
    }

    func testNothingUnderAKeywordIsNoMatchesNotAnEmptyAccount() async throws {
        let catalog = FakeEnvVars()
        let vm = EnvVarListViewModel(catalog: catalog)
        vm.keyword = "找不到的词"
        catalog.replies = [.success(try empty())]
        await vm.refresh()
        XCTAssertEqual(vm.phase, .empty)
        XCTAssertTrue(vm.isFiltered, "…but the card has to say “no matches”, not “you have none”")
    }

    func testASearchOfOnlySpacesIsNotAFilterTheUserMeant() async throws {
        let (vm, _) = try await fresh()
        vm.keyword = "   "
        XCTAssertFalse(vm.isFiltered, "otherwise a list of rows would be read back as “no matches”")
    }

    func testAFailedFirstLoadBecomesAnErrorStateNotAnEmptyCard() async throws {
        let catalog = FakeEnvVars()
        catalog.replies = [.failure(.offline)]
        let vm = EnvVarListViewModel(catalog: catalog)
        await vm.refresh()
        XCTAssertEqual(vm.phase, .failed(hx("error.offline")))
        XCTAssertTrue(vm.items.isEmpty)
        XCTAssertNil(vm.inlineError, "there is nothing on screen for a banner to sit above")
    }

    func testAFailedRefreshKeepsTheRowsAndRaisesABanner() async throws {
        let (vm, catalog) = try await fresh()
        catalog.replies = [.failure(.timeout)]
        await vm.refresh()
        XCTAssertEqual(vm.phase, .content, "a refresh that failed must not blank the list")
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertEqual(vm.inlineError, hx("error.timeout"))
    }

    func testTheNextSuccessfulRefreshWipesTheBanner() async throws {
        let (vm, catalog) = try await fresh()
        catalog.replies = [.failure(.timeout)]
        await vm.refresh()
        XCTAssertNotNil(vm.inlineError)
        catalog.replies = [.success(try seeded(2))]
        await vm.refresh()
        XCTAssertNil(vm.inlineError)
    }

    func testTheKeywordGoesOutAsItWasTypedAndThePageResetsToOne() async throws {
        let (vm, catalog) = try await fresh()
        catalog.replies = [.success(try seeded(1, total: 1))]
        vm.keyword = "OPENAI API"
        // The didSet spawns its own task; wait for the request it sends.
        try await waitUntil { catalog.requests.count == 2 }
        XCTAssertEqual(catalog.requests.last?.keyword, "OPENAI API", "no trimming, no case folding")
        XCTAssertEqual(catalog.requests.last?.num, 1, "a search that kept the page counter would land mid-list")
        XCTAssertTrue(vm.isFiltered)
    }

    func testAStoppedTypingKeywordIsOneRequestNotOnePerKeystroke() async throws {
        let (vm, catalog) = try await fresh()
        catalog.replies = [.success(try seeded(1, total: 1))]
        vm.keyword = "KEY"
        vm.keyword = "KEY_1"
        vm.keyword = "KEY_12"
        try await waitUntil { catalog.requests.count == 2 }
        // Past the 500 ms window the console waits by, the two superseded tasks have been cancelled.
        try await Task.sleep(for: .milliseconds(700))
        XCTAssertEqual(catalog.requests.count, 2, "the last keyword wins and the earlier two never go out")
        XCTAssertEqual(catalog.requests.last?.keyword, "KEY_12")
    }

    func testTheSecondPageIsAppendedWhenTheFirstReportsMoreRowsThanItCarried() async throws {
        let catalog = FakeEnvVars()
        catalog.replies = [.success(try seeded(2, total: 3))]
        let vm = EnvVarListViewModel(catalog: catalog)
        await vm.refresh()
        XCTAssertTrue(vm.canLoadMore)
        catalog.replies = [.success(try seeded(1, total: 3, pageNum: 2, firstID: 3))]
        await vm.loadMore()
        XCTAssertEqual(vm.items.count, 3)
        XCTAssertEqual(vm.total, 3)
        XCTAssertEqual(catalog.requests.map(\.num), [1, 2])
        XCTAssertFalse(vm.canLoadMore, "the counter says the list is exhausted, so no third request goes out")
        catalog.replies = [.success(try seeded(1, total: 3, pageNum: 3, firstID: 4))]
        await vm.loadMore()
        XCTAssertEqual(catalog.requests.count, 2, "and a scroll past the end asks for nothing at all")
    }

    func testAFailedPageKeepsTheCounterSoTheNextScrollRetriesTheSamePage() async throws {
        let catalog = FakeEnvVars()
        catalog.replies = [.success(try seeded(2, total: 5))]
        let vm = EnvVarListViewModel(catalog: catalog)
        await vm.refresh()
        catalog.replies = [.failure(.offline)]
        await vm.loadMore()
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertEqual(vm.inlineError, hx("error.offline"))
        catalog.replies = [.success(try seeded(1, total: 5, pageNum: 2, firstID: 3))]
        await vm.loadMore()
        XCTAssertEqual(catalog.requests.map(\.num), [1, 2, 2])
        XCTAssertEqual(vm.items.count, 3)
        XCTAssertNil(vm.inlineError, "the page that failed is not still being reported")
    }

    // MARK: - what a row may be asked to do

    func testARowTheStackNeverGaveAKeyToTakesNoWriteAtAll() async throws {
        let catalog = FakeEnvVars()
        catalog.replies = [.success(try seeded(1, rowKey: false))]
        let vm = EnvVarListViewModel(catalog: catalog)
        await vm.refresh()
        let keyless = try XCTUnwrap(vm.items.first)
        XCTAssertNil(keyless.id)
        XCTAssertTrue(vm.status(of: keyless))
        await vm.setStatus(false, for: keyless)
        XCTAssertTrue(catalog.statusRequests.isEmpty, "the toggle is addressed by the numeric primary key")
        XCTAssertTrue(vm.pendingIDs.isEmpty)
        XCTAssertTrue(vm.status(of: keyless), "…and with no key there is not even a local override to show")
        vm.beginDelete(keyless)
        XCTAssertNil(vm.deleteTarget, "a confirmation nobody could carry out is not a confirmation")
        await vm.confirmDelete()
        XCTAssertTrue(catalog.deleteRequests.isEmpty)
        XCTAssertEqual(vm.items.count, 1)
    }

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
        XCTAssertNil(vm.inlineError)
    }

    func testARefusedSwitchSnapsTheRowBackToWhatTheStackLastSaid() async throws {
        let (vm, catalog) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        // Switching *off* is the branch that gets refused: an agent still binds the variable
        // (`EnvVariableServiceImpl.kt:210-219`).
        catalog.gateWrites = true
        catalog.statusReplies = [.failure(.business(code: 500, message: "该变量被智能体「翻译助手」使用，无法停用"))]
        let write = Task { await vm.setStatus(false, for: row) }
        try await waitUntil { catalog.statusRequests.count == 1 }
        XCTAssertFalse(vm.status(of: row))
        catalog.releaseWrites()
        await write.value
        XCTAssertTrue(vm.status(of: row), "the override is dropped, so the row reads as the page left it")
        XCTAssertEqual(vm.inlineError, "该变量被智能体「翻译助手」使用，无法停用", "the server's own sentence names the blocker")
        XCTAssertTrue(vm.pendingIDs.isEmpty)
        XCTAssertEqual(vm.total, 2, "a refused switch changes nothing about the list itself")
        XCTAssertEqual(catalog.requests.count, 1, "and it is not answered by re-reading the page to find out")
    }

    func testAReReadListIsAuthoritativeOverAFreshSwitch() async throws {
        let (vm, catalog) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        catalog.statusReplies = [.success(EmptyResponse())]
        await vm.setStatus(false, for: row)
        XCTAssertFalse(vm.status(of: row))
        catalog.replies = [.success(try seeded(2, enabled: true))]
        await vm.refresh()
        XCTAssertTrue(vm.status(of: row), "the override is a stopgap until the page itself says otherwise")
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
        XCTAssertNil(vm.deleteTarget, "…and closes on the answer")
        XCTAssertEqual(vm.items.count, 1)
        XCTAssertEqual(vm.total, 1, "otherwise hasMore promises a page that has one fewer row in it")
        XCTAssertNil(vm.inlineError)
    }

    func testTheLastRowDeletedLeavesTheEmptyCardNotASpinner() async throws {
        let catalog = FakeEnvVars()
        catalog.replies = [.success(try seeded(1, total: 1))]
        let vm = EnvVarListViewModel(catalog: catalog)
        await vm.refresh()
        catalog.deleteReplies = [.success(EmptyResponse())]
        vm.beginDelete(try XCTUnwrap(vm.items.first))
        await vm.confirmDelete()
        XCTAssertEqual(vm.items.count, 0)
        XCTAssertEqual(vm.total, 0)
        XCTAssertEqual(vm.phase, .empty)
        XCTAssertFalse(vm.canLoadMore)
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

    func testARefusedDeleteLeavesTheRowUpWithTheReason() async throws {
        let (vm, catalog) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        // A variable an agent still binds is protected exactly like a running session
        // (`EnvVariableServiceImpl.kt:180-208`).
        catalog.deleteReplies = [.failure(.business(code: 500, message: "该变量被 2 个智能体使用，无法删除"))]
        vm.beginDelete(row)
        await vm.confirmDelete()
        XCTAssertEqual(vm.items.count, 2, "the server kept it, so the list keeps it")
        XCTAssertEqual(vm.total, 2)
        XCTAssertEqual(vm.inlineError, "该变量被 2 个智能体使用，无法删除")
        XCTAssertEqual(vm.phase, .content)
        XCTAssertNil(vm.deleteTarget)
    }

    func testANewConfirmationOpensUnderNoStaleBanner() async throws {
        let (vm, catalog) = try await fresh()
        catalog.deleteReplies = [.failure(.business(code: 500, message: "该变量被智能体使用，无法删除"))]
        vm.beginDelete(try XCTUnwrap(vm.items.first))
        await vm.confirmDelete()
        XCTAssertNotNil(vm.inlineError)
        vm.beginDelete(vm.items[1])
        XCTAssertNil(vm.inlineError, "the second sheet must not open under the first refusal")
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
        XCTAssertFalse(
            vm.status(of: second),
            "a delete re-reads nothing, so the switch the stack already confirmed still shows"
        )
    }

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }
}

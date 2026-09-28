import XCTest
import HarnaxCore
import HarnaxKit
import HarnaxFeatures

/// The four writes, the two filters and one secret that exists for exactly one screen.
///
/// What is worth guarding here is the order each write takes its answer in: the switch decides before the
/// stack speaks and snaps back to the page when refused, a delete leaves only when the envelope says so, a
/// rotate re-reads the first page because the row's own `keyPrefix` changed under it, and `publishedKey`
/// is filled by the rotate route alone — no read, refresh, append or refusal puts a raw key on screen
/// (`harnax-ios/specs/03-system-domain.md:124`, `ApiKeyServiceImpl.kt:80-82`).
@MainActor
final class ApiKeyListViewModelTests: XCTestCase {
    /// Every column on `ApiKeyResponse.kt:8-41` is nullable, so a stub row spells only what the test needs
    /// and lets the decode leave the rest absent.
    private func rowJSON(
        _ name: String,
        id: Any?,
        enabled: Int = 1,
        prefix: String = "hnx_sk_live_ab...9f3c"
    ) -> [String: Any] {
        var json: [String: Any] = [
            "name": name,
            "keyPrefix": prefix,
            "scopes": "chat",
            "rateLimit": 60,
            "enabled": enabled,
            "creator": "admin",
        ]
        if let id { json["id"] = id }
        return json
    }

    private func seeded(
        _ count: Int,
        total: Int? = nil,
        pageNum: Int = 1,
        firstID: Int = 1,
        enabled: Int = 1
    ) throws -> Page<ApiKeySummary> {
        try PageStub.page(
            ApiKeySummary.self,
            (0..<count).map { index in
                let id = firstID + index
                return rowJSON("密钥 \(id)", id: id, enabled: enabled)
            },
            pageNum: pageNum,
            total: total ?? count
        )
    }

    private func keyless() throws -> Page<ApiKeySummary> {
        try PageStub.page(ApiKeySummary.self, [rowJSON("无 id 密钥", id: nil)], total: 1)
    }

    private func made(prefix: String = "hnx_sk_live_nn...44de") -> ApiKeyCreatedSummary {
        let raw = "hnx_sk_live_abcdefghijklmnopqrstuvwxyz"
        return ApiKeyCreatedSummary(id: 7, name: "密钥 7", rawKey: raw, keyPrefix: prefix)
    }

    private func fresh() async throws -> (ApiKeyListViewModel, FakeApiKeys) {
        let keys = FakeApiKeys()
        keys.replies = [.success(try seeded(2))]
        let vm = ApiKeyListViewModel(catalog: keys)
        await vm.refresh()
        return (vm, keys)
    }

    // MARK: - the list itself

    func testTheFirstPageBecomesRowsNotASpinner() async throws {
        let keys = FakeApiKeys()
        let vm = ApiKeyListViewModel(catalog: keys)
        XCTAssertEqual(vm.phase, .loading, "the screen opens on a spinner, not on an empty promise")
        keys.replies = [.success(try seeded(2))]
        await vm.refresh()
        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertEqual(vm.total, 2)
        XCTAssertEqual(keys.requests.map(\.num), [1])
        XCTAssertEqual(keys.requests.map(\.size), [20])
        XCTAssertNil(keys.requests.last?.enabled, "no filter means the column stays off the URL")
        XCTAssertFalse(vm.isFiltered)
    }

    func testAnAccountWithNoKeysIsNotASpinningWheel() async throws {
        let keys = FakeApiKeys()
        keys.replies = [.success(try PageStub.page(ApiKeySummary.self, [], total: 0))]
        let vm = ApiKeyListViewModel(catalog: keys)
        await vm.refresh()
        XCTAssertEqual(vm.phase, .empty)
        XCTAssertFalse(vm.isFiltered, "the empty card may then promise there is no key at all")
    }

    func testNothingUnderAKeywordIsReportedAsNoMatchesNotAsAnEmptyAccount() async throws {
        let keys = FakeApiKeys()
        keys.replies = [
            .success(try PageStub.page(ApiKeySummary.self, [], total: 0)),
            .success(try PageStub.page(ApiKeySummary.self, [], total: 0)),
        ]
        let vm = ApiKeyListViewModel(catalog: keys)
        vm.keyword = "找不到的词"
        await vm.refresh()
        XCTAssertEqual(vm.phase, .empty)
        XCTAssertTrue(vm.isFiltered, "…but the copy has to say “no match”, not “nothing exists”")
        XCTAssertEqual(keys.requests.last?.keyword, "找不到的词")
    }

    func testAStatusFilterCountsAsAFilterOnItsOwnAndSpacesDoNot() async throws {
        let (vm, keys) = try await fresh()
        XCTAssertFalse(vm.isFiltered)
        vm.keyword = "   "
        XCTAssertFalse(vm.isFiltered, "spaces in the box must not let the empty card claim nothing exists")
        vm.keyword = "密钥"
        XCTAssertTrue(vm.isFiltered)
        vm.keyword = ""
        vm.filter = .enabled
        XCTAssertTrue(vm.isFiltered, "the status column narrows the list with no keyword at all")
        vm.filter = .all
        XCTAssertFalse(vm.isFiltered)
        keys.replies = [.success(try seeded(2)), .success(try seeded(2)), .success(try seeded(2))]
        try await Task.sleep(for: .milliseconds(700))
        XCTAssertEqual(keys.requests.count, 4, "each filter change re-read once, the settled keyword once more")
        XCTAssertEqual(keys.requests.map(\.num), [1, 1, 1, 1], "every one of them is a first page")
        XCTAssertEqual(keys.requests.last?.keyword, "")
        XCTAssertNil(keys.requests.last?.enabled)
        // Both fields already hold these values, so neither assignment is a change.
        vm.filter = .all
        vm.keyword = ""
        try await Task.sleep(for: .milliseconds(700))
        XCTAssertEqual(keys.requests.count, 4, "a repeated value is not a search and must not go back out")
        XCTAssertNil(vm.inlineError)
    }

    func testTheStatusFilterReReadsTheFirstPageAndSendsTheRawZeroOneColumn() async throws {
        let (vm, keys) = try await fresh()
        keys.replies = [.success(try seeded(1, total: 1))]
        vm.filter = .disabled
        try await waitUntil { keys.requests.count == 2 }
        XCTAssertEqual(keys.requests.last?.enabled, 0)
        XCTAssertEqual(keys.requests.last?.num, 1, "a filter change must not append to what is on screen")
        vm.filter = .enabled
        keys.replies = [.success(try seeded(1, total: 1))]
        try await waitUntil { keys.requests.count == 3 }
        XCTAssertEqual(keys.requests.last?.enabled, 1)
        vm.filter = .all
        keys.replies = [.success(try seeded(2))]
        try await waitUntil { keys.requests.count == 4 }
        XCTAssertNil(keys.requests.last?.enabled, "`all` leaves the column off rather than sending a sentinel")
    }

    func testAStoppedTypingKeywordIsOneRequestNotOnePerKeystroke() async throws {
        let (vm, keys) = try await fresh()
        keys.replies = [.success(try seeded(1, total: 1))]
        vm.keyword = "密"
        vm.keyword = "密钥"
        vm.keyword = "密钥 2"
        try await waitUntil { keys.requests.count == 2 }
        // Past the 500 ms window the console waits by, the two superseded tasks have been cancelled.
        try await Task.sleep(for: .milliseconds(700))
        XCTAssertEqual(keys.requests.count, 2, "the last keyword wins and the earlier two never go out")
        XCTAssertEqual(keys.requests.last?.keyword, "密钥 2")
        XCTAssertNil(vm.inlineError, "a cancelled keystroke search leaves no banner behind")
    }

    func testAFailedFirstLoadBecomesAnErrorStateNotAnEmptyCard() async throws {
        let keys = FakeApiKeys()
        keys.replies = [.failure(.offline)]
        let vm = ApiKeyListViewModel(catalog: keys)
        await vm.refresh()
        XCTAssertEqual(vm.phase, .failed(hx("error.offline")))
        XCTAssertTrue(vm.items.isEmpty)
    }

    func testAFailedRefreshKeepsTheRowsAndRaisesABanner() async throws {
        let (vm, keys) = try await fresh()
        keys.replies = [.failure(.timeout)]
        await vm.refresh()
        XCTAssertEqual(vm.phase, .content, "a refresh that failed must not blank the list")
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertEqual(vm.inlineError, hx("error.timeout"))
    }

    func testTheNextSuccessfulRefreshWipesTheBanner() async throws {
        let (vm, keys) = try await fresh()
        keys.replies = [.failure(.timeout)]
        await vm.refresh()
        XCTAssertNotNil(vm.inlineError)
        keys.replies = [.success(try seeded(2))]
        await vm.refresh()
        XCTAssertNil(vm.inlineError)
    }

    func testTheCounterComesFromTheServerNotFromTheLengthOfThisPage() async throws {
        let keys = FakeApiKeys()
        keys.replies = [.success(try seeded(2, total: 50))]
        let vm = ApiKeyListViewModel(catalog: keys)
        await vm.refresh()
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertEqual(vm.total, 50, "the row count on screen would otherwise promise forty keys that exist")
        XCTAssertTrue(vm.canLoadMore)
    }

    func testTheSecondPageIsAppendedWhenTheFirstReportsMoreRowsThanItCarried() async throws {
        let keys = FakeApiKeys()
        keys.replies = [.success(try seeded(2, total: 3))]
        let vm = ApiKeyListViewModel(catalog: keys)
        await vm.refresh()
        XCTAssertTrue(vm.canLoadMore)
        keys.replies = [.success(try seeded(1, total: 3, pageNum: 2, firstID: 3))]
        await vm.loadMore()
        XCTAssertEqual(vm.items.count, 3)
        XCTAssertEqual(keys.requests.map(\.num), [1, 2])
        XCTAssertFalse(vm.canLoadMore, "the counter says the list is exhausted, so no third request goes out")
    }

    func testAFailedPageKeepsTheCounterSoTheNextScrollRetriesTheSamePage() async throws {
        let keys = FakeApiKeys()
        keys.replies = [.success(try seeded(2, total: 5))]
        let vm = ApiKeyListViewModel(catalog: keys)
        await vm.refresh()
        keys.replies = [.failure(.offline)]
        await vm.loadMore()
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertEqual(vm.inlineError, hx("error.offline"))
        keys.replies = [.success(try seeded(1, total: 5, pageNum: 2, firstID: 3))]
        await vm.loadMore()
        XCTAssertEqual(keys.requests.map(\.num), [1, 2, 2], "a page that failed is retried, not skipped")
        XCTAssertEqual(vm.items.count, 3)
        XCTAssertNil(vm.inlineError, "the page that failed is not still being reported")
    }

    // MARK: - what a row may be asked to do

    func testARowTheStackNeverGaveAnIdToTakesNoWriteAtAll() async throws {
        let keys = FakeApiKeys()
        keys.replies = [.success(try keyless())]
        let vm = ApiKeyListViewModel(catalog: keys)
        await vm.refresh()
        let keyless = try XCTUnwrap(vm.items.first)
        XCTAssertNil(keyless.id)
        XCTAssertTrue(vm.status(of: keyless), "with no id there is no override slot to consult either")
        await vm.setStatus(false, for: keyless)
        vm.beginDelete(keyless)
        await vm.regenerate(keyless)
        XCTAssertTrue(keys.statusRequests.isEmpty)
        XCTAssertTrue(keys.deleteRequests.isEmpty)
        XCTAssertTrue(keys.regenerateRequests.isEmpty)
        XCTAssertNil(vm.deleteTarget, "the dialog cannot open on a row nothing can address")
        XCTAssertTrue(vm.pendingIDs.isEmpty)
        XCTAssertNil(vm.publishedKey)
    }

    func testTheSwitchFlipsTheRowBeforeTheStackAnswers() async throws {
        let (vm, keys) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        let id = try XCTUnwrap(row.id)
        XCTAssertTrue(vm.status(of: row))
        keys.gateWrites = true
        keys.statusReplies = [.success(EmptyResponse())]
        let write = Task { await vm.setStatus(false, for: row) }
        try await waitUntil { keys.statusRequests.count == 1 }
        XCTAssertFalse(vm.status(of: row), "a tap has to look decided on a slow link, not inert")
        XCTAssertEqual(vm.pendingIDs, [id])
        keys.releaseWrites()
        await write.value
        XCTAssertFalse(vm.status(of: row))
        XCTAssertTrue(row.isEnabled, "the row model itself is untouched — only the override moved")
        XCTAssertTrue(vm.pendingIDs.isEmpty)
        XCTAssertNil(vm.inlineError)
        XCTAssertEqual(keys.requests.count, 1, "a confirmed switch patches the row, it does not re-read the page")
    }

    func testARefusedSwitchSnapsBackAndSaysWhyInTheServersOwnWords() async throws {
        let (vm, keys) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        keys.gateWrites = true
        // The refusal is the backend's sentence for a protected key (`ApiKeyServiceImpl.kt:151-155`), and the
        // list carries no `keyType` to predict it from, so the override is all the client has.
        keys.statusReplies = [.failure(.business(code: 500, message: "PERMANENT API Key cannot be disabled"))]
        let write = Task { await vm.setStatus(false, for: row) }
        try await waitUntil { keys.statusRequests.count == 1 }
        XCTAssertFalse(vm.status(of: row))
        keys.releaseWrites()
        await write.value
        XCTAssertTrue(vm.status(of: row), "the override is dropped, so the row reads as the page left it")
        XCTAssertEqual(vm.inlineError, "PERMANENT API Key cannot be disabled")
        XCTAssertTrue(vm.pendingIDs.isEmpty)
    }

    func testAReReadPageIsAuthoritativeEvenOverAFreshSwitch() async throws {
        let (vm, keys) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        keys.statusReplies = [.success(EmptyResponse())]
        await vm.setStatus(false, for: row)
        XCTAssertFalse(vm.status(of: row))
        keys.replies = [.success(try seeded(2, enabled: 1))]
        await vm.refresh()
        XCTAssertTrue(vm.status(of: vm.items[0]), "the override is a stopgap until the page itself says otherwise")
    }

    // MARK: - delete

    func testADeleteTakesTheRowOutOfTheListAndOffTheCounter() async throws {
        let (vm, keys) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        let id = try XCTUnwrap(row.id)
        vm.beginDelete(row)
        XCTAssertEqual(vm.deleteTarget?.id, id)
        keys.deleteReplies = [.success(EmptyResponse())]
        await vm.confirmDelete()
        XCTAssertEqual(keys.deleteRequests, [id])
        XCTAssertEqual(vm.items.count, 1)
        XCTAssertEqual(vm.total, 1, "otherwise hasMore promises a page that has one fewer row in it")
        XCTAssertNil(vm.deleteTarget, "the dialog is closed by the confirm, not by the answer")
        XCTAssertNil(vm.inlineError)
        XCTAssertEqual(keys.requests.count, 1, "the row leaves from the delete alone; nothing re-reads the page")
    }

    func testACancelledDialogDeletesNothing() async throws {
        let (vm, keys) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        vm.beginDelete(row)
        vm.cancelDelete()
        XCTAssertNil(vm.deleteTarget)
        await vm.confirmDelete()
        XCTAssertTrue(keys.deleteRequests.isEmpty, "a dismissed dialog leaves nothing to confirm")
        XCTAssertNil(vm.inlineError)
    }

    func testARefusedDeleteLeavesTheRowUpWithTheReason() async throws {
        let (vm, keys) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        // A protected key refuses the delete with its own sentence (`ApiKeyServiceImpl.kt:142-144`).
        keys.deleteReplies = [.failure(.business(code: 500, message: "SYSTEM API Key cannot be deleted"))]
        vm.beginDelete(row)
        await vm.confirmDelete()
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertEqual(vm.total, 2)
        XCTAssertEqual(vm.inlineError, "SYSTEM API Key cannot be deleted")
        XCTAssertEqual(vm.phase, .content)
        XCTAssertNil(vm.deleteTarget)
    }

    func testOpeningTheDialogClearsAnEarlierBanner() async throws {
        let (vm, keys) = try await fresh()
        keys.deleteReplies = [.failure(.business(code: 500, message: "SYSTEM API Key cannot be deleted"))]
        vm.beginDelete(vm.items[0])
        await vm.confirmDelete()
        XCTAssertNotNil(vm.inlineError)
        vm.beginDelete(vm.items[1])
        XCTAssertNil(vm.inlineError, "a fresh question must not arrive wearing the last answer")
    }

    // MARK: - the one-time key

    func testRotatingPublishesTheKeyOnlyOnceTheStackHasAnswered() async throws {
        let (vm, keys) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        let id = try XCTUnwrap(row.id)
        keys.gateWrites = true
        keys.regenerateReplies = [.success(made())]
        keys.replies = [.success(try seeded(2)), .success(try seeded(2))]
        let rotate = Task { await vm.regenerate(row) }
        try await waitUntil { keys.regenerateRequests.count == 1 }
        XCTAssertNil(vm.publishedKey, "a secret the server has not minted yet cannot be shown")
        XCTAssertEqual(vm.pendingIDs, [id])
        keys.releaseWrites()
        await rotate.value
        XCTAssertEqual(vm.publishedKey?.rawKey, "hnx_sk_live_abcdefghijklmnopqrstuvwxyz")
        XCTAssertTrue(vm.pendingIDs.isEmpty)
    }

    func testARotateReReadsTheFirstPageBecauseTheRowsOwnPrefixChanged() async throws {
        let (vm, keys) = try await fresh()
        XCTAssertEqual(vm.items.first?.displayKey, "hnx_sk_live_ab...9f3c")
        let rotated = made()
        keys.regenerateReplies = [.success(rotated)]
        // The second page carries the new prefix on the same row id: the list is re-read rather than patched.
        keys.replies = [
            .success(try PageStub.page(
                ApiKeySummary.self,
                [
                    rowJSON("密钥 1", id: 1, prefix: rotated.keyPrefix),
                    rowJSON("密钥 2", id: 2),
                ],
                total: 2
            )),
        ]
        await vm.regenerate(try XCTUnwrap(vm.items.first))
        XCTAssertEqual(keys.requests.map(\.num), [1, 1], "the re-read is the first page, not an appended one")
        XCTAssertEqual(vm.items.first?.displayKey, rotated.keyPrefix)
        XCTAssertEqual(
            vm.publishedKey?.keyPrefix,
            rotated.keyPrefix,
            "the re-read must not take the just-shown secret off screen"
        )
        XCTAssertNil(vm.inlineError)
    }

    func testARefusedRotatePublishesNothingAndReReadsNothing() async throws {
        let (vm, keys) = try await fresh()
        let row = try XCTUnwrap(vm.items.first)
        let id = try XCTUnwrap(row.id)
        // Rotate runs no protected-type check, so an inaccessible row is the only refusal this route gives
        // (`ApiKeyServiceImpl.kt:162-183`); its sentence is the whole diagnosis.
        keys.regenerateReplies = [.failure(.business(code: 500, message: "No permission for this API Key"))]
        await vm.regenerate(row)
        XCTAssertNil(vm.publishedKey)
        XCTAssertEqual(vm.inlineError, "No permission for this API Key")
        XCTAssertEqual(keys.requests.count, 1, "a rotate that never landed has no new prefix to go and read")
        XCTAssertEqual(keys.regenerateRequests, [id], "the rotate was asked for; only its answer refused")
    }

    func testNoReadAppendSwitchOrDeleteEverProducesASecret() async throws {
        let (vm, keys) = try await fresh()
        XCTAssertNil(vm.publishedKey, "reading a page never produces a secret")
        keys.regenerateReplies = [.success(made())]
        keys.replies = [
            .success(try seeded(2, total: 4)),
            .success(try seeded(2, total: 4, firstID: 3)),
            .success(try seeded(2, total: 4)),
        ]
        await vm.regenerate(try XCTUnwrap(vm.items.first))
        XCTAssertNotNil(vm.publishedKey, "a rotate is the one route on this screen that hands one over")
        vm.dismissPublishedKey()
        XCTAssertNil(vm.publishedKey)
        await vm.loadMore()
        await vm.refresh()
        keys.statusReplies = [.success(EmptyResponse())]
        await vm.setStatus(false, for: vm.items[0])
        keys.deleteReplies = [.success(EmptyResponse())]
        vm.beginDelete(vm.items[0])
        await vm.confirmDelete()
        XCTAssertNil(vm.publishedKey, "the backend stores a digest and cannot show the key again")
    }

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }
}

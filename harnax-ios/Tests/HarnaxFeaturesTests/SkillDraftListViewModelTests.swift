import XCTest
import HarnaxCore
import HarnaxKit
import HarnaxFeatures

/// The review queue: three arms, a submit-style search, and a counter that only counts what is owed.
///
/// What is worth guarding here is the two-term split and the identity of a page. The queue's whole purpose is a
/// decision somebody owes, so the field text and the term in force are separate properties
/// (`harnax-webui/src/pages/skill/drafts.tsx:206-215`) — and every read after the commit, a tail page included,
/// has to carry the term in force rather than whatever is sitting in the box. Switching arm then has to discard
/// the accumulated rows: page 4 of `APPROVED` is not a continuation of a `PENDING` list, and appending it would
/// key de-duplication off the wrong `total`.
@MainActor
final class SkillDraftListViewModelTests: XCTestCase {
    private func row(_ id: Int, status: String = SkillDraftStatus.pending.rawValue) -> [String: Any] {
        ["id": id, "name": "草稿 \(id)", "status": status, "upstreamFindingCount": 0]
    }

    private func seeded(
        _ ids: [Int],
        pageNum: Int = 1,
        total: Int? = nil,
        status: String = SkillDraftStatus.pending.rawValue,
        size: Int = 20
    ) throws -> Page<SkillDraftRow> {
        try PageStub.page(
            SkillDraftRow.self,
            ids.map { row($0, status: status) },
            pageNum: pageNum,
            total: total ?? ids.count,
            pageSize: size
        )
    }

    private func fresh(
        pageSize: Int = 20
    ) async throws -> (SkillDraftListViewModel, FakeSkillDrafts) {
        let drafts = FakeSkillDrafts()
        drafts.pageReplies = [.success(try seeded([1, 2]))]
        let vm = SkillDraftListViewModel(drafts: drafts, pageSize: pageSize)
        await vm.refresh()
        return (vm, drafts)
    }

    // MARK: - the three arms

    func testTheQueueOpensOnPendingAndSendsTheArmEveryTime() async throws {
        let drafts = FakeSkillDrafts()
        let vm = SkillDraftListViewModel(drafts: drafts)
        XCTAssertEqual(vm.phase, .loading, "the screen opens on a spinner, not on an empty promise")
        drafts.pageReplies = [.success(try seeded([1, 2]))]
        await vm.refresh()
        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(drafts.pageRequests.count, 1)
        XCTAssertEqual(drafts.pageRequests.last?.status, .pending)
        XCTAssertEqual(
            drafts.pageRequests.last?.size,
            20,
            "the console's own page size (`drafts.tsx:30-31`), so both clients page through the same rows"
        )
        XCTAssertNil(drafts.pageRequests.last?.name, "no term means the key stays off the URL")
    }

    func testThePendingTagNeedsBothTheArmAndARowToCount() async throws {
        let drafts = FakeSkillDrafts()
        let vm = SkillDraftListViewModel(drafts: drafts)
        drafts.pageReplies = [
            .success(try seeded([1, 2, 3], total: 3)),
            .success(try seeded([1, 2, 3], total: 0)),
            .success(try seeded([1, 2, 3], total: 3, status: SkillDraftStatus.approved.rawValue)),
        ]
        await vm.refresh()
        XCTAssertTrue(vm.showsPendingCount, "a pending arm with 3 rows is 3 decisions somebody owes")
        XCTAssertEqual(vm.pendingCountForSegment, 3)

        await vm.refresh()
        XCTAssertFalse(vm.showsPendingCount, "a pending arm that answered zero has nothing to owe")
        XCTAssertNil(vm.pendingCountForSegment)

        vm.status = .approved
        try await waitUntil { drafts.pageRequests.count == 3 && vm.total == 3 }
        XCTAssertEqual(vm.total, 3, "the count still describes the queue, so the number itself is right")
        XCTAssertFalse(
            vm.showsPendingCount,
            "but a decided arm's total is a count of decisions already made, which is not a queue"
        )
    }

    func testSwitchingArmDiscardsTheAccumulatedPages() async throws {
        let drafts = FakeSkillDrafts()
        drafts.pageReplies = [
            .success(try seeded([1, 2], total: 4, size: 2)),
            .success(try seeded([3, 4], pageNum: 2, total: 4, size: 2)),
            .success(try seeded([9], total: 1, status: SkillDraftStatus.approved.rawValue, size: 2)),
        ]
        let vm = SkillDraftListViewModel(drafts: drafts, pageSize: 2)
        await vm.refresh()
        await vm.loadMore()
        XCTAssertEqual(vm.items.count, 4, "both pages of the pending arm are on screen")

        vm.status = .approved
        XCTAssertTrue(vm.items.isEmpty, "the old arm's four rows are dropped by the switch itself")
        XCTAssertEqual(vm.phase, .loading)
        try await waitUntil { drafts.pageRequests.count == 3 && vm.total == 1 }
        XCTAssertEqual(
            drafts.pageRequests.map(\.num),
            [1, 2, 1],
            "a new arm is a new first page, never a third page of the old one"
        )
        XCTAssertEqual(drafts.pageRequests.last?.status, .approved)
        XCTAssertEqual(vm.items.count, 1)
        XCTAssertEqual(vm.total, 1)
        XCTAssertFalse(vm.canLoadMore)
        XCTAssertNil(vm.inlineError)
    }

    func testTheEmptySentenceSplitsByArmNotByFilter() async throws {
        let drafts = FakeSkillDrafts()
        let vm = SkillDraftListViewModel(drafts: drafts)
        drafts.pageReplies = [
            .success(try seeded([], total: 0)),
            .success(try seeded([], total: 0, status: SkillDraftStatus.rejected.rawValue)),
            .success(try seeded([], total: 0, status: SkillDraftStatus.rejected.rawValue)),
        ]
        await vm.refresh()
        XCTAssertEqual(vm.phase, .empty)
        XCTAssertEqual(vm.emptyKey, "skill.draft.empty.pending")

        vm.status = .rejected
        try await waitUntil { drafts.pageRequests.count == 2 && vm.phase == .empty }
        XCTAssertEqual(vm.emptyKey, "skill.draft.empty.decided")

        vm.keyword = "找不到的词"
        await vm.search()
        XCTAssertEqual(
            vm.emptyKey,
            "skill.draft.empty.decided",
            "a search term does not change which of the two facts is on screen"
        )
    }

    // MARK: - the two terms

    func testATypedKeywordGoesOnTheWireOnlyWhenSubmitted() async throws {
        let (vm, drafts) = try await fresh()
        vm.keyword = "周报"
        try await settle()
        XCTAssertEqual(drafts.pageRequests.count, 1, "this list has no debounce — a keystroke is not a search")

        drafts.pageReplies = [.success(try seeded([1], total: 1))]
        await vm.search()
        XCTAssertEqual(drafts.pageRequests.count, 2)
        XCTAssertEqual(drafts.pageRequests.last?.name, "周报")
        XCTAssertEqual(vm.name, "周报")
        XCTAssertTrue(vm.isFiltered)
    }

    func testAnUntrimmedTermIsSentTrimmedAndAnEmptiedFieldLeavesTheKeyOff() async throws {
        let (vm, drafts) = try await fresh()
        drafts.pageReplies = [.success(try seeded([1], total: 1)), .success(try seeded([1], total: 1))]
        vm.keyword = "  周报  "
        await vm.search()
        XCTAssertEqual(drafts.pageRequests.last?.name, "周报", "the server gets the term, not the spaces")

        vm.keyword = "   "
        await vm.search()
        XCTAssertNil(drafts.pageRequests.last?.name, "a field of only spaces is no term at all")
        XCTAssertFalse(vm.isFiltered, "and the empty card may then say the arm has no rows")
    }

    func testCommittingTheTermAlreadyInForceSendsNothing() async throws {
        let (vm, drafts) = try await fresh()
        drafts.pageReplies = [.success(try seeded([1], total: 1))]
        vm.keyword = "周报"
        await vm.search()
        XCTAssertEqual(drafts.pageRequests.count, 2)
        await vm.search()
        XCTAssertEqual(drafts.pageRequests.count, 2, "re-committing what is already in force is not a search")

        // Enter on a field that still holds the committed term is the same no-op from the other side.
        vm.keyword = "周报"
        await vm.search()
        XCTAssertEqual(drafts.pageRequests.count, 2)
    }

    func testClearingTheFieldClearsTheTermInTheLeadAndReReads() async throws {
        let (vm, drafts) = try await fresh()
        drafts.pageReplies = [.success(try seeded([1], total: 1)), .success(try seeded([1, 2]))]
        vm.keyword = "周报"
        await vm.search()
        vm.keyword = ""
        await vm.clearSearch()
        XCTAssertEqual(vm.name, nil)
        XCTAssertEqual(vm.keyword, "")
        XCTAssertNil(drafts.pageRequests.last?.name)
        XCTAssertFalse(vm.isFiltered)

        let before = drafts.pageRequests.count
        await vm.clearSearch()
        XCTAssertEqual(
            drafts.pageRequests.count,
            before,
            "clearing an already-clear queue is nothing to re-read"
        )
    }

    func testAPageTailCarriesTheTermInTheLeadNotTheFieldOnScreen() async throws {
        let (vm, drafts) = try await fresh(pageSize: 1)
        drafts.pageReplies = [
            .success(try seeded([1], total: 3, size: 1)),
            .success(try seeded([2], pageNum: 2, total: 3, size: 1)),
        ]
        vm.keyword = "周报"
        await vm.search()
        // The reader keeps typing after the commit: the box now holds something the queue was never asked for.
        vm.keyword = "周报汇总的草稿标题"
        await vm.loadMore()

        let tails = drafts.pageRequests.dropFirst()
        XCTAssertEqual(tails.map(\.num), [1, 2])
        XCTAssertEqual(
            Array(tails.map(\.name)),
            ["周报", "周报"],
            "a tail read that dropped the term, or picked up the box, splices the wrong rows into this list"
        )
        XCTAssertEqual(vm.items.count, 2)
    }

    // MARK: - paging and failure

    func testTheCounterComesFromTheServerNotFromTheLengthOfThisPage() async throws {
        let drafts = FakeSkillDrafts()
        drafts.pageReplies = [.success(try seeded([1, 2], total: 50))]
        let vm = SkillDraftListViewModel(drafts: drafts)
        await vm.refresh()
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertEqual(vm.total, 50, "the row count on screen would otherwise promise 48 drafts that exist")
        XCTAssertTrue(vm.canLoadMore)
    }

    func testTheSecondPageIsAppendedWhenTheFirstPromisesMore() async throws {
        let drafts = FakeSkillDrafts()
        drafts.pageReplies = [
            .success(try seeded([1, 2], total: 3)),
            .success(try seeded([3], pageNum: 2, total: 3)),
        ]
        let vm = SkillDraftListViewModel(drafts: drafts)
        await vm.refresh()
        await vm.loadMore()
        XCTAssertEqual(vm.items.count, 3)
        XCTAssertEqual(drafts.pageRequests.map(\.num), [1, 2])
        XCTAssertFalse(vm.canLoadMore, "the counter says the queue is exhausted, so no third request goes out")
    }

    func testAFailedPageKeepsTheCounterSoTheNextScrollRetriesTheSamePage() async throws {
        let drafts = FakeSkillDrafts()
        drafts.pageReplies = [.success(try seeded([1, 2], total: 5))]
        let vm = SkillDraftListViewModel(drafts: drafts)
        await vm.refresh()
        drafts.pageReplies = [.failure(.offline)]
        await vm.loadMore()
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertEqual(vm.inlineError, hx("error.offline"))
        drafts.pageReplies = [.success(try seeded([3], pageNum: 2, total: 5))]
        await vm.loadMore()
        XCTAssertEqual(drafts.pageRequests.map(\.num), [1, 2, 2], "a page that failed is retried, not skipped")
        XCTAssertEqual(vm.items.count, 3)
        XCTAssertNil(vm.inlineError, "the page that failed is not still being reported")
    }

    func testAFailedFirstLoadBecomesAnErrorStateNotAnEmptyCard() async throws {
        let drafts = FakeSkillDrafts()
        drafts.pageReplies = [.failure(.offline)]
        let vm = SkillDraftListViewModel(drafts: drafts)
        await vm.refresh()
        XCTAssertEqual(vm.phase, .failed(hx("error.offline")))
        XCTAssertTrue(vm.items.isEmpty)
    }

    func testAFailedRefreshKeepsTheRowsAndTheNextGoodPageWipesTheBanner() async throws {
        let (vm, drafts) = try await fresh()
        drafts.pageReplies = [.failure(.timeout)]
        await vm.refresh()
        XCTAssertEqual(vm.phase, .content, "a refresh that failed must not blank the queue")
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertEqual(vm.inlineError, hx("error.timeout"))

        drafts.pageReplies = [.success(try seeded([1, 2]))]
        await vm.refresh()
        XCTAssertNil(vm.inlineError)
    }

    func testACancelledReadOnAQueueWithRowsRaisesNoBanner() async throws {
        let (vm, drafts) = try await fresh()
        drafts.pageReplies = [.failure(.cancelled)]
        await vm.refresh()
        XCTAssertNil(vm.inlineError, "a pull that was torn down mid-flight is not a load that failed")
        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(vm.items.count, 2)
    }

    // MARK: - the row predicate the queue prints

    func testAPatchedRowNeedsTheServerToSaySomethingMoved() async throws {
        let patched = try SkillDraftRow.stub([
            "createTime": "2026-10-01 09:00:00",
            "updateTime": "2026-10-02 09:00:00",
        ])
        XCTAssertTrue(SkillDraftListViewModel.isPatched(patched))

        let sameStamp = try SkillDraftRow.stub([
            "createTime": "2026-10-01 09:00:00",
            "updateTime": "2026-10-01 09:00:00",
        ])
        XCTAssertFalse(SkillDraftListViewModel.isPatched(sameStamp), "equal stamps mean nothing moved")

        let noCreation = try SkillDraftRow.stub(["updateTime": "2026-10-02 09:00:00"])
        XCTAssertFalse(
            SkillDraftListViewModel.isPatched(noCreation),
            "and with no `createTime` nothing can be said at all (`drafts.tsx:102`)"
        )
    }

    private func settle() async throws {
        try await Task.sleep(for: .milliseconds(600))
    }

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }
}

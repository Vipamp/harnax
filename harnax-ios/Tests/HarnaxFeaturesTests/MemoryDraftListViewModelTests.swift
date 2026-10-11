import XCTest
import HarnaxCore
import HarnaxKit
import HarnaxFeatures

/// The memory review queue: three arms and no「show everything」, a submit-style search whose field text and term
/// in force are two different properties, and a badge that counts what is still owed rather than what is on
/// screen.
///
/// The two properties are the point of the search here. A memory candidate is a whole rewrite of one file that
/// its own conversation keeps re-merging while the row sits open, so a queue that paged on every keystroke would
/// churn a list that is also *moving under the reader* — and unlike a skill draft, a memory row's text can change
/// without its status doing so (`harnax-webui/src/pages/memory/drafts.tsx:343-355` therefore uses an
/// `Input.Search`, and the term goes out only on commit).
///
/// One mechanical note the doubles make visible: a `refresh()` on this queue takes **two** page replies, because
/// the badge is read apart from the page (`MemoryDraftCataloging.swift:50-56`) and an unqueued read answers as a
/// failure rather than as nothing.
@MainActor
final class MemoryDraftListViewModelTests: XCTestCase {
    private func seeded(
        _ ids: [Int],
        pageNum: Int = 1,
        total: Int? = nil,
        status: String = MemoryDraftStatus.pending.rawValue,
        size: Int = 20
    ) throws -> Page<MemoryDraftRow> {
        try PageStub.page(
            MemoryDraftRow.self,
            ids.map {
                [
                    "id": $0,
                    "agentName": "翻译助手",
                    "sessionId": "sess-\($0)",
                    "status": status,
                    // Four columns carry non-null defaults on the DTO (`MemoryDraftResponse.kt:31-41`), so they
                    // are always present on the wire and always needed to build a row.
                    "baseVersion": 3,
                    "mergedChars": 120,
                    "sourceCount": 2,
                    "targetCount": 1,
                ]
            },
            pageNum: pageNum,
            total: total ?? ids.count,
            pageSize: size
        )
    }

    /// A queue already showing two pending candidates: the page plus the badge read it takes with it.
    private func fresh(pageSize: Int = 20) async throws -> (MemoryDraftListViewModel, FakeMemoryDrafts) {
        let drafts = FakeMemoryDrafts()
        drafts.pageReplies = [
            .success(try seeded([1, 2], total: 2)),
            .success(try seeded([1], total: 2, size: 1)),
        ]
        let vm = MemoryDraftListViewModel(catalog: drafts, pageSize: pageSize)
        await vm.refresh()
        return (vm, drafts)
    }

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }

    // MARK: - 1. the three arms

    func testTheQueueOpensOnThePendingArmAndNamesTheArmOnEveryRead() async throws {
        let drafts = FakeMemoryDrafts()
        let vm = MemoryDraftListViewModel(catalog: drafts)
        XCTAssertEqual(vm.phase, .loading, "the screen opens on a spinner, not on an empty promise")
        XCTAssertEqual(vm.status, .pending, "`drafts.tsx:312-342` starts on the arm that still owes a decision")
        drafts.pageReplies = [
            .success(try seeded([1, 2], total: 2)),
            .success(try seeded([1], total: 2, size: 1)),
        ]
        await vm.refresh()
        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(drafts.pageRequests.count, 2)
        XCTAssertEqual(drafts.pageRequests[0].status, .pending)
        XCTAssertEqual(drafts.pageRequests[0].num, 1)
        XCTAssertEqual(
            drafts.pageRequests[0].size,
            20,
            "the console's own page size (`drafts.tsx:44`), so both clients walk the queue in the same slices"
        )
        XCTAssertNil(drafts.pageRequests[0].agentName, "no term means the key stays off the URL")
        XCTAssertNil(drafts.pageRequests[0].sessionId, "this queue is the owner's, across every conversation")
        XCTAssertEqual(
            drafts.pageRequests[1].size,
            1,
            "the second read is the badge, and one row is enough to read a total"
        )
    }

    /// The fourth arm the console does not have either: a queue exists to be worked, and every row of an
    /// all-inclusive arm is a row somebody already finished with.
    func testThereIsNoShowEverythingArm() {
        XCTAssertEqual(
            MemoryDraftStatus.allCases,
            [.pending, .approved, .rejected],
            "the route validates exactly these three (`MemoryDraftServiceImpl.kt:170-175`)"
        )
        XCTAssertNil(MemoryDraftStatus(rawValue: "ALL"))
        XCTAssertNil(MemoryDraftStatus(rawValue: ""))
        XCTAssertEqual(MemoryDraftStatus.pending.titleKey, "memory.draft.status.PENDING")
    }

    /// The badge is the queue's own size, read apart from both the arm on screen and the term in force
    /// (`drafts.tsx:289-292`). A number that followed the filter would say「0 waiting」while a search hid three of
    /// the four pending merges, and on this screen that sentence is the one that makes a reviewer stop looking.
    func testThePendingArmShowsTheQueueOwnPendingCount() async throws {
        let drafts = FakeMemoryDrafts()
        drafts.pageReplies = [
            .success(try seeded([1, 2, 3], total: 3)),
            .success(try seeded([1], total: 7, size: 1)),
        ]
        let vm = MemoryDraftListViewModel(catalog: drafts)
        await vm.refresh()
        XCTAssertEqual(vm.total, 3, "what this filtered page served")
        XCTAssertEqual(vm.pendingCount, 7, "and what the whole queue still owes")
        XCTAssertEqual(vm.countForSegment(.pending), 7)
        XCTAssertNil(
            vm.countForSegment(.approved),
            "a decided arm's total is a count of decisions already made, which is not a queue"
        )
    }

    /// A badge that cannot be re-read is a badge that is lying, so a failed count read takes the number off
    /// rather than leaving the last honest one up.
    func testAFailedBadgeReadTakesTheNumberOff() async throws {
        let drafts = FakeMemoryDrafts()
        drafts.pageReplies = [
            .success(try seeded([1, 2], total: 2)),
            .success(try seeded([1], total: 7, size: 1)),
            .success(try seeded([1, 2], total: 2)),
            .failure(.timeout),
        ]
        let vm = MemoryDraftListViewModel(catalog: drafts)
        await vm.refresh()
        XCTAssertEqual(vm.pendingCount, 7)
        await vm.refresh()
        XCTAssertNil(vm.pendingCount)
        XCTAssertNil(vm.countForSegment(.pending))
        XCTAssertEqual(vm.items.count, 2, "the rows themselves still came back")
        XCTAssertNil(vm.inlineError, "a missing badge is not a failed read of the queue")
    }

    func testTheEmptySentenceSplitsByArmNotByFilter() async throws {
        let drafts = FakeMemoryDrafts()
        drafts.pageReplies = [
            .success(try seeded([], total: 0)),
            .success(try seeded([], total: 0, size: 1)),
            .success(try seeded([], total: 0, status: MemoryDraftStatus.rejected.rawValue)),
            .success(try seeded([], total: 0, size: 1)),
        ]
        let vm = MemoryDraftListViewModel(catalog: drafts)
        await vm.refresh()
        XCTAssertEqual(vm.phase, .empty)
        XCTAssertEqual(vm.emptyKey, "memory.draft.empty.pending")

        vm.status = .rejected
        XCTAssertTrue(vm.items.isEmpty)
        try await waitUntil { drafts.pageRequests.count == 4 && vm.phase == .empty }
        XCTAssertEqual(vm.emptyKey, "memory.draft.empty.decided")
    }

    // MARK: - 2. search, and what an arm switch discards

    func testTheFieldTextIsNotAQueryUntilItIsCommitted() async throws {
        let (vm, drafts) = try await fresh()
        XCTAssertEqual(drafts.pageRequests.count, 2, "both reads of the opening refresh")

        vm.keyword = "  翻译  "
        XCTAssertEqual(drafts.pageRequests.count, 2, "typing sends nothing (`drafts.tsx:343-355`)")
        XCTAssertNil(vm.agentName, "the field is not the term in force")

        drafts.pageReplies = [
            .success(try seeded([1], total: 1)),
            .success(try seeded([1], total: 1, size: 1)),
        ]
        await vm.search()
        XCTAssertEqual(vm.agentName, "翻译", "and the term that goes out is trimmed")
        XCTAssertEqual(drafts.lastListRead?.agentName, "翻译")

        await vm.search()
        XCTAssertEqual(
            drafts.pageRequests.count,
            4,
            "an unchanged term is the only thing worth refusing to do"
        )
    }

    /// The field's cancel button cannot go through `search()`: an emptied box would commit nothing and leave the
    /// old term in force, which is exactly the「filtered and it still looks unfiltered」state.
    func testClearingTheFieldDropsTheTermAndReReads() async throws {
        let (vm, drafts) = try await fresh()
        drafts.pageReplies = [
            .success(try seeded([1], total: 1)),
            .success(try seeded([1], total: 1, size: 1)),
            .success(try seeded([1, 2], total: 2)),
            .success(try seeded([1], total: 2, size: 1)),
        ]
        vm.keyword = "翻译"
        await vm.search()
        XCTAssertEqual(drafts.lastListRead?.agentName, "翻译")

        vm.keyword = ""
        await vm.clearSearch()
        XCTAssertNil(vm.agentName)
        XCTAssertNil(drafts.lastListRead?.agentName, "a cleared term must leave the list read, not just the badge")
        XCTAssertEqual(vm.keyword, "")

        let before = drafts.pageRequests.count
        await vm.clearSearch()
        XCTAssertEqual(before, drafts.pageRequests.count, "nothing in force and nothing to clear means no read")
    }

    /// Every read after the commit carries the term in force, a tail page included — not whatever is sitting in
    /// the box.
    func testATailPageCarriesTheCommittedTerm() async throws {
        let drafts = FakeMemoryDrafts()
        drafts.pageReplies = [
            .success(try seeded([1, 2], total: 4, size: 2)),
            .success(try seeded([1], total: 4, size: 1)),
            .success(try seeded([3, 4], pageNum: 2, total: 4, size: 2)),
        ]
        let vm = MemoryDraftListViewModel(catalog: drafts, pageSize: 2)
        vm.keyword = "翻译"
        await vm.search()
        await vm.loadMore()
        XCTAssertEqual(vm.items.count, 4)
        XCTAssertEqual(drafts.pageRequests.count, 3)
        XCTAssertEqual(drafts.pageRequests[2].num, 2)
        XCTAssertEqual(drafts.pageRequests[2].agentName, "翻译")
    }

    func testSwitchingArmDiscardsTheAccumulatedPages() async throws {
        let drafts = FakeMemoryDrafts()
        drafts.pageReplies = [
            .success(try seeded([1, 2], total: 4, size: 2)),
            .success(try seeded([1], total: 4, size: 1)),
            .success(try seeded([3, 4], pageNum: 2, total: 4, size: 2)),
            .success(try seeded([9], total: 1, status: MemoryDraftStatus.approved.rawValue, size: 2)),
            .success(try seeded([1], total: 4, size: 1)),
        ]
        let vm = MemoryDraftListViewModel(catalog: drafts, pageSize: 2)
        await vm.refresh()
        await vm.loadMore()
        XCTAssertEqual(vm.items.count, 4, "both pages of the pending arm are on screen")

        vm.status = .approved
        XCTAssertTrue(vm.items.isEmpty, "the old arm's four rows are dropped by the switch itself")
        XCTAssertEqual(vm.phase, .loading)
        XCTAssertEqual(vm.total, 0)
        try await waitUntil { drafts.pageRequests.count == 5 && vm.total == 1 }
        XCTAssertEqual(
            drafts.pageRequests.map(\.num),
            [1, 1, 2, 1, 1],
            "a new arm is a new first page, never a third page of the old one"
        )
        XCTAssertEqual(drafts.pageRequests[3].status, .approved)
        XCTAssertEqual(vm.items.map(\.id), [9])
        XCTAssertFalse(vm.canLoadMore)
        XCTAssertNil(vm.inlineError)
    }

    /// A tail page that answers after the arm changed belongs to a list nobody is looking at any more.
    func testAnAppendThatAnswersLateIsDiscardedRatherThanAppended() async throws {
        let drafts = FakeMemoryDrafts()
        drafts.pageReplies = [
            .success(try seeded([1, 2], total: 4, size: 2)),
            .success(try seeded([1], total: 4, size: 1)),
            .success(try seeded([3, 4], pageNum: 2, total: 4, size: 2)),
            .success(try seeded([9], total: 1, status: MemoryDraftStatus.approved.rawValue, size: 2)),
            .success(try seeded([1], total: 1, size: 1)),
        ]
        let vm = MemoryDraftListViewModel(catalog: drafts, pageSize: 2)
        await vm.refresh()
        XCTAssertEqual(vm.items.count, 2)

        drafts.pageGate.arm()
        Task { await vm.loadMore() }
        try await waitUntil { drafts.pageRequests.count == 3 }

        vm.status = .approved
        XCTAssertTrue(vm.items.isEmpty)
        drafts.pageGate.release()
        try await waitUntil { drafts.pageRequests.count == 5 && vm.total == 1 }
        XCTAssertEqual(
            vm.items.map(\.id),
            [9],
            "the old arm's second page must not join the approved list"
        )
    }

    // MARK: - 3. the patched marker

    /// 「已重写」is `updateTime` strictly after `createTime`, and the judgement is the row's own property rather
    /// than a column comparison the screen re-invents (`drafts.tsx:213-221`). A tie means nothing moved; a missing
    /// `createTime` means nothing can be said at all.
    func testThePatchedMarkerIsTheRowsOwn() throws {
        let rewritten = try MemoryDraftRow.stub([
            "createTime": "2026-10-05 09:00:00",
            "updateTime": "2026-10-05 11:00:00",
        ])
        XCTAssertTrue(rewritten.patched)
        XCTAssertTrue(MemoryDraftListViewModel.isPatched(rewritten))

        let untied = try MemoryDraftRow.stub([
            "createTime": "2026-10-05 09:00:00",
            "updateTime": "2026-10-05 09:00:00",
        ])
        XCTAssertFalse(MemoryDraftListViewModel.isPatched(untied), "equal stamps mean the merge said the same thing twice")

        let older = try MemoryDraftRow.stub([
            "createTime": "2026-10-05 11:00:00",
            "updateTime": "2026-10-05 09:00:00",
        ])
        XCTAssertFalse(MemoryDraftListViewModel.isPatched(older))

        let blind = try MemoryDraftRow.stub(["updateTime": "2026-10-05 11:00:00"])
        XCTAssertFalse(MemoryDraftListViewModel.isPatched(blind), "no `createTime` means nothing can be claimed")
    }

    // MARK: - 8. after a decision, the queue re-reads

    /// The row does not get edited out by hand. A candidate that was approved left the pending arm on the
    /// server, and the only honest way for the queue to learn that is to ask again — a splice would keep a row
    /// whose digest, counts and status the reviewer never saw re-read.
    func testADecidedRowDisappearsOnlyBecauseTheServerSaidSo() async throws {
        let drafts = FakeMemoryDrafts()
        drafts.pageReplies = [
            .success(try seeded([1, 2], total: 2)),
            .success(try seeded([1], total: 2, size: 1)),
            .success(try seeded([2], total: 1)),
            .success(try seeded([], total: 1, size: 1)),
        ]
        let vm = MemoryDraftListViewModel(catalog: drafts)
        await vm.refresh()
        XCTAssertEqual(vm.items.map(\.id), [1, 2])

        await vm.refresh()
        XCTAssertEqual(vm.items.map(\.id), [2], "row 1 is gone because the next read said so")
        XCTAssertEqual(vm.total, 1)
        XCTAssertEqual(vm.pendingCount, 1)
        XCTAssertNil(vm.inlineError)
    }

    /// A refresh that fails over rows already on screen keeps the rows and says so inline: the queue did not
    /// stop existing, this read failed. The reviewer still has somewhere to tap reload.
    func testAFailedRefreshKeepsTheRowsAndReportsInline() async throws {
        let (vm, drafts) = try await fresh()
        drafts.pageReplies = [
            .failure(.business(code: 500, message: "boom")),
        ]
        await vm.refresh()
        XCTAssertEqual(vm.items.count, 2, "the rows that were readable stay on screen")
        XCTAssertEqual(vm.phase, .content)
        XCTAssertNotNil(vm.inlineError)
    }

    /// With nothing on screen yet there is nothing to keep, so the whole screen is the failure.
    func testAFailedFirstReadIsTheWholeScreen() async throws {
        let drafts = FakeMemoryDrafts()
        drafts.pageReplies = [.failure(.offline)]
        let vm = MemoryDraftListViewModel(catalog: drafts)
        await vm.refresh()
        if case .failed = vm.phase {} else {
            XCTFail("an empty queue that could not be read is an error, not an empty state: \(vm.phase)")
        }
        XCTAssertTrue(vm.items.isEmpty)
    }

    // MARK: - paging

    func testATailPageAppendsAndStopsWhenTheServerSaysSo() async throws {
        let drafts = FakeMemoryDrafts()
        drafts.pageReplies = [
            .success(try seeded([1, 2], total: 3, size: 2)),
            .success(try seeded([1], total: 3, size: 1)),
            .success(try seeded([3], pageNum: 2, total: 3, size: 2)),
        ]
        let vm = MemoryDraftListViewModel(catalog: drafts, pageSize: 2)
        await vm.refresh()
        XCTAssertTrue(vm.canLoadMore, "2 of 3 served is not the end of the queue")
        await vm.loadMore()
        XCTAssertEqual(vm.items.map(\.id), [1, 2, 3])
        XCTAssertFalse(vm.canLoadMore)
        XCTAssertEqual(drafts.pageRequests.count, 3)

        await vm.loadMore()
        XCTAssertEqual(drafts.pageRequests.count, 3, "a queue that has been walked to the end asks for nothing")
    }

    /// A tail read that fails leaves the head rows alone and says so inline rather than pretending the queue
    /// ended.
    func testAFailedTailPageKeepsWhatIsOnScreen() async throws {
        let drafts = FakeMemoryDrafts()
        drafts.pageReplies = [
            .success(try seeded([1, 2], total: 4, size: 2)),
            .success(try seeded([1], total: 4, size: 1)),
            .failure(.timeout),
        ]
        let vm = MemoryDraftListViewModel(catalog: drafts, pageSize: 2)
        await vm.refresh()
        await vm.loadMore()
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertTrue(vm.canLoadMore)
        XCTAssertNotNil(vm.inlineError)
        XCTAssertFalse(vm.isAppending)
    }
}

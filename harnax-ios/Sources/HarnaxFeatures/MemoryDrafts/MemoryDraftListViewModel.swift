import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// The memory merge queue: `GET /api/admin/memory-drafts?status=&agentName=`.
///
/// Three arms and no「show me everything」(`harnax-webui/src/pages/memory/drafts.tsx:312-342`), because every
/// row on a fourth arm is a row somebody already finished with, and this queue exists to be worked. The screen
/// opens on `PENDING` for the same reason — which is also the server's own default
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/MemoryDraftController.kt:74`), and the
/// arm is still named on every request because it is the only way to ask for a decided one.
///
/// The search is **submit-style**, exactly as the console's `Input.Search` is (`drafts.tsx:343-355`): the
/// request goes out on Enter, on the search button and on the clear button, never on a keystroke. A memory
/// candidate is a whole rewrite of one file that its own conversation keeps re-merging while the row sits open,
/// so a queue that paged on every character would churn a list that is also *moving under the reader* — and
/// unlike the skill queue, a memory row's text can change without its status doing so. `keyword` and
/// `agentName` are therefore two properties, and only the second one goes on the wire.
@MainActor
public final class MemoryDraftListViewModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        case content
        case empty
        case failed(String)
    }

    @Published public private(set) var phase: Phase = .loading
    @Published public private(set) var items: [MemoryDraftRow] = []
    @Published public private(set) var total = 0
    @Published public private(set) var inlineError: String?
    @Published public private(set) var canLoadMore = false
    @Published public private(set) var isAppending = false

    /// What the search field holds, which is not what is on the wire until `search()` commits it.
    @Published public var keyword = ""

    /// The trimmed term in force, `nil` when nothing was committed. This — not `keyword` — is what every
    /// request carries, a tail page and a pull-to-refresh included.
    ///
    /// Named for the query key (`MemoryDraftController.kt:69`), because the server matches it partially against
    /// the agent whose long-term layer the merge rewrites, and against nothing else.
    @Published public private(set) var agentName: String?

    /// The arm on screen. Switching it discards the accumulated pages: page 4 of `APPROVED` rows is not a
    /// continuation of a `PENDING` list, and `append` would key de-duplication off the wrong `total`.
    @Published public var status: MemoryDraftStatus = .pending {
        didSet {
            guard status != oldValue else { return }
            resetPages()
            Task { await refresh() }
        }
    }

    /// The queue's own size, read apart from the page (`MemoryDraftCataloging.pendingCount()`).
    ///
    /// Not the page's `total`, which is what the console badges (`drafts.tsx:289-292`): a badge that followed the
    /// filter would say「0 waiting」while a search term hid three of the four pending merges, and on this screen
    /// that sentence is the one that makes a reviewer stop looking. The read ignores both the arm and the term
    /// (`MemoryDraftCataloging.pendingCount()`), so the number is the whole queue's.
    @Published public private(set) var pendingCount: Int?

    private let catalog: any MemoryDraftCataloging
    private var pages: PagedState<MemoryDraftRow>
    /// Refresh identity and the one-in-flight rule, as the other lists document them: the last answer to arrive
    /// must not be the one that wins the rows, the page counter and the total.
    private var refreshGeneration = 0
    private var isRefreshing = false
    private var rerunRequested = false
    /// An append asked for while a refresh is on the wire is remembered, not dropped.
    private var appendRequested = false

    /// 20 is the console's page size and the controller's own default (`drafts.tsx:44`,
    /// `MemoryDraftController.kt:61-64`), so both clients walk the queue in the same slices.
    public init(catalog: any MemoryDraftCataloging, pageSize: Int = 20) {
        self.catalog = catalog
        pages = PagedState(pageSize: pageSize)
    }

    /// The segment's count slot: only the pending arm has a number to show, because only a pending merge is
    /// something the owner still owes.
    public func countForSegment(_ arm: MemoryDraftStatus) -> Int? {
        arm == .pending ? pendingCount : nil
    }

    /// The empty sentence splits by arm, not by filter (`drafts.tsx:369-378`):「no conversation memory is
    /// waiting」and「no decided merge」are different facts, and a search term does not change which is true.
    public var emptyKey: String {
        status == .pending ? "memory.draft.empty.pending" : "memory.draft.empty.decided"
    }

    // MARK: - Search

    /// Commit the field. Enter and the search button both land here (`drafts.tsx:343-355`); no debounce timer
    /// exists, so an unchanged term is the only thing worth refusing to do.
    public func search() async {
        let term = keyword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard term != agentName ?? "" else { return }
        agentName = term.isEmpty ? nil : term
        await refresh()
    }

    /// Clear both halves and re-read. The field's cancel button has to come here rather than to `search()`,
    /// because an emptied field would otherwise commit nothing and leave the old term in force.
    public func clearSearch() async {
        keyword = ""
        guard agentName != nil else { return }
        agentName = nil
        await refresh()
    }

    // MARK: - Paging

    public func refresh() async {
        refreshGeneration += 1
        guard !isRefreshing else {
            rerunRequested = true
            return
        }
        await runRefresh(generation: refreshGeneration)
        while rerunRequested {
            rerunRequested = false
            await runRefresh(generation: refreshGeneration)
        }
    }

    private func runRefresh(generation: Int) async {
        isRefreshing = true
        defer { isRefreshing = false }
        inlineError = nil
        // `sessionId: nil`: this queue is the owner's, across every conversation they have. The route takes the
        // exact session id (`MemoryDraftController.kt:70`) and no screen here has one to give it.
        switch await catalog.page(
            status: status, agentName: agentName, sessionId: nil, num: 1, size: pages.pageSize
        ) {
        case let .success(page):
            guard generation == refreshGeneration else { return }
            pages.replace(with: page)
            apply()
            await reissueAppend()
            await readPendingCount(generation: generation)
        case let .failure(error):
            guard generation == refreshGeneration else { return }
            guard ErrorMessage.carriesNews(error) else { return }
            let text = ErrorMessage.text(for: error)
            if items.isEmpty {
                phase = .failed(text)
            } else {
                // Rows already on screen stay: the queue did not stop existing, this read failed.
                inlineError = text
            }
        }
    }

    public func loadMore() async {
        guard canLoadMore, !isAppending else { return }
        guard !isRefreshing else {
            appendRequested = true
            return
        }
        await runAppend()
    }

    /// The tail read under the identity of the query that was on screen when the scroll happened, so a page
    /// that answers after an arm switch or a new search is discarded rather than appended to the wrong list.
    private func runAppend() async {
        let generation = refreshGeneration
        isAppending = true
        defer { isAppending = false }
        switch await catalog.page(
            status: status, agentName: agentName, sessionId: nil, num: pages.pageNum + 1, size: pages.pageSize
        ) {
        case let .success(page):
            guard generation == refreshGeneration else { return }
            inlineError = nil
            pages.append(with: page)
            apply()
        case let .failure(error):
            guard generation == refreshGeneration else { return }
            inlineError = ErrorMessage.text(for: error)
        }
    }

    private func reissueAppend() async {
        guard appendRequested, canLoadMore, !isAppending else { return }
        appendRequested = false
        await runAppend()
    }

    /// The badge read, after the rows it accompanies.
    ///
    /// A failed read takes the number **off** the segment instead of leaving the last one there: a badge that
    /// cannot be re-read is a badge that is lying about how much work the queue holds.
    private func readPendingCount(generation: Int) async {
        let count = await catalog.pendingCount()
        guard generation == refreshGeneration else { return }
        pendingCount = count
    }

    /// Drop the accumulated rows without touching the arm or the committed term — both are the query, and a
    /// caller that changes one of them wants the tail of the *new* query.
    private func resetPages() {
        refreshGeneration += 1
        items = []
        total = 0
        inlineError = nil
        pages = PagedState(pageSize: pages.pageSize)
        phase = .loading
    }

    private func apply() {
        items = pages.elements
        total = pages.total
        canLoadMore = pages.hasMore
        phase = pages.isEmpty ? .empty : .content
    }

    // MARK: - Row predicates

    /// 「已重写」: `updateTime` strictly after `createTime`, no tie, and a missing `createTime` marks nothing
    /// (`drafts.tsx:213-221`). The judgement is the Core function the row already calls
    /// (`MemoryDraftRow.patched`), so the queue and a test read one rule.
    public static func isPatched(_ row: MemoryDraftRow) -> Bool { row.patched }
}

import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// The review queue: `GET /api/admin/skill-drafts?status=&name=`.
///
/// Three arms and no「show me everything」(`drafts.tsx:193-205`), because every row on a fourth arm is a row
/// somebody already finished with, and the queue exists to be worked. The screen opens on `PENDING` for the
/// same reason.
///
/// The search is **submit-style**: `drafts.tsx:206-215` fires the request from the search handler, not from a
/// keystroke. That is the opposite of this app's other lists (`SkillTableView` debounces 500ms off `keyword`),
/// and it is deliberate here: a queue row is a decision somebody owes, so paging through it while a name is
/// still being typed churns a list that can also be *shrinking underneath* as another reviewer works it. The
/// field text and the term in force are therefore two separate properties — see `keyword` and `name`.
@MainActor
public final class SkillDraftListViewModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        case content
        case empty
        case failed(String)
    }

    @Published public private(set) var phase: Phase = .loading
    @Published public private(set) var items: [SkillDraftRow] = []
    @Published public private(set) var total = 0
    @Published public private(set) var inlineError: String?
    @Published public private(set) var canLoadMore = false
    @Published public private(set) var isAppending = false

    /// What the search field holds, which is not necessarily what is on the wire until `search()` is called.
    @Published public var keyword = ""

    /// The arm on screen. Switching it discards the accumulated pages — a page 4 of `APPROVED` rows is not a
    /// continuation of a `PENDING` list, and `append` would key de-duplication off the wrong `total`.
    @Published public var status: SkillDraftStatus = .pending {
        didSet {
            guard status != oldValue else { return }
            resetPages()
            Task { await refresh() }
        }
    }

    /// The trimmed term in force, `nil` when nothing was committed. This — not `keyword` — is what goes on
    /// every request, including a page tail and a pull-to-refresh.
    @Published public private(set) var name: String?

    private let drafts: any SkillDraftCataloging
    private var pages: PagedState<SkillDraftRow>
    /// Refresh identity and the one-in-flight rule, as `AgentListViewModel` documents them: the last answer to
    /// arrive must not be the one that wins the rows, the page counter and the total.
    private var refreshGeneration = 0
    private var isRefreshing = false
    private var rerunRequested = false
    /// An append asked for while a refresh is on the wire is remembered, not dropped.
    private var appendRequested = false

    /// `pageSize` is what the console asks for and the server's own default (`drafts.tsx:30-31`).
    public init(drafts: any SkillDraftCataloging, pageSize: Int = 20) {
        self.drafts = drafts
        pages = PagedState(pageSize: pageSize)
    }

    /// The pending-count tag:「{n} 待审」appears only on the arm that can be counted as owed
    /// (`drafts.tsx:178-182`). Both halves are load-bearing — a `PENDING` arm that answered zero has nothing
    /// to owe, and a `REJECTED` arm's `total` is a count of decisions already made, which is not a queue.
    public var showsPendingCount: Bool { status == .pending && total > 0 }

    /// The segment's count slot, which follows the same two conditions as the tag.
    public var pendingCountForSegment: Int? { showsPendingCount ? total : nil }

    /// The empty sentence splits by arm, not by filter (`drafts.tsx:230-233`):「nothing waiting for you」and
    ///「nothing decided yet」are different facts, and a search term does not change which one is true.
    public var emptyKey: String {
        status == .pending ? "skill.draft.empty.pending" : "skill.draft.empty.decided"
    }

    // MARK: - Search

    /// Commit the field. Enter and the clear button both land here (`drafts.tsx:206-215`); no debounce timer
    /// exists, so an unchanged term is the only thing worth refusing to do.
    public func search() async {
        let term = keyword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard term != name ?? "" else { return }
        name = term.isEmpty ? nil : term
        await refresh()
    }

    /// Clear both halves and re-read. Called by the field's own cancel button; `search()` alone cannot do it
    /// because an emptied field would otherwise commit nothing and leave the old term in force.
    public func clearSearch() async {
        keyword = ""
        guard name != nil else { return }
        name = nil
        await refresh()
    }

    /// Whether the term in force has rows to show less than the arm's whole contents — the sentence under the
    /// empty state has to say which of the two is on screen.
    public var isFiltered: Bool { name != nil }

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
        switch await drafts.page(status: status, name: name, num: 1, size: pages.pageSize) {
        case let .success(page):
            guard generation == refreshGeneration else { return }
            pages.replace(with: page)
            apply()
            await reissueAppend()
        case let .failure(error):
            guard generation == refreshGeneration else { return }
            guard ErrorMessage.carriesNews(error) else { return }
            let text = ErrorMessage.text(for: error)
            if items.isEmpty {
                phase = .failed(text)
            } else {
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
        switch await drafts.page(status: status, name: name, num: pages.pageNum + 1, size: pages.pageSize) {
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

    /// 「已打补丁」: `updateTime` strictly after `createTime`, no tie, and a missing `createTime` never marks
    /// (`drafts.tsx:102`). The judgement itself is the Core function, so the queue and a test read the same
    /// rule (`SkillDraftRules.isPatched`).
    public static func isPatched(_ row: SkillDraftRow) -> Bool { row.isPatched }
}

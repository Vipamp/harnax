import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// The view model of the read-only tool list.
///
/// It has no writes at all — no switch, no delete, no form — because the stack has no write route for tools
/// (`ToolCataloging`). Everything here is the read loop: first page, filter, keyword, further pages, and a
/// failed refresh that leaves the rows the user was reading on screen.
@MainActor
public final class ToolListViewModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        case content
        /// The stack answered, and there is nothing to show. Kept apart from `loading` so an account whose
        /// code registers no tools never looks like a spinner that never finishes.
        case empty
        case failed(String)
    }

    @Published public private(set) var phase: Phase = .loading
    @Published public private(set) var items: [ToolSummary] = []
    @Published public private(set) var total = 0
    /// Shown as a banner above a list that stays on screen: a failed refresh or a failed next page must not
    /// erase the rows already being read.
    @Published public private(set) var inlineError: String?
    @Published public private(set) var canLoadMore = false
    @Published public private(set) var isAppending = false
    @Published public var keyword = "" {
        didSet { if keyword != oldValue { scheduleSearch() } }
    }

    @Published public var filter: StatusFilter = .all {
        didSet { if filter != oldValue { Task { await refresh() } } }
    }

    private let tools: any ToolCataloging
    private var pages: PagedState<ToolSummary>
    private var searchTask: Task<Void, Never>?

    /// The builtin table is small — one page of 50 shows a whole installation's tool set, and `hasMore`
    /// still covers a stack that has grown past it.
    public init(tools: any ToolCataloging, pageSize: Int = 50) {
        self.tools = tools
        pages = PagedState(pageSize: pageSize)
    }

    /// An empty result under a filter is not the same news as an account with no tools at all.
    public var isFiltered: Bool {
        !keyword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || filter != .all
    }

    public func refresh() async {
        inlineError = nil
        if items.isEmpty { phase = .loading }
        switch await tools.toolPage(keyword: keyword, status: filter.queryValue, num: 1, size: pages.pageSize) {
        case let .success(page):
            pages.replace(with: page)
            apply()
        case let .failure(error):
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
        isAppending = true
        defer { isAppending = false }
        switch await tools.toolPage(
            keyword: keyword,
            status: filter.queryValue,
            num: pages.pageNum + 1,
            size: pages.pageSize
        ) {
        case let .success(page):
            inlineError = nil
            pages.append(with: page)
            apply()
        case let .failure(error):
            // The list is still usable, so the page counter is left alone and the next scroll retries the
            // same page number.
            inlineError = ErrorMessage.text(for: error)
        }
    }

    /// Typing stops, one request goes out. The console filters its already-loaded array instead, but iOS
    /// reads the paged route — and the server matches the keyword against `name`/`display_name`/
    /// `description`, which is the same set the row shows.
    private func scheduleSearch() {
        searchTask?.cancel()
        searchTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(500))
            guard !Task.isCancelled else { return }
            await self?.refresh()
        }
    }

    private func apply() {
        items = pages.elements
        total = pages.total
        canLoadMore = pages.hasMore
        phase = pages.isEmpty ? .empty : .content
    }
}

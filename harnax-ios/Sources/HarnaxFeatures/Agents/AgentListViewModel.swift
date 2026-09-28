import Foundation
import Combine
import HarnaxCore
import HarnaxKit

@MainActor
public final class AgentListViewModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        case content
        /// The stack answered, and there is nothing to show. Kept apart from `loading` so an empty
        /// account never looks like a spinner that never finishes.
        case empty
        case failed(String)
    }

    @Published public private(set) var phase: Phase = .loading
    @Published public private(set) var items: [AgentSummary] = []
    @Published public private(set) var total = 0
    /// Shown as a banner above a list that stays on screen — a failed refresh must not erase what the
    /// user was already reading.
    @Published public private(set) var inlineError: String?
    @Published public private(set) var canLoadMore = false
    @Published public private(set) var isAppending = false

    private let agents: any AgentCataloging
    private var pages: PagedState<AgentSummary>

    public init(agents: any AgentCataloging, pageSize: Int = 20) {
        self.agents = agents
        pages = PagedState(pageSize: pageSize)
    }

    public func refresh() async {
        inlineError = nil
        if items.isEmpty { phase = .loading }
        switch await agents.page(num: 1, size: pages.pageSize) {
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
        switch await agents.page(num: pages.pageNum + 1, size: pages.pageSize) {
        case let .success(page):
            inlineError = nil
            pages.append(with: page)
            apply()
        case let .failure(error):
            // The list is still usable, so the page counter is left alone and the next scroll retries
            // the same page number.
            inlineError = ErrorMessage.text(for: error)
        }
    }

    private func apply() {
        items = pages.elements
        total = pages.total
        canLoadMore = pages.hasMore
        phase = pages.isEmpty ? .empty : .content
    }
}

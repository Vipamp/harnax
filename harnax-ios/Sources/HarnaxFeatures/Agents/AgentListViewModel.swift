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
    /// user was already reading, and a refused delete has to stay up until it has been read.
    @Published public private(set) var inlineError: String?
    @Published public private(set) var canLoadMore = false
    @Published public private(set) var isAppending = false
    /// Rows whose write has not been answered, so a switch can show progress rather than appear inert.
    @Published public private(set) var pendingIDs: Set<Int64> = []
    @Published public var keyword = "" {
        didSet { if keyword != oldValue { scheduleSearch() } }
    }

    @Published public var filter: StatusFilter = .all {
        didSet { if filter != oldValue { Task { await refresh() } } }
    }

    /// The switch answers before the server does, so a tap feels immediate on a slow link. A refused
    /// write drops the override, which snaps the row back to what the stack last said.
    @Published private(set) var statusOverrides: [Int64: Bool] = [:]

    private let agents: any AgentCataloging
    private var pages: PagedState<AgentSummary>
    private var searchTask: Task<Void, Never>?

    public init(agents: any AgentCataloging, pageSize: Int = 20) {
        self.agents = agents
        pages = PagedState(pageSize: pageSize)
    }

    public func status(of agent: AgentSummary) -> Bool {
        guard let id = agent.id else { return agent.isEnabled }
        return statusOverrides[id] ?? agent.isEnabled
    }

    /// An empty result under a filter is not the same news as an account with no agents at all.
    public var isFiltered: Bool {
        !keyword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || filter != .all
    }

    public func refresh() async {
        inlineError = nil
        if items.isEmpty { phase = .loading }
        switch await agents.page(name: keyword, status: filter.queryValue, num: 1, size: pages.pageSize) {
        case let .success(page):
            pages.replace(with: page)
            statusOverrides = [:]
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
        switch await agents.page(
            name: keyword,
            status: filter.queryValue,
            num: pages.pageNum + 1,
            size: pages.pageSize
        ) {
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

    public func setStatus(_ enabled: Bool, for agent: AgentSummary) async {
        guard let id = agent.id else { return }
        statusOverrides[id] = enabled
        pendingIDs.insert(id)
        defer { pendingIDs.remove(id) }
        if case let .failure(error) = await agents.setStatus(id: id, enabled: enabled) {
            statusOverrides[id] = nil
            inlineError = ErrorMessage.text(for: error)
        }
    }

    /// A delete is refused while teams or sessions still reference the agent, and the backend's own
    /// sentence names them — surfaced rather than swallowed
    /// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:212-254`).
    public func delete(_ agent: AgentSummary) async {
        guard let id = agent.id else { return }
        inlineError = nil
        if case let .failure(error) = await agents.delete(id: id) {
            inlineError = ErrorMessage.text(for: error)
            return
        }
        statusOverrides[id] = nil
        pages.removeRow(id: id)
        apply()
    }

    /// The console waits 500 ms after typing stops; one request per keystroke would be a queue of
    /// superseded answers.
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

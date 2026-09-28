import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// The first level of the model screen: the provider cards, and the three writes a card can do.
///
/// The shape follows `AgentListViewModel` on purpose — optimistic switch with a rollback, a banner that
/// keeps the rows it sits above, and paging that retries the same page number after a failed fetch.
///
/// Two things only this domain has:
/// - the counts on each card come from a second, owner-only route, so a readable provider can still refuse
///   to answer them (`ModelProviderServiceImpl.kt:152-161`);
/// - the connectivity test is a boolean with no measurement behind it today
///   (`ModelProviderServiceImpl.kt:163-169`), which is why the outcome is named after who answered rather
///   than after what was observed.
@MainActor
public final class ModelProviderListViewModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        case content
        /// The stack answered, and there is nothing to show. Kept apart from `loading` so an account with
        /// no providers never looks like a spinner that never finishes.
        case empty
        case failed(String)
    }

    /// What the last test answered. `passed` and `failed` are the two values the envelope can carry;
    /// `refused` is the envelope saying no at all (another tenant's provider id), and it is the one case
    /// with a server sentence attached.
    public enum TestOutcome: Equatable {
        case running
        case passed
        case failed
        case refused(String)
    }

    /// The counts are per-card and arrive after the card does, so "not yet" and "refused" are both distinct
    /// from a provider that really has zero models.
    public enum StatsState: Equatable {
        case pending
        case loaded(ModelProviderStats)
        case unavailable
    }

    @Published public private(set) var phase: Phase = .loading
    @Published public private(set) var items: [ModelProviderSummary] = []
    @Published public private(set) var total = 0
    @Published public private(set) var inlineError: String?
    @Published public private(set) var canLoadMore = false
    @Published public private(set) var isAppending = false
    @Published public private(set) var pendingIDs: Set<Int64> = []
    @Published public private(set) var stats: [Int64: StatsState] = [:]
    @Published public private(set) var tests: [Int64: TestOutcome] = [:]
    @Published public var keyword = "" {
        didSet { if keyword != oldValue { scheduleSearch() } }
    }
    @Published public var filter: StatusFilter = .all {
        didSet { if filter != oldValue { Task { await refresh() } } }
    }

    @Published private(set) var statusOverrides: [Int64: Bool] = [:]

    private let catalog: any ModelCataloging
    private var pages: PagedState<ModelProviderSummary>
    private var searchTask: Task<Void, Never>?

    /// The console's provider page is fixed at eight cards (`harnax-webui/src/pages/model/index.tsx:52`),
    /// which is also what a two-column grid fills on one screen.
    public init(catalog: any ModelCataloging, pageSize: Int = 8) {
        self.catalog = catalog
        pages = PagedState(pageSize: pageSize)
    }

    public func status(of provider: ModelProviderSummary) -> Bool {
        statusOverrides[provider.providerID] ?? provider.isEnabled
    }

    public func statsState(for provider: ModelProviderSummary) -> StatsState {
        stats[provider.providerID] ?? .pending
    }

    public func testOutcome(for provider: ModelProviderSummary) -> TestOutcome? {
        tests[provider.providerID]
    }

    /// An empty result under a filter is not the same news as an account with no providers at all.
    public var isFiltered: Bool {
        !keyword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || filter != .all
    }

    public func refresh() async {
        inlineError = nil
        if items.isEmpty { phase = .loading }
        switch await catalog.providerPage(
            name: keyword,
            type: nil,
            status: filter.queryValue,
            num: 1,
            size: pages.pageSize
        ) {
        case let .success(page):
            pages.replace(with: page)
            statusOverrides = [:]
            stats = [:]
            tests = [:]
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
        switch await catalog.providerPage(
            name: keyword,
            type: nil,
            status: filter.queryValue,
            num: pages.pageNum + 1,
            size: pages.pageSize
        ) {
        case let .success(page):
            inlineError = nil
            pages.append(with: page)
            apply()
        case let .failure(error):
            inlineError = ErrorMessage.text(for: error)
        }
    }

    /// The card asks for its counts once it is on screen, not as part of the page: the page endpoint does
    /// not carry them, and a card whose count failed still shows everything else it knows.
    public func loadStats(for provider: ModelProviderSummary) async {
        guard stats[provider.providerID] == nil || stats[provider.providerID] == .pending else { return }
        stats[provider.providerID] = .pending
        if case let .success(loaded) = await catalog.providerStats(id: provider.providerID) {
            stats[provider.providerID] = .loaded(loaded)
        } else {
            stats[provider.providerID] = .unavailable
        }
    }

    /// Optimistic, and rolled back on a refusal. Disabling a provider that still has an enabled model is a
    /// normal refusal rather than an edge case — the backend counts them first
    /// (`ModelProviderServiceImpl.kt:126-133`).
    public func setStatus(_ enabled: Bool, for provider: ModelProviderSummary) async {
        let id = provider.providerID
        statusOverrides[id] = enabled
        pendingIDs.insert(id)
        defer { pendingIDs.remove(id) }
        if case let .failure(error) = await catalog.setProviderStatus(id: id, enabled: enabled) {
            statusOverrides[id] = nil
            inlineError = ErrorMessage.text(for: error)
        }
    }

    public func delete(_ provider: ModelProviderSummary) async {
        let id = provider.providerID
        inlineError = nil
        if case let .failure(error) = await catalog.deleteProvider(id: id) {
            inlineError = ErrorMessage.text(for: error)
            return
        }
        statusOverrides[id] = nil
        stats[id] = nil
        tests[id] = nil
        pages.removeRow(id: id)
        apply()
    }

    /// The entry stays because the route exists; the answer is labelled as the server's, not as a
    /// measurement, because `connectivityTest` returns `true` without probing anything today.
    public func test(_ provider: ModelProviderSummary) async {
        let id = provider.providerID
        tests[id] = .running
        switch await catalog.testProvider(id: id) {
        case let .success(passed):
            tests[id] = passed ? .passed : .failed
        case let .failure(error):
            tests[id] = .refused(ErrorMessage.text(for: error))
        }
    }

    /// A save answered `ResultVo<Void>`, so there is no id to fold into the list — the page is refetched.
    public func saved() async {
        await refresh()
    }

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

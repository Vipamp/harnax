import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// The second level: the models under one provider.
///
/// Same paging and optimistic-write discipline as `ModelProviderListViewModel`. What is different is the
/// filter: besides the keyword and the status, this level filters on capability tags, and the backend takes
/// them as one comma-joined string that it splits again
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ModelServiceImpl.kt:47-52`).
///
/// The console pulls a hundred rows and shows no pager (`ModelListTable.tsx:57-88`); iOS pages instead, and
/// the spec calls that out deliberately (`harnax-ios/specs/04-context-domains.md:62`).
@MainActor
public final class ModelListViewModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        case content
        case empty
        case failed(String)
    }

    @Published public private(set) var phase: Phase = .loading
    @Published public private(set) var items: [ModelSummary] = []
    @Published public private(set) var total = 0
    @Published public private(set) var inlineError: String?
    @Published public private(set) var canLoadMore = false
    @Published public private(set) var isAppending = false
    @Published public private(set) var pendingIDs: Set<Int64> = []
    /// Selected capability tags, in `ModelCapability.allCases` order on the way out so the same two
    /// selections always produce the same URL.
    @Published public private(set) var selectedTags: [ModelCapability] = []
    @Published public var keyword = "" {
        didSet { if keyword != oldValue { scheduleSearch() } }
    }
    @Published public var filter: StatusFilter = .all {
        didSet { if filter != oldValue { Task { await refresh() } } }
    }

    @Published private(set) var statusOverrides: [Int64: Bool] = [:]

    private let providerID: Int64
    private let catalog: any ModelCataloging
    private var pages: PagedState<ModelSummary>
    private var searchTask: Task<Void, Never>?

    public init(providerID: Int64, catalog: any ModelCataloging, pageSize: Int = 20) {
        self.providerID = providerID
        self.catalog = catalog
        pages = PagedState(pageSize: pageSize)
    }

    public func status(of model: ModelSummary) -> Bool {
        guard let id = model.id else { return model.isEnabled }
        return statusOverrides[id] ?? model.isEnabled
    }

    /// An empty result under a filter is not the same news as a provider with nothing under it.
    public var isFiltered: Bool {
        !keyword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            || filter != .all
            || !selectedTags.isEmpty
    }

    public func toggle(_ tag: ModelCapability) async {
        if let index = selectedTags.firstIndex(of: tag) {
            selectedTags.remove(at: index)
        } else {
            selectedTags.append(tag)
        }
        selectedTags = ModelCapability.allCases.filter { selectedTags.contains($0) }
        await refresh()
    }

    public func refresh() async {
        inlineError = nil
        if items.isEmpty { phase = .loading }
        switch await catalog.modelPage(
            providerID: providerID,
            name: keyword,
            status: filter.queryValue,
            tags: selectedTags.map(\.rawValue),
            num: 1,
            size: pages.pageSize
        ) {
        case let .success(page):
            pages.replace(with: page)
            statusOverrides = statusOverrides.filter { pendingIDs.contains($0.key) }
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
        switch await catalog.modelPage(
            providerID: providerID,
            name: keyword,
            status: filter.queryValue,
            tags: selectedTags.map(\.rawValue),
            num: pages.pageNum + 1,
            size: pages.pageSize
        ) {
        case let .success(page):
            inlineError = nil
            pages.append(with: page)
            apply()
        case let .failure(error):
            // The rows on screen stay, the page counter does not move, and the next scroll retries it.
            inlineError = ErrorMessage.text(for: error)
        }
    }

    /// Enabling a model is refused while its own provider is stopped
    /// (`ModelServiceImpl.kt:186-198`), so the switch has to be able to snap back.
    public func setStatus(_ enabled: Bool, for model: ModelSummary) async {
        guard let id = model.id else { return }
        statusOverrides[id] = enabled
        pendingIDs.insert(id)
        defer { pendingIDs.remove(id) }
        if case let .failure(error) = await catalog.setModelStatus(id: id, enabled: enabled) {
            statusOverrides[id] = nil
            inlineError = ErrorMessage.text(for: error)
        }
    }

    /// A refusal is the expected answer while agents, teams or sessions still point at the model, and the
    /// backend's sentence names the counts (`ModelServiceImpl.kt:200-221`). The row stays where it was so
    /// the message can be read against it.
    public func delete(_ model: ModelSummary) async {
        guard let id = model.id else { return }
        inlineError = nil
        if case let .failure(error) = await catalog.deleteModel(id: id) {
            inlineError = ErrorMessage.text(for: error)
            return
        }
        statusOverrides[id] = nil
        pages.removeRow(id: id)
        apply()
    }

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

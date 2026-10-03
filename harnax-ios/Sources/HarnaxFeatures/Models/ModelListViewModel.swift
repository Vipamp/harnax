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
    /// The model type, as the console's filter bar offers it
    /// (`harnax-webui/src/pages/model/index.tsx:525-535`). A choice rather than typing, so it takes effect on
    /// the spot the way the status picker does.
    @Published public var typeFilter: ModelType? = nil {
        didSet { if typeFilter != oldValue { Task { await refresh() } } }
    }
    /// The two price bounds, kept as text because a box holding a word or a stopped exponent is not a number
    /// and is not worth complaining about on every keystroke — the form's own price field does the same
    /// (`ModelForm.swift:26-28`). Typed, so they ride the keyword's debounce.
    @Published public var minPriceText = "" {
        didSet { if minPriceText != oldValue { scheduleSearch() } }
    }
    @Published public var maxPriceText = "" {
        didSet { if maxPriceText != oldValue { scheduleSearch() } }
    }

    @Published private(set) var statusOverrides: [Int64: Bool] = [:]

    private let providerID: Int64
    private let catalog: any ModelCataloging
    private var pages: PagedState<ModelSummary>
    private var searchTask: Task<Void, Never>?
    /// Refresh identity and the one-in-flight rule, exactly as `AgentListViewModel` documents them: the
    /// last answer to arrive must not be the one that wins the rows, the page counter and the total.
    private var refreshGeneration = 0
    private var isRefreshing = false
    private var rerunRequested = false
    /// An append asked for while a refresh is on the wire is remembered, not dropped — see
    /// `AgentListViewModel`.
    private var appendRequested = false

    public init(providerID: Int64, catalog: any ModelCataloging, pageSize: Int = 20) {
        self.providerID = providerID
        self.catalog = catalog
        pages = PagedState(pageSize: pageSize)
    }

    public func status(of model: ModelSummary) -> Bool {
        guard let id = model.id else { return model.isEnabled }
        return statusOverrides[id] ?? model.isEnabled
    }

    /// The lower bound as the server would take it, or `nil` for "not a number yet". Text that parses to
    /// `nan` or `inf` is no bound either — `Double("nan")` does parse, and it would filter the list to
    /// nothing while the box still showed a word.
    public var minPrice: Double? { Self.bound(minPriceText) }
    public var maxPrice: Double? { Self.bound(maxPriceText) }

    /// The same two tolerances the model form's price field has: a comma for the decimal separator, and a
    /// blank rather than a half-number read as no value (`ModelForm.swift:97-102`).
    static func bound(_ text: String) -> Double? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        guard let value = Double(trimmed) ?? Double(trimmed.replacingOccurrences(of: ",", with: ".")),
              value.isFinite
        else { return nil }
        return value
    }

    /// An empty result under a filter is not the same news as a provider with nothing under it. Every filter
    /// this screen owns counts here, and only as it actually goes on the wire: a box holding text that is not
    /// a number sent no bound, so the list is not filtered.
    public var isFiltered: Bool {
        !keyword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            || filter != .all
            || typeFilter != nil
            || !selectedTags.isEmpty
            || minPrice != nil
            || maxPrice != nil
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
        switch await catalog.modelPage(
            providerID: providerID,
            name: keyword,
            modelType: typeFilter?.rawValue,
            status: filter.queryValue,
            tags: selectedTags.map(\.rawValue),
            minPrice: minPrice,
            maxPrice: maxPrice,
            num: 1,
            size: pages.pageSize
        ) {
        case let .success(page):
            guard generation == refreshGeneration else { return }
            pages.replace(with: page)
            statusOverrides = statusOverrides.filter { pendingIDs.contains($0.key) }
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

    /// The tail read, under the identity of the query that was on screen when the scroll happened, as
    /// `AgentListViewModel` documents it: a retired answer may take none of the rows, the total or the page
    /// counter with it.
    private func runAppend() async {
        let generation = refreshGeneration
        isAppending = true
        defer { isAppending = false }
        switch await catalog.modelPage(
            providerID: providerID,
            name: keyword,
            modelType: typeFilter?.rawValue,
            status: filter.queryValue,
            tags: selectedTags.map(\.rawValue),
            minPrice: minPrice,
            maxPrice: maxPrice,
            num: pages.pageNum + 1,
            size: pages.pageSize
        ) {
        case let .success(page):
            guard generation == refreshGeneration else { return }
            inlineError = nil
            pages.append(with: page)
            apply()
        case let .failure(error):
            guard generation == refreshGeneration else { return }
            // The rows on screen stay, the page counter does not move, and the next scroll retries it.
            inlineError = ErrorMessage.text(for: error)
        }
    }

    private func reissueAppend() async {
        guard appendRequested, canLoadMore, !isAppending else { return }
        appendRequested = false
        await runAppend()
    }

    /// Enabling a model is refused while its own provider is stopped
    /// (`ModelServiceImpl.kt:186-198`), so the switch has to be able to snap back.
    public func setStatus(_ enabled: Bool, for model: ModelSummary) async {
        guard let id = model.id else { return }
        guard !pendingIDs.contains(id) else { return }
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

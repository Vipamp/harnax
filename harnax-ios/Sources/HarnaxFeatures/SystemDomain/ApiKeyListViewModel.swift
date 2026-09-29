import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// S4 — the API Key list: keyword and status filters, paging, the four writes, and the one-time key that
/// create and regenerate hand back.
///
/// Shaped like `TeamListViewModel`; two things this domain adds and one it drops:
///
/// - the page really does take a status filter (`ApiKeyController.kt:39`), so the shared `StatusFilter`
///   drives `enabled` here;
/// - the raw key. `publishedKey` is the only place a `rawKey` value exists on this screen, it is filled by
///   no read path, and dismissing it destroys it — the backend stores a SHA-256 digest and cannot show the
///   key again (`ApiKeyServiceImpl.kt:80-82`).
/// - no related-session pre-flight: a key binds nothing in the database, so delete asks the server and
///   takes its answer.
@MainActor
public final class ApiKeyListViewModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        case content
        case empty
        case failed(String)
    }

    @Published public private(set) var phase: Phase = .loading
    @Published public private(set) var items: [ApiKeySummary] = []
    @Published public private(set) var total = 0
    @Published public private(set) var inlineError: String?
    @Published public private(set) var canLoadMore = false
    @Published public private(set) var isAppending = false
    @Published public private(set) var pendingIDs: Set<Int64> = []
    @Published public private(set) var deleteTarget: ApiKeySummary?
    /// A key the stack has just shown for the first and last time. `nil` means nothing is on screen, and
    /// nothing else in this type can put a value here.
    @Published public private(set) var publishedKey: ApiKeyCreatedSummary?

    @Published public var keyword = "" {
        didSet { if keyword != oldValue { scheduleSearch() } }
    }
    @Published public var filter: StatusFilter = .all {
        didSet { if filter != oldValue { Task { await refresh() } } }
    }

    @Published private(set) var statusOverrides: [Int64: Bool] = [:]

    private let catalog: any ApiKeyCataloging
    private var pages: PagedState<ApiKeySummary>
    private var searchTask: Task<Void, Never>?

    public init(catalog: any ApiKeyCataloging, pageSize: Int = 20) {
        self.catalog = catalog
        pages = PagedState(pageSize: pageSize)
    }

    public func status(of row: ApiKeySummary) -> Bool {
        guard let id = row.id else { return row.isEnabled }
        return statusOverrides[id] ?? row.isEnabled
    }

    public var isFiltered: Bool {
        !keyword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || filter != .all
    }

    public func refresh() async {
        inlineError = nil
        if items.isEmpty { phase = .loading }
        switch await catalog.apiKeyPage(keyword: keyword, enabled: filter.queryValue, num: 1, size: pages.pageSize) {
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
        switch await catalog.apiKeyPage(
            keyword: keyword,
            enabled: filter.queryValue,
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

    /// A protected row is refused here with the server's own “PERMANENT API Key cannot be disabled”
    /// (`ApiKeyServiceImpl.kt:151-155`); the switch snaps back and says why. No client-side whitelist tries
    /// to predict that, because the list carries no `keyType` to predict from.
    public func setStatus(_ enabled: Bool, for row: ApiKeySummary) async {
        guard let id = row.id else { return }
        statusOverrides[id] = enabled
        pendingIDs.insert(id)
        defer { pendingIDs.remove(id) }
        if case let .failure(error) = await catalog.setApiKeyStatus(id: id, enabled: enabled) {
            statusOverrides[id] = nil
            inlineError = ErrorMessage.text(for: error)
        }
    }

    public func beginDelete(_ row: ApiKeySummary) {
        guard row.id != nil else { return }
        inlineError = nil
        deleteTarget = row
    }

    public func cancelDelete() {
        deleteTarget = nil
    }

    public func confirmDelete() async {
        guard let row = deleteTarget, let id = row.id else { return }
        deleteTarget = nil
        if case let .failure(error) = await catalog.deleteApiKey(id: id) {
            inlineError = ErrorMessage.text(for: error)
            return
        }
        statusOverrides[id] = nil
        pages.removeRow(id: id)
        apply()
    }

    /// Rotates the secret. Refused rows answer with the server's sentence and publish no key.
    ///
    /// Unlike update, toggle and delete this route runs no protected-type check at all
    /// (`ApiKeyServiceImpl.kt:162-183`), which is deliberate: rotating is the way out of a permanent key
    /// that has leaked. The confirmation the view opens says so, because replacing a PERMANENT or SYSTEM
    /// key's secret invalidates every caller already holding it
    /// (`harnax-ios/specs/03-system-domain.md:351`).
    public func regenerate(_ row: ApiKeySummary) async {
        guard let id = row.id else { return }
        pendingIDs.insert(id)
        defer { pendingIDs.remove(id) }
        switch await catalog.regenerateApiKey(id: id) {
        case let .success(created):
            inlineError = nil
            // The row's own prefix changed, so the list is re-read rather than patched.
            publishedKey = created
            await refresh()
        case let .failure(error):
            inlineError = ErrorMessage.text(for: error)
        }
    }

    /// Closing the one-time screen destroys the key. It is not kept in the row model, in the page state, or
    /// anywhere else on this type — `harnax-ios/specs/03-system-domain.md:124` is the rule, and this is the
    /// only place it can be enforced.
    public func dismissPublishedKey() {
        publishedKey = nil
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

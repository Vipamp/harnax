import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// S3 — the environment-variable list: keyword search, paging, and the four writes the console offers.
///
/// Shaped exactly like `TeamListViewModel`, with one difference that comes from the backend: this page has
/// no status filter to send (`EnvVariableController.kt:27-31` takes `keyword` alone), so the shared
/// `StatusFilter` is not used here — a client-side filter over a paginated list would hide rows the server
/// counted, and a second request per keystroke to fake one would be worse.
@MainActor
public final class EnvVarListViewModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        case content
        /// The stack answered, and this account typed no variables. Kept apart from `loading` so an empty
        /// account never looks like a spinner that never finishes.
        case empty
        case failed(String)
    }

    @Published public private(set) var phase: Phase = .loading
    @Published public private(set) var items: [EnvVarSummary] = []
    @Published public private(set) var total = 0
    /// Shown as a banner above a list that stays on screen: a failed refresh must not erase what the user
    /// was reading, and a refused delete or toggle has to stay up until it has been read.
    @Published public private(set) var inlineError: String?
    @Published public private(set) var canLoadMore = false
    @Published public private(set) var isAppending = false
    @Published public private(set) var pendingIDs: Set<Int64> = []
    /// The row whose delete confirmation is open. No pre-flight stands behind it: this domain refuses at
    /// the delete itself, naming the binding agents in the message
    /// (`EnvVariableServiceImpl.kt:180-208`).
    @Published public private(set) var deleteTarget: EnvVarSummary?
    @Published public var keyword = "" {
        didSet { if keyword != oldValue { scheduleSearch() } }
    }

    @Published private(set) var statusOverrides: [Int64: Bool] = [:]

    private let catalog: any EnvVarCataloging
    private var pages: PagedState<EnvVarSummary>
    private var searchTask: Task<Void, Never>?

    public init(catalog: any EnvVarCataloging, pageSize: Int = 20) {
        self.catalog = catalog
        pages = PagedState(pageSize: pageSize)
    }

    public func status(of row: EnvVarSummary) -> Bool {
        guard let id = row.id else { return row.isEnabled }
        return statusOverrides[id] ?? row.isEnabled
    }

    /// An empty result under a keyword is not the same news as an account with no variables at all.
    public var isFiltered: Bool {
        !keyword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    public func refresh() async {
        inlineError = nil
        if items.isEmpty { phase = .loading }
        switch await catalog.envVarPage(keyword: keyword, num: 1, size: pages.pageSize) {
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
        switch await catalog.envVarPage(
            keyword: keyword,
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

    /// Optimistic, because the switch is the row's own state and the answer is a bare `ResultVo<Void>`:
    /// the flag is put back if the stack refuses.
    ///
    /// Switching *off* is the branch that gets refused — a variable an agent still binds to is protected
    /// exactly like a delete (`EnvVariableServiceImpl.kt:210-219`) — so the refusal is a normal outcome
    /// and its sentence is shown as it arrived.
    public func setStatus(_ enabled: Bool, for row: EnvVarSummary) async {
        guard let id = row.id else { return }
        statusOverrides[id] = enabled
        pendingIDs.insert(id)
        defer { pendingIDs.remove(id) }
        if case let .failure(error) = await catalog.setEnvVarStatus(id: id, enabled: enabled) {
            statusOverrides[id] = nil
            inlineError = ErrorMessage.text(for: error)
        }
    }

    public func beginDelete(_ row: EnvVarSummary) {
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
        if case let .failure(error) = await catalog.deleteEnvVar(id: id) {
            // The row stays: the server kept it, and its message is the only explanation the user gets.
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

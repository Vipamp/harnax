import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// The transport filter the list offers.
///
/// stdio stays in the menu even though nothing may be created with it: the deployment policy refuses the
/// transport at runtime, not the schema, so a legacy row is still on the page and has to be findable
/// (`harnax-webui/src/pages/mcp/index.tsx:492-495`).
public enum McpTypeFilter: String, CaseIterable, Identifiable {
    case all
    case stdio
    case sse
    case streamablehttp

    public var id: String { rawValue }

    /// `all` uses the word the other two context lists already use for "no filter".
    public var titleKey: String {
        switch self {
        case .all: "state.filter.all"
        case .stdio: "mcp.type.stdio"
        case .sse: "mcp.type.sse"
        case .streamablehttp: "mcp.type.streamablehttp"
        }
    }

    /// Omitted rather than empty: `type` is matched as an exact string
    /// (`McpServerController.kt:32-57`).
    public var queryValue: String? {
        self == .all ? nil : rawValue
    }
}

/// M1 — the MCP card list: the same paged read the other context domains have, plus the two writes that
/// are specific to this domain (a connectivity probe and a delete that says who is bound first).
///
/// `GET /api/admin/mcp/page` carries the endpoint, the transport, the auth type and the masked config
/// entries, so the card needs no second read (`McpServerResponse.kt:14-44`).
@MainActor
public final class McpListViewModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        case content
        /// The stack answered, and there is nothing to show. Kept apart from `loading` so an account with
        /// no servers never looks like a spinner that never finishes.
        case empty
        case failed(String)
    }

    /// The dialog the row's delete button opens after the related-agent read came back.
    ///
    /// `boundAgents` is the blast radius, not a blocker: the backend deletes the row and cascades the
    /// bindings away (`McpServerServiceImpl.kt:320-337`), so this list is the sentence "these agents are
    /// about to lose a server" and never "the delete will be refused".
    public struct DeleteTarget: Equatable {
        public let server: McpServerRow
        public let boundAgents: [RelatedAgent]
    }

    @Published public private(set) var phase: Phase = .loading
    @Published public private(set) var items: [McpServerRow] = []
    @Published public private(set) var total = 0
    /// Shown as a banner above a list that stays on screen: a failed refresh must not erase what the user
    /// was reading, and a refused delete or probe has to stay up until it has been read.
    @Published public private(set) var inlineError: String?
    @Published public private(set) var canLoadMore = false
    @Published public private(set) var isAppending = false
    /// Rows whose write has not been answered, so a switch can show progress rather than appear inert.
    @Published public private(set) var pendingIDs: Set<Int64> = []
    @Published public private(set) var deleteTarget: DeleteTarget?
    /// The related-agent read is still on the wire, so the row must not open a second one.
    @Published public private(set) var isCheckingDelete = false
    /// Last probe per row, kept until the operator starts another one. A probe is a one-off question with
    /// an answer worth reading, so it survives a refresh of the list around it.
    @Published public private(set) var testOutcomes: [Int64: McpTestOutcome] = [:]
    @Published public private(set) var testingIDs: Set<Int64> = []
    @Published public var keyword = "" {
        didSet { if keyword != oldValue { scheduleSearch() } }
    }
    @Published public var filter: StatusFilter = .all {
        didSet { if filter != oldValue { Task { await refresh() } } }
    }
    @Published public var typeFilter: McpTypeFilter = .all {
        didSet { if typeFilter != oldValue { Task { await refresh() } } }
    }

    @Published private(set) var statusOverrides: [Int64: Bool] = [:]

    /// The console races the probe against its own 15 s deadline
    /// (`harnax-webui/src/pages/mcp/index.tsx:381-438`); injectable so a test does not have to wait.
    public let testTimeout: TimeInterval

    private let mcp: any McpCataloging
    private var pages: PagedState<McpServerRow>
    private var searchTask: Task<Void, Never>?

    public init(mcp: any McpCataloging, pageSize: Int = 20, testTimeout: TimeInterval = 15) {
        self.mcp = mcp
        self.testTimeout = testTimeout
        pages = PagedState(pageSize: pageSize)
    }

    public func status(of server: McpServerRow) -> Bool {
        guard let id = server.id else { return server.isEnabled }
        return statusOverrides[id] ?? server.isEnabled
    }

    /// An empty result under a filter is not the same news as an account with no servers at all.
    public var isFiltered: Bool {
        !keyword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            || filter != .all
            || typeFilter != .all
    }

    public func refresh() async {
        inlineError = nil
        if items.isEmpty { phase = .loading }
        switch await mcp.mcpPage(
            keyword: keyword,
            status: filter.queryValue,
            type: typeFilter.queryValue,
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
        switch await mcp.mcpPage(
            keyword: keyword,
            status: filter.queryValue,
            type: typeFilter.queryValue,
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

    public func setStatus(_ enabled: Bool, for server: McpServerRow) async {
        guard let id = server.id else { return }
        statusOverrides[id] = enabled
        pendingIDs.insert(id)
        defer { pendingIDs.remove(id) }
        if case let .failure(error) = await mcp.setMcpStatus(id: id, enabled: enabled) {
            statusOverrides[id] = nil
            inlineError = ErrorMessage.text(for: error)
            return
        }
        // The old verdict is about the old state of the row: testing an enabled server and then switching
        // it off leaves an answer the operator would read as current.
        testOutcomes[id] = nil
    }

    /// Ask the server whether it can be reached, and keep all three answers apart.
    ///
    /// A `false` from the envelope and a refusal are the same shape on the wire (`code != 200` plus a
    /// `message`), and the local deadline is the third case: the request is still running, so nothing here
    /// claims the server is down (`McpServerServiceImpl.kt:441-464`).
    public func runTest(for server: McpServerRow) async {
        guard let id = server.id else { return }
        inlineError = nil
        testingIDs.insert(id)
        testOutcomes[id] = nil
        defer { testingIDs.remove(id) }
        let catalog = mcp
        testOutcomes[id] = await McpConnectivity.probe(timeout: testTimeout) {
            await catalog.testMcpConnectivity(id: id)
        }
    }

    public func dismissTest(for server: McpServerRow) {
        guard let id = server.id else { return }
        testOutcomes[id] = nil
    }

    /// Ask first, confirm second. An unreadable related list is its own outcome: until admin answers, no
    /// message on this screen can claim which agents would lose the server — so no dialog opens at all,
    /// and the row's delete button stays usable for a retry
    /// (`harnax-webui/src/pages/mcp/index.tsx:314-358`).
    public func requestDelete(_ server: McpServerRow) async {
        guard let id = server.id else { return }
        inlineError = nil
        isCheckingDelete = true
        defer { isCheckingDelete = false }
        switch await mcp.mcpRelatedAgents(id: id) {
        case let .success(agents):
            deleteTarget = DeleteTarget(server: server, boundAgents: agents)
        case .failure:
            inlineError = hx("mcp.delete.loadFailed")
        }
    }

    public func cancelDelete() {
        deleteTarget = nil
    }

    public func confirmDelete() async {
        guard let server = deleteTarget?.server, let id = server.id else { return }
        deleteTarget = nil
        if case let .failure(error) = await mcp.deleteMCPServer(id: id) {
            // `deleteMCPServer` is a logical delete with no relation gate, so a failure here is
            // authentication or a row that is already gone; either way the backend's own sentence is the
            // honest one.
            inlineError = ErrorMessage.text(for: error)
            return
        }
        statusOverrides[id] = nil
        testOutcomes[id] = nil
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

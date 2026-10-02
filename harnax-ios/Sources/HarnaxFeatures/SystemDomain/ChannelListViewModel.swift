import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// E1 — the channel list: keyword, type and status filters, paging, the four writes, and the sandbox light
/// that arrives in a second request.
///
/// Shaped like `ApiKeyListViewModel`, with the one difference this domain forces: the row's status column is
/// not on the channel API at all. The sandbox light comes from the *runtime*'s bulk workspace lookup keyed by
/// `sessionId` (`harnax-webui/src/pages/channel/index.tsx:111-128`), so it is loaded after each page,
/// overlaid onto the rows, and left `unknown` when it fails. The web console swallows that failure outright;
/// this side keeps a distinct state instead, because "no answer" and "stopped" read very differently on a
/// row that claims to be running.
@MainActor
public final class ChannelListViewModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        case content
        case empty
        case failed(String)
    }

    @Published public private(set) var phase: Phase = .loading
    @Published public private(set) var items: [ChannelSummary] = []
    @Published public private(set) var total = 0
    @Published public private(set) var inlineError: String?
    @Published public private(set) var canLoadMore = false
    @Published public private(set) var isAppending = false
    @Published public private(set) var pendingIDs: Set<Int64> = []
    /// Deleting a channel releases its runtime first, and a refused release keeps the row
    /// (`ChannelServiceImpl.kt:163-169`). So the row stays on screen with the server's sentence attached.
    @Published public private(set) var deleteTarget: ChannelSummary?
    /// The row whose QR sheet is open. Set by the list rather than by the form, because scanning is a
    /// credential path rather than a field edit.
    @Published public var scanTarget: ChannelSummary?

    @Published public var keyword = "" {
        didSet { if keyword != oldValue { scheduleSearch() } }
    }
    @Published public var filter: StatusFilter = .all {
        didSet { if filter != oldValue { Task { await refresh() } } }
    }
    @Published public var typeFilter: ChannelTypeFilter = .all {
        didSet { if typeFilter != oldValue { Task { await refresh() } } }
    }

    @Published private(set) var statusOverrides: [Int64: Bool] = [:]
    /// Keyed by `sessionId`, the runtime's own address for the sandbox. Kept across pages so a row already
    /// answered does not flicker back to `unknown` when the next page's lookup is in flight.
    @Published private(set) var sandboxStates: [String: SandboxState] = [:]

    private let catalog: any ChannelCataloging
    private var pages: PagedState<ChannelSummary>
    private var searchTask: Task<Void, Never>?
    /// Refresh identity and the one-in-flight rule, exactly as `AgentListViewModel` documents them: the
    /// last answer to arrive must not be the one that wins the rows, the page counter and the total.
    private var refreshGeneration = 0
    private var isRefreshing = false
    private var rerunRequested = false
    /// An append asked for while a refresh is on the wire is remembered, not dropped — see
    /// `AgentListViewModel`.
    private var appendRequested = false
    /// Bumped by every page load so a slow sandbox answer cannot overwrite a newer page's rows.
    private var sandboxGeneration = 0

    public init(catalog: any ChannelCataloging, pageSize: Int = 20) {
        self.catalog = catalog
        pages = PagedState(pageSize: pageSize)
    }

    public func status(of row: ChannelSummary) -> Bool {
        guard let id = row.id else { return row.isRunning }
        return statusOverrides[id] ?? row.isRunning
    }

    public func sandboxStatus(of row: ChannelSummary) -> SandboxStatus {
        SandboxStatus(from: row.sessionId.flatMap { sandboxStates[$0] })
    }

    /// What this row may claim about its sandbox.
    ///
    /// Three states and not one: `sandboxStatus(of:)` cannot tell "there is no session to ask about" from
    /// "the runtime has not answered", and the row used to wear the same warning capsule for both.
    public func sandboxLight(of row: ChannelSummary) -> ChannelSandboxLight {
        ChannelSandboxLight(sessionId: row.sessionId, status: sandboxStatus(of: row))
    }

    public var isFiltered: Bool {
        !keyword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            || filter != .all
            || typeFilter != .all
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
        if items.isEmpty { phase = .loading }
        switch await catalog.channelPage(
            keyword: keyword,
            type: typeFilter.code,
            status: filter.queryValue,
            num: 1,
            size: pages.pageSize
        ) {
        case let .success(page):
            guard generation == refreshGeneration else { return }
            pages.replace(with: page)
            statusOverrides = statusOverrides.filter { pendingIDs.contains($0.key) }
            apply()
            await loadSandboxStatuses()
            await reissueAppend()
        case let .failure(error):
            guard generation == refreshGeneration else { return }
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
    /// `AgentListViewModel` documents it. The guard sits in front of the sandbox read too: a retired page must
    /// not so much as ask the runtime about rows that never landed here.
    private func runAppend() async {
        let generation = refreshGeneration
        isAppending = true
        defer { isAppending = false }
        switch await catalog.channelPage(
            keyword: keyword,
            type: typeFilter.code,
            status: filter.queryValue,
            num: pages.pageNum + 1,
            size: pages.pageSize
        ) {
        case let .success(page):
            guard generation == refreshGeneration else { return }
            inlineError = nil
            pages.append(with: page)
            apply()
            await loadSandboxStatuses()
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

    /// Optimistic: the toggle answers `ResultVo<Void>` and no pre-flight runs server-side
    /// (`ChannelServiceImpl.kt:133-146`), so the flag is the row's own state until the answer says otherwise.
    ///
    /// Worth stating plainly for whoever reads this expecting a stop: `status` only decides whether the
    /// console lists the channel. It does not disconnect a live listener.
    public func setStatus(_ running: Bool, for row: ChannelSummary) async {
        guard let id = row.id else { return }
        guard !pendingIDs.contains(id) else { return }
        statusOverrides[id] = running
        pendingIDs.insert(id)
        defer { pendingIDs.remove(id) }
        if case let .failure(error) = await catalog.setChannelStatus(id: id, running: running) {
            statusOverrides[id] = nil
            inlineError = ErrorMessage.text(for: error)
        }
    }

    public func beginDelete(_ row: ChannelSummary) {
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
        if case let .failure(error) = await catalog.deleteChannel(id: id) {
            inlineError = ErrorMessage.text(for: error)
            return
        }
        statusOverrides[id] = nil
        if let sessionId = row.sessionId { sandboxStates[sessionId] = nil }
        pages.removeRow(id: id)
        apply()
    }

    /// The second-order read. No session ids means no request — the server refuses a blank `sessionIds`
    /// (`AgentProxyController.kt:218-221`), and the console only asks for the rows that have one
    /// (`harnax-webui/src/pages/channel/index.tsx:112-116`).
    private func loadSandboxStatuses() async {
        let ids = pages.elements.compactMap(\.sessionId).filter { !$0.isEmpty }
        guard !ids.isEmpty else { return }
        sandboxGeneration += 1
        let generation = sandboxGeneration
        guard case let .success(map) = await catalog.sandboxStatuses(sessionIds: ids) else { return }
        guard generation == sandboxGeneration else { return }
        for (key, state) in map.states { sandboxStates[key] = state }
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

/// What a channel row is allowed to say about its sandbox.
///
/// The web console splits this three ways and iOS now follows
/// (`harnax-webui/src/pages/channel/index.tsx:317-327`): no `sessionId` prints a bare `-`, a session the
/// status map does not answer for prints a default-coloured `-`, and only a real answer gets a coloured tag.
/// The two "no answer" branches used to be one `.unknown` here, and the row painted it warning — so every
/// session-less channel, which is a normal configuration and not a fault, wore the same capsule as a
/// sandbox the runtime refused to describe.
///
/// The status lookup itself is unchanged: `SandboxStatus` is still the runtime's three-value answer, and
/// this type is what a *row* makes of it given whether it even has a session to look up.
public enum ChannelSandboxLight: Equatable, Sendable {
    /// No session id: there is nothing to ask the runtime about, so the row shows no capsule at all.
    ///
    /// Not a `-` in a pill: an empty capsule on this screen reads as a rendering failure rather than as a
    /// deliberate blank (`ChannelListView.swift`'s own comment on the type column).
    case noSession
    /// A session exists and the bulk lookup has not answered — in flight, refused, or silent about this id.
    case unanswered
    case running
    case idle

    public init(sessionId: String?, status: SandboxStatus) {
        guard hxPresented(sessionId) != nil else {
            self = .noSession
            return
        }
        switch status {
        case .running: self = .running
        case .idle: self = .idle
        case .unknown: self = .unanswered
        }
    }

    /// `nil` is the row's instruction to draw nothing, which is why the capsule is conditional rather than
    /// always present with some fallback word.
    public var labelKey: String? {
        switch self {
        case .noSession: nil
        case .unanswered: "channel.sandbox.unknown"
        case .running: "channel.sandbox.running"
        case .idle: "channel.sandbox.idle"
        }
    }

    /// Neutral for "asked, nothing came back": the console gives that branch its default tag, and a warning
    /// slot on a row that merely has no session yet tells the operator something is broken when nothing is.
    public var tone: PaletteSlot? {
        switch self {
        case .noSession: nil
        case .unanswered: .textTertiary
        case .running: .success
        case .idle: .textTertiary
        }
    }

    public var showsCapsule: Bool { labelKey != nil }
}

/// The type menu's options. `all` sends no `type` parameter at all; a picked type sends its exact lower-case
/// code because `ChannelType.fromCode` is case-sensitive (`ChannelServiceImpl.kt:355-366`).
public enum ChannelTypeFilter: Hashable, Identifiable, Sendable {
    case all
    case type(ChannelType)

    public static let allCases: [ChannelTypeFilter] = [.all] + ChannelType.allCases.map { .type($0) }

    public var id: String {
        switch self {
        case .all: return "all"
        case let .type(value): return value.rawValue
        }
    }

    public var titleKey: String {
        switch self {
        case .all: return "state.filter.all"
        case let .type(value): return value.titleKey
        }
    }

    public var code: String? {
        switch self {
        case .all: nil
        case let .type(value): value.rawValue
        }
    }
}

import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// D4 — the CLI page: a read-only catalogue of registered packages plus the one switch that takes a package
/// out of circulation.
///
/// The switch is the whole write surface, and admin does not refuse it while agents still bind the CLI
/// (design D9). That moves the judgement here: the blast radius has to be read and shown *before* the flip,
/// which is why `requestStatus` is a state machine rather than a call to the endpoint
/// (`harnax-webui/src/pages/cli/index.tsx:114-172`).
@MainActor
public final class CliListViewModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        case content
        /// The stack answered, and there is nothing registered. Kept apart from `loading` so an installation
        /// with no packages never looks like a spinner that never finishes.
        case empty
        case failed(String)
    }

    /// The confirmation a disable opens once admin has answered. `names` is resolved at read time so the
    /// dialog cannot render a half sentence if the language changes while it is up.
    public struct DisableTarget: Equatable {
        public let cli: CliSummary
        public let agents: [RelatedAgent]
        public let names: String

        public var count: Int { agents.count }
    }

    /// How many bound agents the confirm dialog names. The console caps at five and leaves the rest to the
    /// count in front of it (`harnax-webui/src/pages/cli/index.tsx:158-166`).
    static let nameLimit = 5

    @Published public private(set) var phase: Phase = .loading
    @Published public private(set) var items: [CliSummary] = []
    @Published public private(set) var total = 0
    /// Shown as a banner above a list that stays on screen: a failed refresh must not erase what the user
    /// was reading, and a refused switch has to stay up until it has been read.
    @Published public private(set) var inlineError: String?
    @Published public private(set) var canLoadMore = false
    @Published public private(set) var isAppending = false
    @Published public private(set) var pendingIDs: Set<Int64> = []
    @Published public private(set) var disableTarget: DisableTarget?
    /// The related-agents read is on the wire, so the row shows progress instead of looking inert.
    @Published public private(set) var isCheckingStatus = false
    /// A switch that succeeded while agents still bind the package. The view turns it into the shared
    /// refresh panel, exactly where the console opens `AgentRefreshModal`
    /// (`harnax-webui/src/pages/cli/index.tsx:145-148`).
    @Published public var refreshOffer: CliSummary?
    @Published public var keyword = "" {
        didSet { if keyword != oldValue { scheduleSearch() } }
    }
    @Published public var filter: StatusFilter = .all {
        didSet { if filter != oldValue { Task { await refresh() } } }
    }

    /// The switch answers before the server does. A refused write drops the override, which snaps the row
    /// back to what the stack last said — the console reloads the page instead, and this is the same
    /// outcome without the second round trip.
    @Published private(set) var statusOverrides: [Int64: Bool] = [:]

    private let clis: any CliCataloging
    private var pages: PagedState<CliSummary>
    private var searchTask: Task<Void, Never>?

    public init(clis: any CliCataloging, pageSize: Int = 20) {
        self.clis = clis
        pages = PagedState(pageSize: pageSize)
    }

    public func status(of cli: CliSummary) -> Bool {
        guard let id = cli.id else { return cli.isEnabled }
        return statusOverrides[id] ?? cli.isEnabled
    }

    /// An empty result under a filter is not the same news as an installation with no packages at all.
    public var isFiltered: Bool {
        !keyword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || filter != .all
    }

    public func refresh() async {
        inlineError = nil
        if items.isEmpty { phase = .loading }
        switch await clis.cliPage(name: keyword, status: filter.queryValue, num: 1, size: pages.pageSize) {
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
        switch await clis.cliPage(
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
            // The list is still usable, so the page counter is left alone and the next scroll retries the
            // same page number.
            inlineError = ErrorMessage.text(for: error)
        }
    }

    /// The gate, in the order the console runs it:
    ///
    /// 1. an unreadable related list stops the switch — nothing may be flipped while nobody knows how far
    ///    the change reaches;
    /// 2. nobody bound means the flip is a private matter for this row, so it runs with no confirmation and
    ///    no refresh offer;
    /// 3. enabling in front of a bound agent is adding a capability back, so it runs straight away and then
    ///    offers the refresh;
    /// 4. disabling is the direction that removes something, so it asks first and names who loses.
    public func requestStatus(_ enabled: Bool, for cli: CliSummary) async {
        guard let id = cli.id else { return }
        inlineError = nil
        isCheckingStatus = true
        defer { isCheckingStatus = false }
        switch await clis.cliRelatedAgents(id: id) {
        case let .failure(error):
            // Blocked, not proceeded. The row keeps the status the stack last reported, and no write goes
            // out on an unanswered question.
            inlineError = ErrorMessage.text(for: error)
        case let .success(agents):
            guard !agents.isEmpty else {
                await write(enabled, for: cli, offerRefresh: false)
                return
            }
            if enabled {
                await write(true, for: cli, offerRefresh: true)
            } else {
                disableTarget = DisableTarget(cli: cli, agents: agents, names: Self.names(of: agents))
            }
        }
    }

    public func cancelDisable() {
        disableTarget = nil
    }

    public func confirmDisable() async {
        guard let target = disableTarget else { return }
        disableTarget = nil
        await write(false, for: target.cli, offerRefresh: true)
    }

    /// Who holds this package's configuration right now. The panel's loader is a closure, so it captures the
    /// facade rather than this `MainActor` object — a `@Sendable` closure may not reach into one.
    public func refreshTarget(for cli: CliSummary) -> SessionRefreshTarget? {
        guard let id = cli.id else { return nil }
        let catalog = clis
        return SessionRefreshTarget(
            id: id,
            name: hxPresented(cli.name) ?? "",
            source: .cli
        ) {
            await catalog.cliRelatedSessions(id: id)
        }
    }

    private func write(_ enabled: Bool, for cli: CliSummary, offerRefresh: Bool) async {
        guard let id = cli.id else { return }
        statusOverrides[id] = enabled
        pendingIDs.insert(id)
        defer { pendingIDs.remove(id) }
        if case let .failure(error) = await clis.setCliStatus(id: id, enabled: enabled) {
            // A refusal is the server's own sentence — admin names what it refused — and it stays on screen
            // with the row still where it was.
            statusOverrides[id] = nil
            inlineError = ErrorMessage.text(for: error)
            return
        }
        if offerRefresh { refreshOffer = cli }
    }

    /// A bound agent with no name column still has to be countable in the sentence, so it falls back to its
    /// id and only then to the placeholder — the same order the team drill-down uses.
    private static func names(of agents: [RelatedAgent]) -> String {
        agents.prefix(nameLimit).map { agent in
            hxPresented(agent.agentName)
                ?? agent.agentId.map { hx("cli.related.agentFallback", Int($0)) }
                ?? hx("cli.related.agentUnnamed")
        }.joined(separator: "、")
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

import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// C2 — the team list: the same three writes an agent card has, plus the pre-flight a delete needs.
///
/// The lead's own prompt and model live on the team row, so the list is the whole read path here
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/TeamResponse.kt:8-12`).
@MainActor
public final class TeamListViewModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        case content
        /// The stack answered, and there is nothing to show. Kept apart from `loading` so an account with
        /// no teams never looks like a spinner that never finishes.
        case empty
        case failed(String)
    }

    /// The dialog the delete button opens. `boundSessions` is what the pre-flight read counted, and it is
    /// the sentence the dialog shows — the backend refuses the delete while sessions still bind the team
    /// (`harnax-webui/src/pages/team/index.tsx:171-193`).
    public struct DeleteTarget: Equatable {
        public let team: TeamSummary
        public let boundSessions: Int
    }

    @Published public private(set) var phase: Phase = .loading
    @Published public private(set) var items: [TeamSummary] = []
    @Published public private(set) var total = 0
    /// Shown as a banner above a list that stays on screen: a failed refresh must not erase what the user
    /// was reading, and a refused delete has to stay up until it has been read.
    @Published public private(set) var inlineError: String?
    @Published public private(set) var canLoadMore = false
    @Published public private(set) var isAppending = false
    @Published public private(set) var pendingIDs: Set<Int64> = []
    @Published public private(set) var deleteTarget: DeleteTarget?
    /// The pre-flight is still on the wire, so the row's delete button must not open a second one.
    @Published public private(set) var isCheckingDelete = false
    @Published public var keyword = "" {
        didSet { if keyword != oldValue { scheduleSearch() } }
    }
    @Published public var filter: StatusFilter = .all {
        didSet { if filter != oldValue { Task { await refresh() } } }
    }

    @Published private(set) var statusOverrides: [Int64: Bool] = [:]

    private let teams: any TeamCataloging
    private var pages: PagedState<TeamSummary>
    private var searchTask: Task<Void, Never>?

    public init(teams: any TeamCataloging, pageSize: Int = 20) {
        self.teams = teams
        pages = PagedState(pageSize: pageSize)
    }

    public func status(of team: TeamSummary) -> Bool {
        guard let id = team.id else { return team.isEnabled }
        return statusOverrides[id] ?? team.isEnabled
    }

    /// An empty result under a filter is not the same news as an account with no teams at all.
    public var isFiltered: Bool {
        !keyword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || filter != .all
    }

    public func refresh() async {
        inlineError = nil
        if items.isEmpty { phase = .loading }
        switch await teams.teamPage(name: keyword, status: filter.queryValue, num: 1, size: pages.pageSize) {
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
        switch await teams.teamPage(
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

    public func setStatus(_ enabled: Bool, for team: TeamSummary) async {
        guard let id = team.id else { return }
        statusOverrides[id] = enabled
        pendingIDs.insert(id)
        defer { pendingIDs.remove(id) }
        if case let .failure(error) = await teams.setTeamStatus(id: id, enabled: enabled) {
            statusOverrides[id] = nil
            inlineError = ErrorMessage.text(for: error)
        }
    }

    /// Who has this team's lead and members loaded right now.
    public func refreshTarget(for team: TeamSummary) -> SessionRefreshTarget? {
        guard let id = team.id else { return nil }
        let catalog = teams
        return SessionRefreshTarget(
            id: id,
            name: hxPresented(team.name) ?? "",
            source: .team
        ) {
            await catalog.teamRelatedSessions(id: id)
        }
    }

    /// Ask first, confirm second. An unreadable related list is its own outcome: until admin answers, no
    /// message on this screen can claim the delete would be refused — or that it would not
    /// (`harnax-webui/src/pages/team/index.tsx:159-176`).
    public func requestDelete(_ team: TeamSummary) async {
        guard let id = team.id else { return }
        inlineError = nil
        isCheckingDelete = true
        defer { isCheckingDelete = false }
        switch await teams.teamRelatedSessions(id: id) {
        case let .success(sessions):
            deleteTarget = DeleteTarget(team: team, boundSessions: sessions.count)
        case .failure:
            inlineError = hx("team.delete.loadFailed")
        }
    }

    public func cancelDelete() {
        deleteTarget = nil
    }

    /// A refusal is a normal answer: the backend's own sentence names the blocking sessions, so it is
    /// shown as it arrived rather than mapped to a generic failure.
    public func confirmDelete() async {
        guard let team = deleteTarget?.team, let id = team.id else { return }
        deleteTarget = nil
        if case let .failure(error) = await teams.deleteTeam(id: id) {
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

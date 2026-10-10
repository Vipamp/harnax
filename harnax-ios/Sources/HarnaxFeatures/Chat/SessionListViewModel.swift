import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// The chat tab's landing screen: every conversation this account can see, and the four writes a row
/// can take.
///
/// The shape follows `AgentListViewModel` on purpose — optimistic switch with a rollback, a banner that
/// leaves the list on screen, a debounced search. What differs is the permission rule, documented at
/// `canWrite`, and the fact that a rename is a whole-row update rather than a field patch.
@MainActor
public final class SessionListViewModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        case content
        /// The stack answered, and there is nothing to show. Kept apart from `loading` so an account with
        /// no conversations never looks like a spinner that never finishes.
        case empty
        case failed(String)
    }

    /// What the rename sheet is allowed to do with its own dismissal.
    public enum RenameOutcome: Equatable {
        case saved
        /// Refused before the request: the copy says why, and the sheet stays open with it.
        case invalid(String)
        case failed(String)
    }

    @Published public private(set) var phase: Phase = .loading
    @Published public private(set) var items: [SessionSummary] = []
    @Published public private(set) var total = 0
    /// Shown as a banner above a list that stays on screen — a failed refresh must not erase what the user
    /// was already reading, and a refused delete has to stay up until it has been read.
    @Published public private(set) var inlineError: String?
    /// The runtime's own sentence after a cleared history. `CLEAR` answers success even when the
    /// conversation held nothing
    /// (`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt:241-244`),
    /// so an empty banner would be the honest report more often than a spinner would be.
    @Published public private(set) var notice: String?
    @Published public private(set) var canLoadMore = false
    @Published public private(set) var isAppending = false
    /// Rows whose write has not been answered, so a menu item can show progress rather than appear inert.
    @Published public private(set) var pendingIDs: Set<Int64> = []
    @Published public var keyword = "" {
        didSet { if keyword != oldValue { scheduleSearch() } }
    }

    @Published public var filter: StatusFilter = .all {
        didSet { if filter != oldValue { Task { await refresh() } } }
    }

    /// The switch answers before the server does, so a tap feels immediate on a slow link. A refused write
    /// drops the override, which snaps the row back to what the stack last said.
    @Published private(set) var statusOverrides: [Int64: Bool] = [:]
    /// Same trick for a rename: the update route is confirmed by an empty body, so the new title has to be
    /// remembered here rather than read back.
    @Published private(set) var titleOverrides: [Int64: String] = [:]

    private let sessions: any SessionCataloging
    private var pages: PagedState<SessionSummary>
    private var searchTask: Task<Void, Never>?
    /// Refresh identity and the one-in-flight rule, exactly as `AgentListViewModel` documents them: the
    /// last answer to arrive must not be the one that wins the rows, the page counter and the total.
    private var refreshGeneration = 0
    private var isRefreshing = false
    private var rerunRequested = false
    /// An append asked for while a refresh is on the wire is remembered, not dropped — see
    /// `AgentListViewModel`.
    private var appendRequested = false

    public init(sessions: any SessionCataloging, pageSize: Int = 20) {
        self.sessions = sessions
        pages = PagedState(pageSize: pageSize)
    }

    public func status(of session: SessionSummary) -> Bool {
        guard let id = session.id else { return session.isEnabled }
        return statusOverrides[id] ?? session.isEnabled
    }

    public func title(of session: SessionSummary) -> String {
        if let id = session.id, let override = titleOverrides[id] { return override }
        return session.displayName ?? ""
    }

    /// Whether any write can be addressed to this row at all.
    ///
    /// Nothing here is gated on the creator, which is a deliberate departure from the agent list. The list
    /// is `is_public = 1 OR creator = <me>` (`mapper/SessionMapper.xml:104-116`), while every write only
    /// checks the tenant (`service/impl/SessionServiceImpl.kt:82`), so a conversation another user shared
    /// with the tenant really is editable — refusing the menu there would promise a rule the stack does not
    /// enforce.
    public func canWrite(_ session: SessionSummary) -> Bool {
        session.id != nil
    }

    /// An empty result under a filter is not the same news as an account with no conversations at all.
    public var isFiltered: Bool {
        !keyword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || filter != .all
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
        notice = nil
        switch await sessions.sessionPage(
            keyword: keyword,
            status: filter.queryValue,
            num: 1,
            size: pages.pageSize
        ) {
        case let .success(page):
            guard generation == refreshGeneration else { return }
            pages.replace(with: page)
            statusOverrides = statusOverrides.filter { pendingIDs.contains($0.key) }
            titleOverrides = titleOverrides.filter { pendingIDs.contains($0.key) }
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
        switch await sessions.sessionPage(
            keyword: keyword,
            status: filter.queryValue,
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
            // The list is still usable, so the page counter is left alone and the next scroll retries the
            // same page number.
            inlineError = ErrorMessage.text(for: error)
        }
    }

    private func reissueAppend() async {
        guard appendRequested, canLoadMore, !isAppending else { return }
        appendRequested = false
        await runAppend()
    }

    public func setStatus(_ enabled: Bool, for session: SessionSummary) async {
        guard let id = session.id else { return }
        guard !pendingIDs.contains(id) else { return }
        statusOverrides[id] = enabled
        pendingIDs.insert(id)
        defer { pendingIDs.remove(id) }
        if case let .failure(error) = await sessions.setSessionStatus(id: id, enabled: enabled) {
            statusOverrides[id] = nil
            inlineError = ErrorMessage.text(for: error)
        }
    }

    /// The server's own sentence is the whole value of a refused rename: the update route reads the row,
    /// re-resolves the agent it names and then writes (`service/impl/SessionServiceImpl.kt:244-259`), so a
    /// team row that names an agent, or an agent from another tenant, comes back as a message rather than as
    /// a status code.
    public func rename(_ session: SessionSummary, to title: String) async -> RenameOutcome {
        // The update route takes `@RequestBody SessionCreateRequest` with no `@Validated`
        // (`controller/SessionController.kt:91-95`), so the `@NotBlank` and `@Size(max = 100)` on that DTO
        // (`dto/SessionCreateRequest.kt:13-15`) never run: only the `varchar(100)` column enforces the limit
        // (`harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:457`), and what it raises is a
        // database error the operator cannot act on. This check is the only readable refusal.
        guard let trimmed = hxPresented(title) else { return .invalid(hx("chat.rename.required")) }
        guard trimmed.count <= 100 else { return .invalid(hx("chat.rename.long")) }
        let id = session.id
        // A second tap while the first rename is out says nothing new: the row is already saving, and the sheet
        // stays open with the copy it shows while a save is on the wire.
        if let id, pendingIDs.contains(id) { return .invalid(hx("chat.rename.saving")) }
        if let id { pendingIDs.insert(id) }
        let result = await sessions.renameSession(session, to: trimmed)
        if let id { pendingIDs.remove(id) }
        switch result {
        case .success:
            notice = nil
            inlineError = nil
            if let id { titleOverrides[id] = trimmed }
            return .saved
        case let .failure(error):
            return .failed(ErrorMessage.text(for: error))
        }
    }

    public func delete(_ session: SessionSummary) async {
        guard let id = session.id else { return }
        inlineError = nil
        notice = nil
        if case let .failure(error) = await sessions.deleteSession(id: id) {
            // A refusal is normal: the runtime or the artifact store can hold the delete back and the
            // envelope names what is still in the way (`SessionServiceImpl.kt:326-346`).
            inlineError = ErrorMessage.text(for: error)
            return
        }
        statusOverrides[id] = nil
        titleOverrides[id] = nil
        pages.removeRow(id: id)
        apply()
    }

    /// Throws away the history and leaves the row. The answer is the runtime's own copy
    /// (`harnax-webui/src/pages/session/components/ChatWindow.tsx:1028` reads the same three fields), which
    /// is why the server's sentence is shown verbatim rather than replaced by local success text.
    ///
    /// A row whose `sessionId` is absent does nothing: the command is addressed by that string, and the
    /// numeric row id would simply address a conversation that is not there.
    public func clearMessages(_ session: SessionSummary) async {
        guard let sessionId = hxPresented(session.sessionId) else { return }
        let id = session.id
        if let id, pendingIDs.contains(id) { return }
        if let id { pendingIDs.insert(id) }
        let result = await sessions.clearMessages(sessionId: sessionId)
        if let id { pendingIDs.remove(id) }
        switch result {
        case let .success(reply):
            if reply.success == false {
                inlineError = hxPresented(reply.message) ?? hx("chat.clear.refused")
            } else {
                // The server's `CLEAR` branch always answers with its own sentence
                // (`DefaultAgentRunner.kt:241-244`), so an absent one is itself worth reporting rather than
                // passing over as silence.
                notice = hxPresented(reply.message) ?? hx("chat.clear.emptyReply")
            }
        case let .failure(error):
            inlineError = ErrorMessage.text(for: error)
        }
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

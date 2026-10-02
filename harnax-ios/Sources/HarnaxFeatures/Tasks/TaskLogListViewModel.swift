import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// One task's execution log (`§5.3`), the four filters the route answers to, and the stop of a live run.
///
/// Opened from a task row rather than from a route of its own: the path id is the task, and the log page has no
/// cross-task listing (`GET /api/admin/agent-tasks/{id}/logs` is the only log read in the domain, `§1`).
///
/// Two things about this backend shape the screen:
///
/// - **There is no single-log endpoint** (`§5.4`). A detail pane is the row already in hand, and the only way it
///   learns anything new is the page re-reading underneath it — so the selection is held here and re-pointed at
///   the fresh row by id, the way the console keeps `selectedLog` in sync (`TaskLogModal.tsx:61-66`).
/// - **A stop does not report what it did** (`§5.5`). The three outcomes are settled inside the scheduler, so
///   the only honest news iOS can give is "the request was accepted" plus whatever the next read shows.
@MainActor
public final class TaskLogListViewModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        case content
        /// The task has never fired. A LEFT JOIN keeps such a task on the task list (`§2.2`) with no log rows at
        /// all, so an empty page here is a fact about the task rather than a filter artifact.
        case empty
        case failed(String)
    }

    @Published public private(set) var phase: Phase = .loading
    @Published public private(set) var items: [AgentTaskLog] = []
    @Published public private(set) var total = 0
    @Published public private(set) var canLoadMore = false
    @Published public private(set) var isAppending = false
    @Published public private(set) var inlineError: String?
    /// The one notice this screen can raise that is not a failure: a stop that was accepted (`§5.5`).
    @Published public private(set) var noticeLine: String?
    @Published public private(set) var stoppingIDs: Set<Int64> = []
    /// The row the confirm sheet is about. A stop interrupts a live agent execution, which is the one action on
    /// this screen the console puts behind a confirmation (`TaskLogModal.tsx:149-152`).
    @Published public private(set) var stopTarget: AgentTaskLog?
    /// The row the detail pane is showing, kept pointing at the polled copy while the run is alive.
    @Published public private(set) var selected: AgentTaskLog?

    /// Typed keywords are debounced, as on the task list: one request per keystroke would be a queue of
    /// superseded answers, and this route's `keyword` LIKE-scans three columns (`AgentTaskLogMapper.xml:157-161`).
    @Published public var keyword = "" {
        didSet {
            guard keyword != oldValue else { return }
            refreshGeneration += 1
            scheduleSearch()
        }
    }

    /// Status and the time window are chosen, not typed, so they take effect on the spot.
    @Published public var filter: AgentTaskLogFilter = AgentTaskLogFilter() {
        didSet {
            guard !filter.isSearchEquivalent(to: oldValue) else { return }
            refreshGeneration += 1
            Task { await refresh() }
        }
    }

    @Published public var isWindowEditorShown = false

    private let taskID: Int64
    private let catalog: any AgentTaskCataloging
    private let account: AccountSnapshot?
    private var pages: PagedState<AgentTaskLog>
    private var searchTask: Task<Void, Never>?
    private var afterStopTask: Task<Void, Never>?
    /// The identity of the query on screen, and the guard every page is answered under — the same rule
    /// `AgentListViewModel` documents, with one difference that comes from this screen's own readers: the tick
    /// and the re-read after a stop ask for the page that is already settled, so the two query setters advance
    /// it rather than every call of `refresh()`. A duplicate re-read is not a new query, and retiring the
    /// answer it wants would leave the newest read to a page nobody queued.
    private var refreshGeneration = 0
    /// Whether a first page is on the wire. An append in that window is for a list that is about to be
    /// replaced, so it waits and is re-issued when the page lands.
    private var isRefreshing = false
    /// An append asked for while a refresh is on the wire is remembered, not dropped — see
    /// `AgentListViewModel`.
    private var appendRequested = false
    /// The in-flight cadence (`TaskLogModal.tsx:85-86`) and the idle one. Unlike the task list, this page keeps a
    /// timer alive whatever the rows say — an open log modal is a screen somebody is watching.
    private let activeInterval: Duration
    private let idleInterval: Duration
    private let stopReloadDelay: Duration
    private lazy var poll = HXPollLoop(
        nextInterval: { [weak self] in self?.nextInterval },
        tick: { [weak self] in await self?.silentReload() }
    )

    public init(
        taskID: Int64,
        catalog: any AgentTaskCataloging,
        account: AccountSnapshot?,
        pageSize: Int = 10,
        activeInterval: Duration = .seconds(3),
        idleInterval: Duration = .seconds(5),
        stopReloadDelay: Duration = .seconds(1)
    ) {
        self.taskID = taskID
        self.catalog = catalog
        self.account = account
        pages = PagedState(pageSize: pageSize)
        self.activeInterval = activeInterval
        self.idleInterval = idleInterval
        self.stopReloadDelay = stopReloadDelay
    }

    /// Whether this account may stop the row at all: seeing a log is wider than stopping its execution
    /// (`§5.5`), and only a `3` is still running.
    public func stoppable(_ row: AgentTaskLog) -> Bool {
        row.stoppable(by: account)
    }

    public var isFiltered: Bool { !readFilter.isEmpty }

    /// The cadence rule in one line: 3 s while a row is mid-flight, 5 s otherwise, never off
    /// (`§5.3`'s 轮询 bullet).
    public var nextInterval: Duration? {
        items.contains(where: { $0.state?.isInFlight ?? false }) ? activeInterval : idleInterval
    }

    public var isPolling: Bool { poll.isRunning }

    public func refresh() async {
        inlineError = nil
        noticeLine = nil
        if items.isEmpty { phase = .loading }
        await reload(reporting: true)
    }

    /// A poll is not news: banners stay where they were, and a failed tick is ignored outright
    /// (`TaskLogModal.tsx:107`).
    private func silentReload() async {
        await reload(reporting: false)
    }

    /// The first page, whether a reader or the timer asked for it, answered under the identity of the query that
    /// was on screen when it went out.
    ///
    /// Returns whether the page landed: a queued append is only owed a re-issue once the screen really has a
    /// first page to append to.
    @discardableResult
    private func reload(reporting: Bool) async -> Bool {
        let generation = refreshGeneration
        isRefreshing = true
        defer { isRefreshing = false }
        switch await catalog.agentTaskLogs(
            taskID: taskID,
            filter: readFilter,
            num: 1,
            size: pages.pageSize
        ) {
        case let .success(page):
            guard absorb(page, generation: generation) else { return false }
            await reissueAppend()
            return true
        case let .failure(error):
            guard reporting, generation == refreshGeneration else { return false }
            let text = ErrorMessage.text(for: error)
            if items.isEmpty {
                phase = .failed(text)
            } else {
                inlineError = text
            }
            return false
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

    /// The tail read, under the identity of the query that was on screen when the scroll happened. See
    /// `AgentListViewModel`.
    private func runAppend() async {
        let generation = refreshGeneration
        isAppending = true
        defer { isAppending = false }
        switch await catalog.agentTaskLogs(
            taskID: taskID,
            filter: readFilter,
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
            inlineError = ErrorMessage.text(for: error)
        }
    }

    private func reissueAppend() async {
        guard appendRequested, canLoadMore, !isAppending else { return }
        appendRequested = false
        await runAppend()
    }

    /// Asks first, as the console does: the route really does interrupt the agent's in-flight execution
    /// (`§5.5`'s INTERRUPT command), so a mis-tap is not a no-op.
    public func beginStop(_ row: AgentTaskLog) {
        guard stoppable(row) else { return }
        inlineError = nil
        stopTarget = row
    }

    public func cancelStop() {
        stopTarget = nil
    }

    public func confirmStop() async {
        guard let row = stopTarget else { return }
        stopTarget = nil
        await stop(row)
    }

    /// Stop one live execution by **log** id.
    ///
    /// The route answers a single verdict, so the row's own status is only settled by the next read — the console
    /// waits a second before asking again (`TaskLogModal.tsx:159-161`) and this screen keeps that delay.
    public func stop(_ row: AgentTaskLog) async {
        guard stoppable(row) else { return }
        // A second interrupt command for a run that is already going down is not a second stop.
        guard !stoppingIDs.contains(row.id) else { return }
        stoppingIDs.insert(row.id)
        defer { stoppingIDs.remove(row.id) }
        if case let .failure(error) = await catalog.stopAgentTaskLog(id: row.id) {
            // The English sentence is the only explanation on offer, including the 500 "Task is not running or
            // already completed" a row that finished mid-click answers with.
            inlineError = ErrorMessage.text(for: error)
            return
        }
        noticeLine = hx("task.log.stop.sent")
        afterStopTask?.cancel()
        afterStopTask = Task { [weak self] in
            try? await Task.sleep(for: self?.stopReloadDelay ?? .seconds(1))
            guard !Task.isCancelled else { return }
            await self?.silentReload()
        }
    }

    public func beginDetail(_ row: AgentTaskLog) {
        selected = row
    }

    public func closeDetail() {
        selected = nil
    }

    /// A whole window in one write. The two bounds arrive together from the editor's 确定, and assigning the
    /// struct once means one `didSet` and therefore one request.
    public func setWindow(from: Date?, to: Date?) {
        var next = filter
        next.from = from
        next.to = to
        filter = next
    }

    public func clearWindow() {
        setWindow(from: nil, to: nil)
    }

    public func pausePolling() {
        poll.park()
    }

    public func resumePolling() {
        poll.unpark()
    }

    /// Closing the sheet. The console refreshes the task list on the way out
    /// (`TaskLogModal.tsx:121-132`), because a run that finished while the modal was open is exactly the news the
    /// list's own badge is stale about.
    public func stopPolling() {
        searchTask?.cancel()
        afterStopTask?.cancel()
        poll.cancel()
    }

    /// The keyword lives in its own `@Published` so typing does not re-render the filter struct; the read joins
    /// the two back together.
    private var readFilter: AgentTaskLogFilter {
        var copy = filter
        copy.keyword = keyword
        return copy
    }

    @discardableResult
    private func absorb(_ page: Page<AgentTaskLog>, generation: Int) -> Bool {
        guard generation == refreshGeneration else { return false }
        pages.replace(with: page)
        apply()
        return true
    }

    private func apply() {
        items = pages.elements
        total = pages.total
        canLoadMore = pages.hasMore
        phase = pages.isEmpty ? .empty : .content
        // The detail pane follows the polled copy rather than the snapshot it was opened with, which is how a
        // running row's duration and answer appear without any second request (`§5.4`).
        if let id = selected?.id, let fresh = items.first(where: { $0.id == id }) {
            selected = fresh
        }
        poll.sync()
    }

    private func scheduleSearch() {
        searchTask?.cancel()
        searchTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(500))
            guard !Task.isCancelled else { return }
            await self?.refresh()
        }
    }
}

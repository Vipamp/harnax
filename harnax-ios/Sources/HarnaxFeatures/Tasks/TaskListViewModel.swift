import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// What the list has to do after the task sheet closes, in the sheet's own words.
///
/// Both members exist because the backend says so rather than because the UI wanted a hint: an accepted edit
/// pauses the task whatever it changed (`AgentTaskCrudServiceImpl.kt:156-157`, `§3.2`), and a 40902 means the
/// row is written but the shared Quartz store never converged (`:264-293`, `§4.3`) — which is a completed write
/// with news attached, not a failure.
public struct TaskFormSaveOutcome: Equatable, Sendable {
    public let didPauseScheduledTask: Bool
    public let schedulerSyncWarning: String?

    public init(didPauseScheduledTask: Bool, schedulerSyncWarning: String? = nil) {
        self.didPauseScheduledTask = didPauseScheduledTask
        self.schedulerSyncWarning = schedulerSyncWarning
    }
}

/// `/agent/task` — the task list, its two filters, and the four writes the row offers.
///
/// Shaped like `EnvVarListViewModel`, with two differences that both come from this backend:
///
/// - the page really does take a status filter, and its name is `taskStatus` where every other list in the app
///   sends `status` (`AgentTaskController.kt:58`);
/// - every notice this screen can raise is English. The domain's rejections are hardcoded strings in the
///   scheduler and admin forwards them byte for byte (`§0`), so the row keeps them verbatim instead of routing
///   them through a catalogue.
@MainActor
public final class TaskListViewModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        case content
        /// The stack answered, and this account can see no task at all. The page is scoped to
        /// `is_public = 1 OR creator = me` (`AgentTaskMapper.xml:85`), so an empty list is genuinely empty.
        case empty
        case failed(String)
    }

    @Published public private(set) var phase: Phase = .loading
    @Published public private(set) var items: [AgentTaskSummary] = []
    @Published public private(set) var total = 0
    /// A refused write or a failed refresh, shown above rows that stay on screen.
    @Published public private(set) var inlineError: String?
    /// Not failures: the "this save paused the task" note and a 40902's own sentence (`§4.3`, `§6.4`). Kept
    /// apart from `inlineError` because a red banner under a write that succeeded teaches the user to distrust
    /// the banner.
    @Published public private(set) var noticeLines: [String] = []
    @Published public private(set) var canLoadMore = false
    @Published public private(set) var isAppending = false
    @Published public private(set) var pendingIDs: Set<Int64> = []
    /// Rows whose run-now request was accepted and whose reload has not landed yet — the console waits 1.5 s
    /// before asking again so the scheduler has time to write the running log row
    /// (`harnax-webui/src/pages/agent-task/index.tsx:129`).
    @Published public private(set) var triggeredIDs: Set<Int64> = []
    @Published public private(set) var deleteTarget: AgentTaskSummary?
    @Published public var keyword = "" {
        didSet { if keyword != oldValue { scheduleSearch() } }
    }
    /// `all` leaves `taskStatus` off the query; the other two cases are the console's `1 Enabled` /
    /// `0 Disabled` pair (`harnax-webui/src/pages/agent-task/index.tsx:288-298`).
    @Published public var filter: StatusFilter = .all {
        didSet { if filter != oldValue { Task { await refresh() } } }
    }

    @Published private(set) var statusOverrides: [Int64: Bool] = [:]

    private let catalog: any AgentTaskCataloging
    private var pages: PagedState<AgentTaskSummary>
    private var searchTask: Task<Void, Never>?
    private var triggeredReloadTask: Task<Void, Never>?
    /// Refresh identity and the one-in-flight rule, exactly as `AgentListViewModel` documents them. This
    /// screen has three writers of the same rows — the keyword debounce, the three-second tick and the
    /// delayed re-read after a run — so "whichever answer arrives last wins" is not a rare race here.
    private var refreshGeneration = 0
    private var isRefreshing = false
    private var rerunRequested = false
    /// An append asked for while a refresh is on the wire is remembered, not dropped — see
    /// `AgentListViewModel`.
    private var appendRequested = false
    /// `§2.5`: three seconds, but only while the page holds a Running or Stopping row — the loop cancels itself
    /// the moment the data stops asking, which is what makes this poll data-driven rather than constant.
    private let pollInterval: Duration
    /// The console's own 1.5 s between an accepted run and the re-read that shows it
    /// (`harnax-webui/src/pages/agent-task/index.tsx:129`); a held-out value is the wait a test must then sit
    /// through, so it is a parameter rather than a literal.
    private let triggerReloadDelay: Duration
    private lazy var poll = HXPollLoop(
        nextInterval: { [weak self] in self?.shouldPoll == true ? self?.pollInterval : nil },
        tick: { [weak self] in await self?.silentReload() }
    )

    public init(
        catalog: any AgentTaskCataloging,
        pageSize: Int = 20,
        pollInterval: Duration = .seconds(3),
        triggerReloadDelay: Duration = .milliseconds(1500)
    ) {
        self.catalog = catalog
        pages = PagedState(pageSize: pageSize)
        self.pollInterval = pollInterval
        self.triggerReloadDelay = triggerReloadDelay
    }

    public func status(of row: AgentTaskSummary) -> Bool {
        guard let id = row.id else { return row.isEnabled }
        return statusOverrides[id] ?? row.isEnabled
    }

    /// An empty result under a filter is not the same news as an account that owns no task.
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
        noticeLines = []
        guard await reload(reporting: true, generation: generation) else { return }
        await reissueAppend()
    }

    /// The same page, read again without touching the banners.
    ///
    /// A poll is not new news: clearing `noticeLines` here would make "your save paused this task" vanish three
    /// seconds after it appeared, and a failed tick is ignored outright — the console's own loop swallows it
    /// (`harnax-webui/src/pages/agent-task/components/TaskLogModal.tsx:107`), because a banner every three seconds
    /// over a list that is merely stale is worse than the staleness.
    ///
    /// It reads the query the screen settled on rather than asking for a new one, so it neither bumps the
    /// generation nor queues a rerun: an answer that has been overtaken is dropped where it lands.
    private func silentReload() async {
        await reload(reporting: false, generation: refreshGeneration)
    }

    /// Whether the page landed, because a queued append is only owed a re-issue once the screen really has a
    /// first page of its own query to append to.
    @discardableResult
    private func reload(reporting: Bool, generation: Int) async -> Bool {
        switch await catalog.agentTaskPage(name: keyword, taskStatus: filter.queryValue, num: 1, size: pages.pageSize) {
        case let .success(page):
            return absorb(page, generation: generation)
        case let .failure(error):
            guard reporting, generation == refreshGeneration else { return false }
            guard ErrorMessage.carriesNews(error) else { return false }
            let text = ErrorMessage.text(for: error)
            if items.isEmpty {
                phase = .failed(text)
            } else {
                inlineError = text
            }
            return false
        }
    }

    /// A first page into the visible list.
    ///
    /// An optimistic switch survives only while its own write is still in flight: once the request has answered,
    /// the page carries the very value the override was standing in for, and keeping the override would let a
    /// three-second poll leave a switch pointing the wrong way for good.
    @discardableResult
    private func absorb(_ page: Page<AgentTaskSummary>, generation: Int) -> Bool {
        guard generation == refreshGeneration else { return false }
        pages.replace(with: page)
        statusOverrides = statusOverrides.filter { pendingIDs.contains($0.key) }
        apply()
        return true
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
        switch await catalog.agentTaskPage(
            name: keyword,
            taskStatus: filter.queryValue,
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
            // The list stays usable, so the page counter is left alone and the next scroll retries the
            // same page number.
            inlineError = ErrorMessage.text(for: error)
        }
    }

    private func reissueAppend() async {
        guard appendRequested, canLoadMore, !isAppending else { return }
        appendRequested = false
        await runAppend()
    }

    /// Optimistic, because the answer is a bare `ResultVo<Void>` and the switch is the row's own state.
    ///
    /// Starting is the branch that gets refused for a reason the client cannot see: the backend only counts
    /// cron fields (`AgentTaskCrudServiceImpl.kt:212-225`), so an expression that survives that check but that
    /// Quartz rejects fails here, at `start`, with its own sentence (`§4.1`). The override snaps back and the
    /// message is shown as it arrived.
    public func setStatus(_ enabled: Bool, for row: AgentTaskSummary) async {
        guard let id = row.id else { return }
        guard !pendingIDs.contains(id) else { return }
        statusOverrides[id] = enabled
        pendingIDs.insert(id)
        defer { pendingIDs.remove(id) }
        if case let .failure(error) = await catalog.setAgentTaskStatus(id: id, enabled: enabled) {
            statusOverrides[id] = nil
            inlineError = ErrorMessage.text(for: error)
        }
    }

    /// One run now. Deliberately not gated on the creator check the other four writes use: `§2.4` lists edit,
    /// delete, stop and start/stop as creator-only, and a run request goes through the same visibility read as
    /// the list itself, so a public task may be started by whoever can see it.
    ///
    /// Answers whether the request was accepted, because the console opens that task's log on the way
    /// (`harnax-webui/src/pages/agent-task/index.tsx:114-119`) and the caller can only do that on a `true`.
    @discardableResult
    public func trigger(_ row: AgentTaskSummary) async -> Bool {
        guard let id = row.id else { return false }
        guard !pendingIDs.contains(id) else { return false }
        pendingIDs.insert(id)
        defer { pendingIDs.remove(id) }
        if case let .failure(error) = await catalog.triggerAgentTask(id: id) {
            // Including the 40901 "a run is already in flight and this task forbids it" case, which only the
            // scheduler can tell apart from a real failure (`§7.2`) — so it is shown, not guessed around.
            inlineError = ErrorMessage.text(for: error)
            return false
        }
        triggeredIDs.insert(id)
        triggeredReloadTask?.cancel()
        triggeredReloadTask = Task { [weak self] in
            guard let self else { return }
            try? await Task.sleep(for: self.triggerReloadDelay)
            guard !Task.isCancelled else { return }
            await self.reloadAfterTrigger(id: id)
        }
        return true
    }

    /// The delayed re-read after a run. A failure here is not the user's second bad news — the run was
    /// accepted — so it lands as a banner over the rows that are still on screen.
    private func reloadAfterTrigger(id: Int64) async {
        triggeredIDs.remove(id)
        await reload(reporting: true, generation: refreshGeneration)
    }

    public func beginDelete(_ row: AgentTaskSummary) {
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
        var outOfSync: String?
        if case let .failure(error) = await catalog.deleteAgentTask(id: id) {
            guard AgentTaskCode.isSchedulerOutOfSync(error) else {
                // The row stays: the server kept it, and its sentence is the only explanation on offer.
                inlineError = ErrorMessage.text(for: error)
                return
            }
            // 40902 on a delete is "gone from the table, still in someone's Quartz store" — the console treats
            // it as done and refreshes (`harnax-webui/src/pages/agent-task/index.tsx:158-162`), so iOS drops the
            // row and says so in the warning band instead of the error one.
            outOfSync = ErrorMessage.text(for: error)
        }
        statusOverrides[id] = nil
        pages.removeRow(id: id)
        apply()
        if let outOfSync { noticeLines = [outOfSync] }
    }

    /// The sheet's parting news, applied in the order the list needs it: re-read first, because a saved edit
    /// has paused the task server-side and the row must stop claiming otherwise, then say why it changed.
    public func formDidSave(_ outcome: TaskFormSaveOutcome) async {
        await refresh()
        var lines: [String] = []
        if outcome.didPauseScheduledTask { lines.append(hx("task.form.paused.notice")) }
        if let warning = outcome.schedulerSyncWarning { lines.append(warning) }
        noticeLines = lines
    }

    /// The console waits 500 ms after typing stops; one request per keystroke would be a queue of superseded
    /// answers.
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
        poll.sync()
    }

    // MARK: - polling (`§2.5`)

    /// The whole rule, stated once: this page is only worth re-reading while one of its rows is mid-flight.
    ///
    /// `3` Running and `4` Stopping are the pair the console keys on (`harnax-webui/src/pages/agent-task/index.tsx:67-68`),
    /// and `isInFlight` names the same pair in the contract so a badge and the timer cannot drift apart.
    public var shouldPoll: Bool {
        items.contains { $0.lastRun?.isInFlight ?? false }
    }

    /// Whether the timer is armed right now, which `shouldPoll` alone does not answer — a backgrounded screen
    /// holds the same rows and no timer.
    public var isPolling: Bool { poll.isRunning }

    /// `scenePhase != .active`: parked, not stopped, so coming back re-arms from the data.
    public func pausePolling() {
        poll.park()
    }

    public func resumePolling() {
        poll.unpark()
    }

    /// Leaving the screen. The web modal cancels its timer on close and refreshes its parent
    /// (`TaskLogModal.tsx:121-132`); this list has no parent to tell.
    public func stopPolling() {
        searchTask?.cancel()
        triggeredReloadTask?.cancel()
        poll.cancel()
    }
}

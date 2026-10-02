import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// The plan drawer's whole state (`§计划面板（右侧滑出）`), mounted by whoever owns the chat screen.
///
/// Two reads, and each has its own cadence — that asymmetry is the shape of this class:
///
/// - `current-plan` re-reads every 2 s, and *only* while a plan is open, expanded, the switch is on, and either
///   the drawer is out or a conversation is following it (`ChatWindow.tsx:649-669`, armed on `enablePlan &&
///   currentPlanExpanded && currentPlan` — never on `showPlanPanel`, because the reading feeds the card in the
///   message stream as much as the drawer). Any one of those drops out and the loop retires itself;
/// - the history never re-reads on a timer. Its 5 s `plansListTimerRef` is declared and never started
///   (`ChatWindow.tsx:603`, `:2651-2669`), so this class only reads it on open and on an explicit manual refresh.
///   The console's own dead timer is reproduced rather than "improved".
///
/// The manual refresh is incremental, exactly like `loadPlans(true)` (`ChatWindow.tsx:2603-2648`): the reply is
/// compared against the `planId`s already on screen and only the unseen ones are **prepended**. An entry the
/// user is reading is never rebuilt out from under them, so a plan whose server-side copy moved on keeps the
/// text already displayed.
///
/// The feature switch (`enablePlan`) is a flag rather than a gate on loading: the console reaches this panel only
/// through the switch or the right-edge button (`ChatWindow.tsx:3590-3600`, `:3298-3305`), and what the switch
/// actually stops is the polling.
@MainActor
public final class PlanPanelViewModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        case content
        /// Nothing on either read: no plan open and no plan this conversation has ever written.
        case empty
        case failed(String)
    }

    @Published public private(set) var phase: Phase = .loading
    /// History newest-first, in the order the route answered — the console neither sorts nor slices it
    /// (`ChatWindow.tsx:3041-3290`).
    @Published public private(set) var history: [PlanNote] = []
    /// The plan the session is on now, already through the name-is-validity rule
    /// (`PlanNote.isValid` / `hasValidCurrentPlan`, `ChatWindow.tsx:2965-3038`): a plan with no name is absent
    /// rather than an empty card, so `nil` is the only way this is empty.
    @Published public private(set) var current: PlanNote?
    /// A failed read with content still on screen (`§`'s "a failure never empties the panel").
    @Published public private(set) var inlineError: String?
    /// Drives the history section's own spinner, the console's `loadingPlans`.
    @Published public private(set) var isLoadingHistory = false
    /// The current plan card's body. The console opens expanded (`ChatWindow.tsx:593`) and its poll lives or
    /// dies on this flag, so collapsing the card is also what stops the requests.
    @Published public private(set) var isCurrentPlanExpanded = true
    /// The accordion's open row (`Collapse accordion defaultActiveKey=[plans[0].planId]`,
    /// `ChatWindow.tsx:3041-3290`): at most one history row is open, and it is the newest one when the panel
    /// first loads.
    @Published public private(set) var expandedPlanID: String?

    /// `enablePlan`. Read-only from the outside but changeable, because the chat toolbar's Switch can move while
    /// this panel is mounted.
    @Published public var isEnabled: Bool {
        didSet { if isEnabled != oldValue { poll.sync() } }
    }

    /// Whether a conversation screen is following this plan right now.
    ///
    /// The console never gated the poll on its drawer: the timer's guard is `enablePlan && currentPlanExpanded
    /// && currentPlan` (`ChatWindow.tsx:649-669`), because the card the reading feeds sits in the message stream
    /// (`:2479-2585`), not in the drawer. This flag is the difference between "a chat screen is on this
    /// conversation" and "somebody mounted the panel and walked away from it".
    public private(set) var isFollowing = false

    /// The conversation screen's hook: a reading that put a different plan on the panel than was there before,
    /// told to whoever is drawing the inline card.
    ///
    /// A callback rather than a published value the screen watches, because the rule that matters is *changed*:
    /// a poll that answers the same plan again must not rewrite the stream, and the plan going away must leave the
    /// card on screen (`:2576-2584`) — so a `nil` here is news of its own, and the listener decides to ignore it.
    @MainActor public var onCurrentPlanRead: ((PlanNote?) -> Void)?

    private let sessionId: String
    private let reading: any PlanReading
    /// The console's `planRefreshTimerRef` cadence (`ChatWindow.tsx:602`), a test seam like every other screen's.
    private let pollInterval: Duration
    /// Whether the drawer is open. `close()` is `handleTogglePlanPanel`'s else-branch: the timers go, the data stays.
    private var isOpen = false
    /// The message the last failing read left, until the user asks for a read again.
    private var lastFailure: String?
    private lazy var poll = HXPollLoop(
        nextInterval: { [weak self] in self?.nextInterval },
        tick: { [weak self] in await self?.tick() }
    )

    public init(
        sessionId: String,
        reading: any PlanReading,
        isEnabled: Bool = true,
        pollInterval: Duration = .seconds(2)
    ) {
        self.sessionId = sessionId
        self.reading = reading
        self.isEnabled = isEnabled
        self.pollInterval = pollInterval
    }

    // MARK: - the cadence rule

    /// All of the console's guards at once, because its effect guard is all of them at once. A session with no
    /// plan open asks no questions about a plan, and neither does a screen that has stopped following one.
    public var nextInterval: Duration? {
        guard isOpen || isFollowing, isEnabled, isCurrentPlanExpanded, current != nil else { return nil }
        return pollInterval
    }

    public var isPolling: Bool { poll.isRunning }

    /// The `Realtime Update` line: the spinner means the loop is actually running, not that it could.
    public var isLive: Bool { poll.isRunning }

    // MARK: - open / close

    /// Opening the drawer: the history in full, the current plan once, then whatever the three conditions ask for.
    public func open() async {
        guard !isOpen else { return }
        isOpen = true
        await reload()
    }

    /// Closing the drawer. The data stays so a reopen does not flash an empty panel, and the loop is *re-synced*
    /// rather than cut: a conversation still following this plan is watching the card in its own message stream,
    /// and `ChatWindow.tsx:649-669` never had the drawer in its guard. With nothing following, the sync retires
    /// the loop, which is `handleTogglePlanPanel`'s else-branch.
    public func close() {
        isOpen = false
        poll.sync()
    }

    // MARK: - following the plan

    /// A conversation screen has this plan's card on screen and wants it kept current: read it once, now, and arm
    /// the loop for as long as the follow lasts (`ChatWindow.tsx:1503`, where the plan call starts the load, and
    /// `:642-646`, where an open switch does).
    ///
    /// Idempotent, because the chat screen calls this on a frame *and* on the switch arriving from the config
    /// read: a follow already running asks nothing.
    public func startFollowing() async {
        guard !isFollowing else { return }
        isFollowing = true
        // `reporting: false`: a follow that fails has nothing on screen to spoil, and an error band on a panel
        // the user did not open is not theirs to read. `loadCurrent` re-syncs the loop off whatever came back.
        await loadCurrent(reporting: false)
    }

    /// The conversation stopped caring: the switch went off, the screen went away, or it moved on to another
    /// session. The data stays for the drawer, and the loop goes with the follow.
    public func stopFollowing() {
        guard isFollowing else { return }
        isFollowing = false
        poll.sync()
    }

    /// `plan_exit` came back (`ChatWindow.tsx:1587-1590`): the plan is over, so the card stops being followed and
    /// the loop retires. What is already drawn stays drawn — in the drawer and in the stream alike.
    ///
    /// The follow flag goes too, which is what lets the next `plan_write` reach `startFollowing()` again rather
    /// than finding itself guarded out by one that has finished.
    public func exitCurrentPlan() {
        isFollowing = false
        current = nil
        settle()
    }

    /// `common.retry` on a panel that failed before it had anything to show: both reads again, in full.
    public func reload() async {
        lastFailure = nil
        if history.isEmpty && current == nil { phase = .loading }
        await loadHistory(full: true)
        await loadCurrent(reporting: true)
        settle()
    }

    /// The 刷新 button — `handleRefreshPlans` (`ChatWindow.tsx:2672-2675`), which is `loadPlans(true)` and
    /// nothing else. The current plan card is already on its own timer; a manual refresh does not touch it.
    public func refreshHistory() async {
        lastFailure = nil
        await loadHistory(full: false)
        settle()
    }

    // MARK: - expansion

    public func toggleCurrentPlan() {
        isCurrentPlanExpanded.toggle()
        poll.sync()
    }

    /// The accordion: opening one row closes the one that was open, and tapping the open row closes it.
    public func toggleHistoryRow(_ plan: PlanNote) {
        let key = plan.id
        expandedPlanID = expandedPlanID == key ? nil : key
    }

    public func isHistoryRowExpanded(_ plan: PlanNote) -> Bool {
        expandedPlanID == plan.id
    }

    // MARK: - scene

    public func pausePolling() {
        poll.park()
    }

    public func resumePolling() {
        poll.unpark()
    }

    /// The panel leaving the hierarchy for good.
    public func stopPolling() {
        poll.cancel()
    }

    // MARK: - reads

    /// A tick is not news: the console's `loadCurrentPlan` returns in silence on a non-OK response
    /// (`ChatWindow.tsx:2489-2492`), so a background re-read that fails leaves the card exactly as it was and
    /// raises nothing.
    private func tick() async {
        await loadCurrent(reporting: false)
    }

    private func loadCurrent(reporting: Bool) async {
        switch await reading.currentPlan(sessionId: sessionId) {
        case let .success(plan):
            // The name is the validity flag, so an unnamed plan reads as no plan rather than as a blank card.
            let note = plan.valid ? plan.note : nil
            // Only a *different* reading is news: the poll answers the same plan again every 2 s while the run
            // works on it, and the card in the stream has no reason to be rewritten for that. A plan that went
            // away is a change too, and gets said — the listener decides to leave the card where it is
            // (`ChatWindow.tsx:2576-2584`).
            let changed = note != current
            current = note
            if changed { onCurrentPlanRead?(note) }
        case let .failure(error):
            guard reporting else { return }
            lastFailure = ErrorMessage.text(for: error)
        }
        settle()
    }

    private func loadHistory(full: Bool) async {
        isLoadingHistory = true
        defer { isLoadingHistory = false }
        switch await reading.planNotes(sessionId: sessionId) {
        case let .success(notes):
            absorb(notes, full: full)
        case let .failure(error):
            // `loadPlans` catches and only logs (`ChatWindow.tsx:2640-2644`), but a refresh button that silently
            // did nothing is worse than one that says so — and either way the rows already displayed stay put.
            lastFailure = ErrorMessage.text(for: error)
        }
        settle()
    }

    /// Full load replaces; incremental prepends unseen `planId`s and leaves the rest alone
    /// (`ChatWindow.tsx:2621-2637`).
    ///
    /// The comparison is on `planId` and nothing else, including the nil the console also puts in its own set —
    /// a plan the runtime never stamped is "already seen" once any unstamped plan is on screen, which is what the
    /// `Set` of `undefined` does there. Reproduced rather than tidied, because the alternative re-shows a plan the
    /// user has just watched.
    private func absorb(_ notes: [PlanNote], full: Bool) {
        if full || history.isEmpty {
            history = notes
            // `defaultActiveKey` is a mount-time prop: the newest row opens, and an incremental refresh — which
            // antd treats as the same mount — does not move the highlight.
            expandedPlanID = notes.first?.id
            return
        }
        let seen = Set(history.map(\.planId))
        let fresh = notes.filter { seen.contains($0.planId) == false }
        guard !fresh.isEmpty else { return }
        history = fresh + history
    }

    /// Where the last failure goes, and whether it goes at all.
    ///
    /// One rule for both reads: a failure never empties the panel. Whatever the good reads put on screen stays
    /// there with the failure as a band over it, and only a panel with nothing on either side becomes an error
    /// screen — which is why the message is kept here rather than written straight into `phase`.
    private func settle() {
        let hasNoContent = history.isEmpty && current == nil
        if let failure = lastFailure {
            phase = hasNoContent ? .failed(failure) : .content
            inlineError = hasNoContent ? nil : failure
        } else {
            inlineError = nil
            phase = hasNoContent ? .empty : .content
        }
        poll.sync()
    }

    // MARK: - pure rendering helpers

    /// `costTimeSeconds` spelled with the formatter the repo already has (`AgentTaskLog.spelledDuration`,
    /// the console's own `(ms/1000).toFixed(1) + "s"`), so no second duration dialect gets invented here.
    ///
    /// `nil` renders as nothing, never as `-` or `0s`: the route answers through a `non_null` inclusion, so an
    /// unset column is an absent key (`PlanNote.swift`'s header). Zero renders as nothing too, which is the
    /// console's `costTimeSeconds > 0` guard on both the subtask row and the footer
    /// (`ChatWindow.tsx:3128-3133`, `:3276-3280`).
    public static func elapsedText(_ seconds: Int64?) -> String? {
        guard let seconds, seconds > 0 else { return nil }
        return AgentTaskLog.spelledDuration(seconds * 1_000)
    }

    /// A subtask's own elapsed seconds, under the same rule as the plan's.
    public static func elapsedText(_ subtask: PlanSubTask) -> String? {
        elapsedText(subtask.costTimeSeconds)
    }

    /// `3/5`, and only when there are subtasks to count — the console renders the badge inside
    /// `subtasks.length > 0` (`ChatWindow.tsx:2987-2993`). Numbers are not copy, so nothing here needs a key.
    public static func progressText(_ plan: PlanNote) -> String? {
        guard !plan.subtasks.isEmpty else { return nil }
        let progress = plan.progress
        return "\(progress.done)/\(progress.total)"
    }
}

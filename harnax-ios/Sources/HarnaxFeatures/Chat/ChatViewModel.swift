import Foundation
import Combine
import SwiftUI
import HarnaxCore
import HarnaxKit

/// What the conversation screen is showing.
///
/// The session list owns the rows it draws; this screen only needs the two things a turn is addressed by,
/// so it declares them here instead of borrowing a DTO from the list half of the feature. `id` is the
/// string business key rather than the numeric row id, because the streaming endpoints take the business
/// key (`harnax-webui/src/pages/session/index.tsx:309` passes `sessionId`, while delete takes `id`).
public struct ChatConversation: Identifiable, Hashable, Sendable {
    public let id: String
    public let title: String

    public init(id: String, title: String) {
        self.id = id
        self.title = title
    }
}

/// The conversation's own half of the screen: the transcript as it grows, the read that feeds it, and the
/// scroll intent that follows it.
///
/// One turn is streamed at a time. Every path that leaves the screen or moves to another conversation has
/// to abort the read first — the console does not, and its own audit calls that out
/// (`harnax-webui/src/pages/session/components/ChatWindow.tsx:672-680`, `:683-705`): the previous
/// conversation's frames keep landing in the new one. Here `bind` and `detach` both cancel before anything
/// else happens.
@MainActor
public final class ChatViewModel: ObservableObject {
    /// Why an answer stopped without an end frame. The transcript keeps whatever did arrive; this is the
    /// sentence under it.
    public enum StopNotice: Equatable {
        /// The read closed and nothing had arrived, so there is no partial answer to show
        /// (`ChatWindow.tsx:2361-2366`).
        case disconnected
        /// The answer broke partway through, or the request never started. `text` is the resolved copy.
        case failed(text: String)
    }

    /// How far from the newest row still counts as following the stream. The console's
    /// `NEAR_BOTTOM_THRESHOLD` (`ChatWindow.tsx:612`), in the same unit — points.
    public static let nearBottomThreshold: CGFloat = 120

    @Published public var draft = ""
    /// The rows and the tail intent that rides with them, as one published value.
    ///
    /// They change together on every streaming update — a delta grows a row *and* pulls the view down to it —
    /// and `@Published` sends one `objectWillChange` per property write, not per render. Kept apart, one token
    /// would invalidate the transcript twice; kept together, a frame window is one update
    /// (`ChatStreamCoalescer`, `DESIGN.md` §10 性能). Written only through `commit(_:)` and `settled(_:)`.
    @Published private var surface = TranscriptSurface()
    /// The conversation as the screen draws it. Reads are exactly what they were when this was a `@Published`
    /// property; it is get-only from outside the view model, which is also what makes an update that skipped
    /// the frame window a compile error rather than a silent one.
    public var transcript: ChatTranscript { surface.transcript }
    @Published public private(set) var conversation: ChatConversation
    @Published public private(set) var isStreaming = false
    @Published public private(set) var stopNotice: StopNotice?
    /// The stored rows are on their way. The screen shows a loading state rather than an empty conversation,
    /// because the two look identical until the call comes back.
    @Published public private(set) var isLoadingHistory = false
    /// Why the stored rows could not be read, resolved copy. Kept apart from `stopNotice`, which is about an
    /// answer that stopped mid-flight: an unopened conversation never had one.
    @Published public private(set) var historyFailure: String?
    /// The user is looking at the tail, so new rows should be pulled into view. False from the moment they
    /// scroll up past `nearBottomThreshold` until they come back down.
    @Published public private(set) var isAnchoredToBottom = true
    /// Bumped when the list should be at the newest row. The view watches this instead of scrolling on
    /// every published change, which is what `scrollToBottom(force:)`'s `isNearBottomRef` test decides
    /// (`ChatWindow.tsx:614-617`). It lives in `surface` beside the rows it follows, so a stream that grows
    /// the transcript and pulls the view along is one update and not two.
    public var scrollToBottomID: Int { surface.scrollToBottomID }

    // MARK: - composer

    /// The pictures waiting to go out, as `data:image/…;base64,…` strings.
    ///
    /// Plain strings rather than picker items, so the whole send path is testable on a macOS host that has no
    /// photo library. The `chatPhotoPicker` modifier is the only thing that turns a picture into one of these
    /// (`Sources/HarnaxFeatures/Chat/ChatImagePicker.swift`).
    @Published public private(set) var images: [String] = []
    /// The conversation's four writable settings plus the model's capability flags, read once on open
    /// (`ChatWindow.tsx:718-737`). It is the same DTO the config route answers, kept whole because the
    /// matrix of which switch is enabled reads off every flag in it at once.
    @Published public private(set) var composer = SessionChatConfig()
    /// Why the last tap did nothing, or why a switch has come back. The console's three `message.warning`
    /// calls (`ChatWindow.tsx:3493-3572`) are its only feedback for a refused switch; here it is a banner.
    @Published public var composerNotice: ChatComposerNotice?
    /// A command that has to be confirmed before it goes out, and the text the user typed for it.
    @Published public private(set) var pendingCommand: ChatPendingCommand?
    /// The choice made for each row of a confirmation panel, by tool id.
    ///
    /// Absent means 「允许执行」, which is what an untouched row shows, and what a rollback therefore has to
    /// leave alone: the user's own taps survive an answer that never reached the run.
    @Published public private(set) var confirmationChoices: [String: ToolConfirmAnswer] = [:]
    /// Artifact ids whose bytes are in flight — the file row's own spinner, and the gate a second tap hits.
    @Published public private(set) var pendingDownloads: Set<String> = []

    // MARK: - plan

    /// The plan panel, owned here rather than by the drawer.
    ///
    /// The card in the message stream and the card in the drawer are the same plan, read by the same loop
    /// (`ChatWindow.tsx:2479-2585` feeds both from one `currentPlan`), and that reading has to outlive the sheet:
    /// a panel built by the drawer stops when the drawer closes, which would leave the stream's card frozen the
    /// moment the user looked at it. `nil` is a host that wired no `PlanReading`, and it takes the toolbar entry
    /// with it.
    @Published public private(set) var planPanel: PlanPanelViewModel?
    /// Whether the drawer is showing. The composer's chip writes the plan *switch*; nothing writes this one by
    /// hand any more, because a plan call opens the drawer on its own (`ChatWindow.tsx:1501`).
    @Published public var isPlanPanelPresented = false

    // MARK: - sandbox workspace

    /// The workspace drawer's panel, owned here for the same reason the plan panel is: it is addressed by session
    /// id, so it has to be rebuilt when the conversation moves rather than outliving the move inside the sheet.
    /// `nil` is a host that wired no `SessionWorkspaceReading`.
    @Published public private(set) var workspacePanel: WorkspaceViewModel?
    @Published public var isWorkspacePresented = false
    /// Whether this conversation has a sandbox up right now — the only thing that puts the workspace entry in
    /// this screen's top-right corner.
    ///
    /// The console keeps its button on screen and reads the status when it is tapped, warning when the answer is
    /// no (`harnax-webui/src/pages/session/index.tsx:280-292`). iOS hides the entry instead, because with no
    /// sandbox manager running all five workspace routes are 404s (`SandboxWorkspaceController.kt:398-416`) and
    /// a corner entry that can only fail reads as a broken button. A read that *failed* counts as not running
    /// here and says nothing: the entry going away is a state, while `checkSandbox` has a refused command to
    /// explain.
    @Published public private(set) var sandboxIsRunning = false

    // MARK: - context usage

    /// How full this conversation's model context is, or `nil` when there is nothing to show.
    ///
    /// `nil` is not an empty context. The runtime answers two ways when it cannot see one — a session never
    /// bound to an instance (`ResultVo.success(null)`) and a session bound elsewhere
    /// (`ResultVo.error("No context held for session …")`) — and the console hides its tag for both rather
    /// than drawing `0%` (`harnax-webui/src/pages/session/index.tsx:142-153`,
    /// `harnax-webui/src/pages/session/components/contextUsage.ts:22-26`). A read therefore *overwrites*,
    /// including with `nil`: the tag is the latest answer, and an old one belongs to a context the run has
    /// since changed.
    @Published public private(set) var contextUsage: ContextUsage?

    /// How many occupancy reads this screen has asked for. The class is `@MainActor`, so this counts asks in
    /// order without a lock, and `refreshContextUsage` keeps only the newest one's answer.
    private var contextUsageGeneration = 0

    private let streaming: any AgentStreaming
    private let commands: (any AgentCommanding)?
    private let history: (any ChatHistoryReading)?
    private let configReader: (any SessionConfiguring)?
    private let workspace: (any SessionWorkspaceReading)?
    /// The answer channel, optional like the five above it. Without it the confirmation card shows the wait
    /// and offers nothing, because a control that can only fail is worse than no control
    /// (`ChatWindow.tsx:451-460` gates its buttons on the same kind of `onAnswer` being present).
    private let confirming: (any ToolConfirming)?
    /// The plan's two reads, optional for the same reason as the five above: a host that wired none has no drawer
    /// to open, and its stream draws no plan card either.
    private let planReading: (any PlanReading)?
    /// The context-occupancy read, optional for the same reason as the six above: a host that wired none shows
    /// no readout, and the composer's compaction entry still works off the command channel alone.
    private let contextReader: (any ContextUsageReading)?
    /// The one door a run's artifact bytes leave through, injected for the same reason the two drawers inject
    /// it: the macOS test host has no share sheet, and the question a test asks is which bytes under which name.
    private let share: @Sendable (HXSharedFile) -> Void
    /// The system's background allowance for the read in flight: taken when a stream opens and given back when
    /// it ends. That is the whole of what `DESIGN.md:183` asks for, and it is less than the line reads as —
    /// see `ChatBackgroundAssertion` for the ceiling.
    private let background: ChatBackgroundAssertion
    /// The clock the frame window runs on. Production ticks at display rate; a test hands itself every tick,
    /// which is why none of them waits on a wall clock to find out what a window does
    /// (`ChatStreamCoalescer`).
    private let frameClock: any ChatFrameClock
    /// The current frame window: what is held back from the screen, and whether a tick is owed for it.
    private var coalescer = ChatStreamCoalescer()
    private var streamTask: Task<Void, Never>?
    /// The stored rows are already on screen for this conversation. A reload is a re-entry, not a refresh.
    private var loadedConversationID: String?
    /// The confirm stream being read right now, and whether anything has come back over it yet. An answer
    /// whose stream yields no frame at all never reached the run, and the panel has to go back on screen.
    private var confirmRead: ConfirmRead?
    /// A member answer's own leg. It runs beside the lead's read rather than instead of it, because the
    /// resumed member output comes back on the lead's stream (`postMemberAnswer`).
    private var memberAnswerRead: ConfirmRead?
    private var memberAnswerTask: Task<Void, Never>?
    /// What the composer box held when the turn now reading took it out.
    private var consumedByOpenTurn: ConsumedComposer?
    /// The panel is a nested observable object, and the card in the stream reads through this one
    /// (`isPlanExpanded`, `isPlanLive`), so its changes are forwarded here or the card would show a stale
    /// chevron and a spinner that never stops.
    private var planObserver: AnyCancellable?
    /// The plan read a frame or a switch change asked for. Internal for the same reason the held frames are:
    /// 「a plan call started the load」 is only assertable by something that can wait for that leg
    /// (`ChatStreamPublishingTests`).
    private(set) var planFollowTask: Task<Void, Never>?

    /// The two halves of the conversation area that change together: the rows on screen, and whether the view
    /// should be pulled to the end of them.
    ///
    /// One value rather than two published properties, because `@Published` publishes per write and a streamed
    /// token writes both: kept apart, the requirement's 「每 token 触发全列表刷新」 would still be true with
    /// twice the updates (`DESIGN.md` §10 性能).
    private struct TranscriptSurface {
        var transcript = ChatTranscript()
        var scrollToBottomID = 0
    }

    /// The text and pictures one send consumed from the composer.
    ///
    /// `send()` empties the box before the request goes out, which is right for a turn that lands and wrong
    /// for one that dies in transmission: the words the user typed and the pictures they picked are gone
    /// with nothing left to retry. This is the copy a read that never got a frame puts back. It is dropped
    /// the moment any frame arrives — from then on the router has the message, and refilling the box would
    /// only invite a second run of the same turn.
    private struct ConsumedComposer {
        let text: String
        let images: [String]
    }

    /// One read in flight, and the frame count that says whether the answer landed.
    private struct ConfirmRead {
        let tools: [ChatPendingTool]
        var sawFrame = false
    }

    /// Which of the two streaming endpoints the current read came from. Both feed one loop, because the
    /// confirm response is the same event vocabulary as a chat response — including another confirmation.
    private enum ReadTarget {
        case chat(ChatAgentRequest)
        case confirm(ConfirmAgentRequest)
    }

    /// `commands` is the console's second leg of a stop: the read is aborted locally, and `INTERRUPT` tells
    /// the server to stop billing the run (`ChatWindow.tsx:2401-2405`). A host that has no command channel
    /// wired yet still gets a working abort, so the dependency is optional. `history` is optional for the
    /// same reason: a host that has not wired it starts on an empty transcript instead of failing to build.
    ///
    /// `config` is what the composer's four controls are gated by — the model capability flags and the stored
    /// permission mode (`ChatWindow.tsx:718-737`) — and `workspace` is the sandbox status read that
    /// `/stop-sandbox` has to make before it offers the confirmation (`:3639-3664`). Both optional like the
    /// other two: without them the switches keep the shipped defaults and the send path is untouched.
    ///
    /// `confirming` answers a tool confirmation and hands back the stream that resumes the run
    /// (`SessionRouterService.kt:221`). Left unwired, a parked run stays parked.
    ///
    /// `share` is where a downloaded run artifact goes. Both drawers take the same closure for the same
    /// reason (`WorkspaceViewModel.swift:103-108`), and there is no way to test an artifact row without it.
    ///
    /// `contextUsage` is the occupancy read behind the header's tag. Left unwired, the tag never appears —
    /// unlike the console, which has no unwired case because one client object serves every route
    /// (`harnax-webui/src/pages/session/index.tsx:142-153`).
    ///
    /// Every screen a handset builds takes the platform's own background allowance; the initializer below is
    /// the one that takes it as an argument.
    public convenience init(
        streaming: any AgentStreaming,
        confirming: (any ToolConfirming)? = nil,
        commands: (any AgentCommanding)? = nil,
        history: (any ChatHistoryReading)? = nil,
        config: (any SessionConfiguring)? = nil,
        workspace: (any SessionWorkspaceReading)? = nil,
        plan: (any PlanReading)? = nil,
        contextUsage: (any ContextUsageReading)? = nil,
        conversation: ChatConversation,
        share: @escaping @Sendable (HXSharedFile) -> Void = HXFileShare.share
    ) {
        self.init(
            streaming: streaming,
            confirming: confirming,
            commands: commands,
            history: history,
            config: config,
            workspace: workspace,
            plan: plan,
            contextUsage: contextUsage,
            background: ChatBackgroundAssertion(),
            conversation: conversation,
            share: share
        )
    }

    /// `background` is the system's allowance for the read in flight (`DESIGN.md:183`). It is an argument
    /// rather than something the screen reaches for itself because the two things worth asserting about it —
    /// that a run holds exactly one and gives it back once, and what the screen does when iOS takes it back —
    /// are only observable from outside, and no host on this planet can wait for the real grace period in a
    /// test.
    ///
    /// `frameClock` is the same argument for the same reason: whether a token reached the screen because a run
    /// ended or because a frame window closed (`ChatStreamCoalescer`) is only decidable when the test is the
    /// one holding the ticks. A test installs a clock it drives itself and therefore never sleeps; `nil` — the
    /// only default the language allows on an argument whose value is main-actor-isolated — is the ticker that
    /// runs at display rate.
    init(
        streaming: any AgentStreaming,
        confirming: (any ToolConfirming)? = nil,
        commands: (any AgentCommanding)? = nil,
        history: (any ChatHistoryReading)? = nil,
        config: (any SessionConfiguring)? = nil,
        workspace: (any SessionWorkspaceReading)? = nil,
        plan: (any PlanReading)? = nil,
        contextUsage: (any ContextUsageReading)? = nil,
        background: ChatBackgroundAssertion,
        frameClock: (any ChatFrameClock)? = nil,
        conversation: ChatConversation,
        share: @escaping @Sendable (HXSharedFile) -> Void = HXFileShare.share
    ) {
        self.streaming = streaming
        self.confirming = confirming
        self.commands = commands
        self.history = history
        self.configReader = config
        self.workspace = workspace
        self.planReading = plan
        self.contextReader = contextUsage
        self.background = background
        // The nil default is Swift's, not this type's preference: a default argument is evaluated outside the
        // actor, and the ticker is `@MainActor`. Inside this body the isolation is the class's, so the
        // production clock is built here and every caller that says nothing still gets a display-rate tick.
        self.frameClock = frameClock ?? ChatFrameTicker()
        self.conversation = conversation
        self.share = share
        // The console reads the current plan on mount, with the switch as its only guard (`:642-646`) — not when
        // the drawer opens, which is the difference between a panel the screen owns and one the drawer owns.
        adoptPlanPanel()
        // Building the workspace panel asks nothing of the network; the drawer's own `.task` is what reads.
        adoptWorkspacePanel()
    }

    /// Nothing to send, or a turn already reading — the input bar's send button becomes a stop button while
    /// streaming (`ChatWindow.tsx:3679-3686`), and the backend refuses a second run on a session still busy.
    ///
    /// A picture is a message. The console's own disabled test reads
    /// `!inputValue.trim() && imageUrls.length === 0` (`:3684`), so a caption-less picture arms the button.
    public var canSend: Bool {
        !isStreaming && (!draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !images.isEmpty)
    }

    /// The conversation's own id decides two composer affordances.
    ///
    /// A `task-` conversation is the scheduler's, and the runtime refuses to move its permission mode or its
    /// capability flags at all (`DefaultAgentRunner.kt:952-954`, `:1025-1027`), so the picker is not offered
    /// rather than offered and refused.
    public var isTaskConversation: Bool { conversation.id.hasPrefix("task-") }

    /// Whether the permission picker may be shown. The switch itself never was the guard: the command is the
    /// thing that says no.
    public var canPickPermission: Bool { !isTaskConversation }

    /// Whether a picture may be picked. Already-picked pictures stay sendable when this is false — the console
    /// blocks the button, not the state (`ChatWindow.tsx:3493-3505`).
    public var canPickImages: Bool { composer.modelSupportVision }

    /// The answer currently on screen, open or closed.
    public var answer: ChatTurn? { transcript.turns.last }

    // MARK: - screen updates

    /// The frames the open frame window is holding back from the screen, oldest first.
    ///
    /// Internal rather than public, and it exists because 「at most one update per frame」 is only assertable
    /// by something that can say "these deltas have all arrived and none has been published yet"
    /// (`Tests/HarnaxFeaturesTests/ChatStreamPublishingTests.swift`).
    var heldStreamFrames: [ChatEvent] { coalescer.held }

    /// Whether a frame window is standing, i.e. whether the next delta will be held rather than shown.
    var isOpenFrameWindow: Bool { coalescer.isWindowOpen }

    /// One change to the conversation area that adds no rows of its own, told to the view immediately.
    ///
    /// The scroll bump is all that is left here. Anything that writes rows goes through `settled(_:)` instead:
    /// a frame window may be standing at any moment of a run, and a write that skipped the batch it holds would
    /// land the answer under the message that came after it. `transcript` and `scrollToBottomID` are get-only
    /// out here, so a write that tried to skip both doors would not compile.
    private func commit(_ change: (inout TranscriptSurface) -> Void) {
        var next = surface
        change(&next)
        surface = next
    }

    /// Take everything the frame window is holding, fold it, and let `change` land behind it — in one update,
    /// at once, and in that order.
    ///
    /// The order is the point. A held delta arrived before whatever `change` does, so it has to reach the
    /// screen first: half an answer appearing *under* the message the user sent while it waited would read as
    /// that message's reply. This is the door for every path that touches the transcript for a reason of its
    /// own — a send, a stop, a confirmation settling or being handed back, a command's reply. A path that only
    /// needs the window emptied uses `settleWindow()`.
    private func settled(_ change: (inout TranscriptSurface) -> Void) {
        var next = surface
        for frame in coalescer.settle() { next.transcript.fold(frame) }
        change(&next)
        surface = next
        syncFrameClock()
    }

    /// Empty a standing frame window, with no change of the caller's own.
    ///
    /// The paths that only need the held frames on screen — a read that ended, a fresh read opening over a
    /// leg that already settled. Nothing is published when no window is standing, so a run that ended on its
    /// own end frame does not cost the view a second update for the tidying.
    private func settleWindow() {
        guard coalescer.isWindowOpen else { return }
        settled { _ in }
    }

    /// Do what the frame window decided about one arriving frame, or about one tick.
    ///
    /// The held frames and the frame that ended the window land together, and the tail follows the rows in the
    /// same write — one update per frame window is what `DESIGN.md` §10 性能 asks for, and the per-token
    /// scroll bump was the second invalidation the requirement was about.
    private func apply(_ outcome: ChatStreamCoalescer.Outcome) {
        if case let .publish(frames) = outcome {
            var next = surface
            for frame in frames { next.transcript.fold(frame) }
            if isAnchoredToBottom { next.scrollToBottomID += 1 }
            surface = next
        }
        syncFrameClock()
    }

    /// The clock runs for exactly as long as a window is open. A run parked on a confirmation for three minutes
    /// ticks nobody, and a run that has stopped talking stops costing frames between its words.
    private func syncFrameClock() {
        guard coalescer.isWindowOpen else {
            frameClock.stop()
            return
        }
        frameClock.start { [weak self] in self?.frameWindowClosed() }
    }

    /// One frame window ended: what it held reaches the screen as the single update it was promised, and the
    /// window re-arms for the frames still to come.
    private func frameWindowClosed() {
        apply(coalescer.tick())
    }

    // MARK: - history

    /// The conversation's stored rows, replayed into the transcript. The console does the same on open
    /// (`ChatWindow.tsx:740-933`), and this is called from the screen's `.task`.
    ///
    /// Rows never overwrite work already on screen: if a turn started while the read was in flight, or the
    /// user moved on to another conversation, what came back is stale and gets dropped.
    public func load() async {
        guard let history else { return }
        let sessionID = conversation.id
        guard loadedConversationID != sessionID, !isStreaming else { return }
        isLoadingHistory = true
        defer { isLoadingHistory = false }
        let result = await history.history(sessionId: sessionID)
        guard conversation.id == sessionID else { return }
        switch result {
        case let .success(logs):
            historyFailure = nil
            // A turn that started while the read was in flight owns the screen now. Dropping these rows is
            // right, but the conversation is not then "loaded" — marking it so would leave the screen short
            // by its own history for the rest of the visit with nothing left to re-read.
            guard transcript.turns.isEmpty else { return }
            loadedConversationID = sessionID
            // One update for the rows and the tail. `settled` rather than `commit` because this replaces the
            // transcript whole and a held delta must never be dropped by that swap — the guard above means
            // there is no read in flight to hold one, so the batch this takes is empty.
            settled { screen in
                screen.transcript = ChatTranscript(replaying: logs)
                screen.scrollToBottomID += 1
            }
        case let .failure(error):
            historyFailure = ErrorMessage.text(for: error)
        }
    }

    // MARK: - turn

    /// The send button and the Return key.
    ///
    /// A slash line takes the text and nothing else: the console's command leg never reads `imageUrls`, so a
    /// picture picked before typing `/clear` is still in the strip afterwards (`ChatWindow.tsx:986-1032`).
    ///
    /// The box empties as the send goes out, and what it held is handed to the turn so a read that dies
    /// before its first frame can put it back (`restoreComposer`).
    public func send() {
        let text = draft
        let isCommand = ChatSlashCommand.parse(text.trimmingCharacters(in: .whitespacesAndNewlines)) != nil
        // Only a message is recoverable: a command line has already reached the other channel by the time
        // this returns, and the console clears the box for it without a second thought.
        let consumed = isCommand ? nil : ConsumedComposer(text: text, images: images)
        guard send(text, consumed: consumed) else { return }
        draft = ""
        if !isCommand { images = [] }
    }

    /// Submit one line of composer text: a slash command if it parses as one, otherwise a turn.
    ///
    /// Returns false when there was nothing to send or a turn is already reading — the input bar's send button
    /// becomes a stop button while streaming (`ChatWindow.tsx:3679-3686`), and the backend refuses a second
    /// run on a session that is still busy. An unrecognised keyword is not a command and not an error:
    /// `/hello there` is a greeting, which is the console's fall-through at `:980-982`.
    @discardableResult
    public func send(_ message: String) -> Bool {
        send(message, consumed: nil)
    }

    /// `consumed` is what the composer box held when the tap came from the box itself; a send that named its
    /// message directly was never in the box, so a failed turn of that shape restores nothing.
    @discardableResult
    private func send(_ message: String, consumed: ConsumedComposer?) -> Bool {
        guard !isStreaming else { return false }
        let text = message.trimmingCharacters(in: .whitespacesAndNewlines)
        if let command = ChatSlashCommand.parse(text) {
            dispatch(command, rawText: text)
            return true
        }
        // A picture with no words is still a message — the disabled test at `:3684` reads exactly this way.
        guard !text.isEmpty || !images.isEmpty else { return false }
        stopNotice = nil
        // A new turn abandons whatever ask the previous one parked on: the run it held is gone.
        confirmationChoices = [:]
        isAnchoredToBottom = true
        let pictures = images
        consumedByOpenTurn = consumed
        // Settled first: a delta still waiting for its tick belongs to the answer before this message, and
        // must not be folded into the bubble the user has just opened.
        settled { $0.transcript.send(text, images: pictures) }
        startStream(text, images: pictures)
        return true
    }

    /// Abort the read and tell the server. Not awaited: the console fires all three legs at once and clears
    /// its loading flag itself, because a run parked on a long tool call can take a while to notice.
    public func stop() {
        guard isStreaming else { return }
        abort()
        guard let commands else { return }
        let sessionID = conversation.id
        Task { _ = await commands.command(CommandAgentRequest(sessionId: sessionID, command: .interrupt)) }
    }

    /// The system took the background allowance back before the answer came in (`DESIGN.md:183`).
    ///
    /// This read is what dies next: iOS suspends the process as soon as the grace period ends, and a stream
    /// left open behind that is the state this feature exists to prevent — `isStreaming` still true, the send
    /// button still a stop button, an answer that will never arrive. So the run goes the way a stopped run
    /// goes, `stop()` and the `INTERRUPT` that stops the server billing it, and the banner says what happened
    /// rather than blaming the network.
    private func backgroundAllowanceExpired() {
        guard isStreaming else { return }
        stop()
        composerNotice = .warning(hx("chat.background.expired"))
    }

    /// The screen is going away. The answer stays as it is, with its open cards closed, and the plan stops being
    /// followed — nobody is left to read the card. The panel itself stays, because this same screen can come back
    /// to this same conversation (`bind` returns early on an unchanged conversation, so it would not rebuild one).
    public func detach() {
        abort()
        planPanel?.stopFollowing()
        planPanel?.close()
    }

    /// Move to another conversation. The old read is aborted before the new conversation is adopted, so its
    /// frames cannot land here.
    public func bind(_ conversation: ChatConversation) {
        guard conversation != self.conversation else { return }
        abort()
        self.conversation = conversation
        // `settled` rather than `commit`: the swap drains whatever the old conversation's stream still had
        // held, so no frame of it can survive into the new conversation's empty transcript.
        settled { $0.transcript = ChatTranscript() }
        stopNotice = nil
        historyFailure = nil
        loadedConversationID = nil
        draft = ""
        // The three switches and the permission mode are per-conversation columns, and the console clears
        // them on the session change rather than letting the previous one's values sit under a new title
        // (`ChatWindow.tsx:683-705`). Same for the pictures: a half-picked send belongs to one conversation.
        composer = SessionChatConfig()
        // A plan belongs to the conversation that wrote it, and its panel is addressed by session id: the old
        // follow, its loop and its drawer go here, and the new conversation gets a panel of its own. After the
        // composer reset above, so the new panel starts from this conversation's own defaults rather than from
        // the one just left.
        retirePlanPanel()
        // The drawer's panel goes the same way — it lists one conversation's sandbox — and an open drawer is
        // put away rather than left showing the session the user just left. Its status answer belongs to that
        // conversation too, and the new one is answered by its own read (`ChatView`'s `.task(id:)`).
        retireWorkspacePanel()
        adoptWorkspacePanel()
        sandboxIsRunning = false
        // The occupancy reading is per-conversation too, and the old one's denominator is another model's
        // window: the tag goes away until this conversation's own read answers.
        contextUsage = nil
        images = []
        composerNotice = nil
        pendingCommand = nil
        // A parked ask belongs to the conversation that parked it: the panel goes with it, and so do the
        // half-made choices. `abort()` has already let go of a confirm read that was in flight.
        confirmationChoices = [:]
        // The same for a byte still on its way: the row it belongs to went with the transcript, and a spinner
        // keyed to an id nobody can reach again would never come back down.
        pendingDownloads = []
        isAnchoredToBottom = true
        commit { $0.scrollToBottomID += 1 }
    }

    private func abort() {
        streamTask?.cancel()
        streamTask = nil
        isStreaming = false
        // Nothing here is waiting on any socket any more — including a read the confirm leg replaced without
        // closing — so the allowance is given back in one go rather than as each socket notices.
        background.releaseAll()
        // A stop or a conversation switch is the user letting the turn go, not an answer that failed to
        // land: the settled panel stays settled rather than being handed back for another try.
        confirmRead = nil
        memberAnswerTask?.cancel()
        memberAnswerTask = nil
        memberAnswerRead = nil
        // And the same for the composer: the request was accepted, so refilling the box would only set up
        // a duplicate run of a turn the router is already working on.
        consumedByOpenTurn = nil
        // Settled, not just terminated: the last words before a stop are exactly the ones a window may be
        // holding, and 「a stop keeps what was already on screen」 includes what had arrived but not yet been
        // shown. They fold first, then the interruption closes over them, in one update.
        settled { $0.transcript.terminate(as: .interrupted) }
    }

    private func startStream(_ message: String, images: [String] = []) {
        let request = ChatAgentRequest(sessionId: conversation.id, message: message, imageUrls: images)
        confirmRead = nil
        start(with: .chat(request))
    }

    /// Open one read and drain it.
    ///
    /// The two streaming endpoints share the loop because they share the frame vocabulary: the confirm
    /// response carries the resumed run, and that run may park on another confirmation
    /// (`ChatWindow.tsx:2036-2331`). Folding both through `receive` is what makes the recursion one code path
    /// instead of the three copies the console had to write; the read loop is never blocked waiting for an
    /// answer, which `DESIGN.md` line 300 lists as a defect not to reproduce.
    private func start(with target: ReadTarget) {
        isStreaming = true
        stopNotice = nil
        commit { $0.scrollToBottomID += 1 }
        // A new read starts with no window standing. Both doors into here (`send`, `sendAnswer`) settle for the
        // same reason, so this is the belt on the braces: a delta held over from the previous socket would
        // land inside the wrong answer. Guarded so a clean read start costs the view nothing.
        settleWindow()
        let reader = streaming
        let confirmer = confirming
        let sessionID = conversation.id
        // The socket is open from here, so the allowance is taken from here, and the read gives it back on
        // whichever path it ends on: the end frame, a socket that closed, a throw, or the cancellation `abort`
        // asked for. Expiry is the one thing here the read cannot see coming, and it is said to
        // `backgroundAllowanceExpired` rather than left to hang the screen on (`DESIGN.md:183`).
        let background = self.background
        let held = background.take(onExpire: { [weak self] in self?.backgroundAllowanceExpired() })
        streamTask = Task { [weak self] in
            defer { background.release(held) }
            let stream: AsyncThrowingStream<ChatEvent, any Error>
            switch target {
            case let .chat(request):
                stream = await reader.chat(request)
            case let .confirm(request):
                guard let confirmer else { return }
                stream = await confirmer.confirm(request)
            }
            // The answer may have been overtaken by a stop or a conversation switch while the request was
            // still going out; either way this read has nobody to report to any more.
            guard self?.conversation.id == sessionID, !Task.isCancelled else { return }
            do {
                for try await event in stream {
                    guard !Task.isCancelled else { return }
                    self?.receive(event)
                    // The turn's last word was said. Letting go of the stream is what closes the socket
                    // behind it, so a read that outlived its own end frame cannot linger on the router.
                    if self?.transcript.isTerminated ?? true { break }
                }
                self?.readerClosed()
            } catch {
                self?.readerFailed(with: error)
            }
        }
    }

    /// One frame folded in, and the terminal frames settle the screen's state as they arrive rather than
    /// waiting for the socket to close.
    ///
    /// What arrives at the screen is the same fold in the same order as before coalescing; what is now
    /// different is how often it is handed over. A growing delta waits for the frame window
    /// (`ChatStreamCoalescer`), and a frame that changes the screen's state — an end, a failure, a tool call,
    /// a result, a confirmation, a keep-alive — goes through at once with everything that was waiting in
    /// front of it. The two matter together here: the loop below reads `transcript.isTerminated` to decide
    /// whether to let go of the socket, and nothing that could terminate the turn is ever held back, so that
    /// test still sees the truth on the frame that carries it (`DESIGN.md` §10 性能).
    private func receive(_ event: ChatEvent) {
        // Any frame at all is proof the answer reached the run: the server only writes on a stream it has
        // accepted (`DefaultAgentRunner.kt:342-442`). That is also the moment the composer stops being
        // recoverable — and, since a parked ask implies frames already landed, the moment a later
        // confirmation read inherits no composer text of its own.
        confirmRead?.sawFrame = true
        consumedByOpenTurn = nil
        apply(coalescer.accept(event))
        // The plan's two frames are the ones the fold throws away, so whatever they cause has to be read off the
        // raw event here — after `apply`, so the words already in the window are on screen first.
        reactToPlanFrame(event)
        // A member's terminal frame is not the stream's last word. It arrives on the lead's channel and closes
        // only the member's own bubble (`handleMemberEvent`, `ChatWindow.tsx:1278-1290`), while the lead's turn
        // is still open waiting for the delegation to come back — so it settles nothing here.
        let isMemberFrame = event.memberRunID != nil
        switch event {
        case let .failure(failure):
            guard !isMemberFrame else { break }
            isStreaming = false
            stopNotice = .failed(text: hx("chat.error.occurred", errorSentence(failure)))
        case .end:
            guard !isMemberFrame else { break }
            isStreaming = false
        case .keepAlive:
            break
        default:
            break
        }
    }

    /// The read ended on its own. A stream that never saw an end frame was cut, which is the console's
    /// disconnect case (`ChatWindow.tsx:2361-2366`): words only when nothing got through.
    private func readerClosed() {
        streamTask = nil
        isStreaming = false
        followUpSandboxStatus()
        followUpContextUsage()
        // The socket opened, so the request left: a stream that goes quiet is the console's disconnect
        // (`ChatWindow.tsx:2361-2366`), not a send that failed to go out, and the box stays empty.
        consumedByOpenTurn = nil
        // The window first, before anything below reads the transcript. A stream that closed just after its
        // last word — with that word still waiting for a tick — would otherwise be read as an answer that
        // carried nothing, and `isTerminated` would be answered for a transcript that is not the one the user
        // is owed.
        settleWindow()
        let sawContent = transcript.turns.last?.hasContent ?? false
        guard !transcript.isTerminated else {
            settleAnswerDelivery(reason: nil)
            return
        }
        settled { $0.transcript.terminate(as: .interrupted) }
        // A rolled-back answer has already said why in its own words; a stream that carried nothing at all
        // is the disconnect the console names.
        if settleAnswerDelivery(reason: nil) == false, !sawContent {
            stopNotice = .disconnected
        }
    }

    /// The read threw. Cancellation is not a failure — the abort that got us here already settled the
    /// transcript, and a stop has nothing to apologise for.
    private func readerFailed(with error: any Error) {
        streamTask = nil
        isStreaming = false
        guard !Task.isCancelled, !(error is CancellationError) else { return }
        // A run that threw can still have created the container before it failed, so the entry is asked again.
        followUpSandboxStatus()
        // And the same for the reading: the calls a failed run did make are billed and counted.
        followUpContextUsage()
        settled { $0.transcript.terminate(as: .interrupted) }
        let detail = transportSentence(error)
        if settleAnswerDelivery(reason: detail) { return }
        restoreComposer()
        stopNotice = .failed(text: detail)
    }

    /// Put back what the dead turn took out of the composer.
    ///
    /// Only a read that threw before its first frame gets here: `readerClosed` is a stream the server
    /// accepted, `abort` is the user letting a live turn go, and `receive` has already cleared this for any
    /// turn that said something. The bubble stays where it is — this screen does not take words back out of
    /// a transcript, and the row is the record that the turn was attempted.
    private func restoreComposer() {
        guard let consumed = consumedByOpenTurn else { return }
        consumedByOpenTurn = nil
        // Whatever the user put in the box in the meantime wins: a recovery that poured the dead turn's
        // text on top of it would lose newer input to an older one.
        if draft.isEmpty { draft = consumed.text }
        if images.isEmpty { images = consumed.images }
    }

    /// Close out a confirm read.
    ///
    /// An answer whose stream produced no frame never reached the run, so the decision goes back on the panel
    /// instead of sitting settled on a transcript the server never saw it (`ChatWindow.tsx:1338-1347` reads
    /// the response body for exactly this news, and `confirmSubmitFailed` is what it says when the call
    /// throws). Returns true when it rolled an answer back, which is the one case that has already said why.
    @discardableResult
    private func settleAnswerDelivery(reason: String?) -> Bool {
        guard let attempt = confirmRead else { return false }
        confirmRead = nil
        return deliver(attempt, reason: reason)
    }

    /// The same close-out for a member's leg, which is tracked apart because it runs beside the lead's read.
    @discardableResult
    private func settleMemberAnswer(reason: String?) -> Bool {
        guard let attempt = memberAnswerRead else { return false }
        memberAnswerRead = nil
        return deliver(attempt, reason: reason)
    }

    private func deliver(_ attempt: ConfirmRead, reason: String?) -> Bool {
        guard !attempt.sawFrame else {
            // Delivered: the choices it carried are no longer anybody's pending decision.
            for tool in attempt.tools { confirmationChoices[tool.toolId] = nil }
            return false
        }
        settled { $0.transcript.reopenConfirmation() }
        stopNotice = .failed(text: hx("chat.confirm.submitFailed", reason ?? hx("chat.error.disconnected")))
        followTail()
        return true
    }

    private func followTail() {
        guard isAnchoredToBottom else { return }
        commit { $0.scrollToBottomID += 1 }
    }

    // MARK: - tool confirmation

    /// The block that is waiting on the user, or `nil` when nothing is parked.
    ///
    /// An answered block is not pending even though it stays on screen: it is the record of a decision, and
    /// a nested ask puts its own block under it (`ChatWindow.tsx:2036-2130`).
    public var pendingConfirmation: ChatPendingConfirmation? { transcript.pendingConfirmation }

    /// Whether the answer controls may be offered at all. Both halves matter: a run parked on a question,
    /// and a host with somewhere to send the answer.
    public var canAnswerConfirmation: Bool { confirming != nil && pendingConfirmation != nil }

    /// Whether this block is the live question, which is what tells a settled panel to stop offering choices.
    public func isWaitingConfirmation(_ segmentID: Int) -> Bool {
        pendingConfirmation?.segmentID == segmentID
    }

    /// The row's choice. A row nobody touched is approved, which is the console's default too — its modal
    /// offers 「允许执行」 as the affirmative action (`ChatWindow.tsx:1712-1728`).
    public func confirmationChoice(for toolId: String) -> ToolConfirmAnswer {
        confirmationChoices[toolId] ?? .allowed
    }

    public func setConfirmationChoice(_ answer: ToolConfirmAnswer, for toolId: String) {
        confirmationChoices[toolId] = answer
    }

    /// The panel's single 确定 control: send each row's own choice.
    public func submitConfirmation() {
        guard let pending = pendingConfirmation, confirming != nil else { return }
        sendAnswer(for: pending) { confirmationChoice(for: $0.toolId) }
    }

    /// One answer for every row — 「全部批准」, 「全部拒绝」, 「全部总是允许」.
    ///
    /// The console only has the first two, and only as bulk buttons on the modal (`specs/02-session-chat.md`
    /// line 517-526); the third is the iOS affordance `FEATURES.md` §3 asks for, and it is the answer that
    /// forces the request out of bulk mode.
    public func answerAllConfirmation(_ choice: ToolConfirmAnswer) {
        guard let pending = pendingConfirmation, confirming != nil else { return }
        for tool in pending.tools { confirmationChoices[tool.toolId] = choice }
        sendAnswer(for: pending) { _ in choice }
    }

    /// Settle the block on screen, then send the answer and read the stream it comes back with.
    ///
    /// The order matters: the block settles first so a nested ask arriving on the response has somewhere to
    /// go, and so the panel cannot be tapped twice while the request is still going out.
    private func sendAnswer(
        for pending: ChatPendingConfirmation,
        choosing: (ChatPendingTool) -> ToolConfirmAnswer
    ) {
        let tools = pending.tools
        guard !tools.isEmpty else { return }
        let answers = tools.map { choosing($0) }
        let request = Self.confirmRequest(sessionID: conversation.id, pending: pending, answers: answers)
        var decisions: [String: ToolConfirmAnswer] = [:]
        for (tool, answer) in zip(tools, answers) { decisions[tool.toolId] = answer }
        settled { $0.transcript.resolveConfirmation(decisions) }
        if pending.childRunId != nil {
            postMemberAnswer(request, tools: tools)
            return
        }
        confirmRead = ConfirmRead(tools: tools)
        start(with: .confirm(request))
    }

    /// Post a member's answer without becoming the reader.
    ///
    /// The member run is parked inside a tool call of the lead, whose stream is still open, and the resumed
    /// output continues on that stream: the answer itself comes back as a bare End and nothing else
    /// (`DefaultAgentRunner.kt:400-441`, and `ChatWindow.tsx:1294-1348` reads the body only for an ErrorEvent).
    /// So this leg reads for the news of its own delivery and for nothing else. Folding that End into the
    /// transcript would terminate the lead's live turn and drop every frame after it, and taking over
    /// `streamTask` would leave the lead's read running with no handle left to stop it.
    private func postMemberAnswer(_ request: ConfirmAgentRequest, tools: [ChatPendingTool]) {
        guard let confirmer = confirming else { return }
        let sessionID = conversation.id
        memberAnswerRead = ConfirmRead(tools: tools)
        memberAnswerTask = Task { [weak self] in
            let stream = await confirmer.confirm(request)
            do {
                for try await event in stream {
                    guard self?.conversation.id == sessionID, !Task.isCancelled else { return }
                    self?.memberAnswerRead?.sawFrame = true
                    if case let .failure(failure) = event {
                        // The orchestrator says the run is not waiting on this answer any more — said out
                        // loud rather than folded into a turn that is still running.
                        self?.stopNotice = .failed(text: self?.errorSentence(failure) ?? "")
                    }
                    if case .end = event { break }
                }
            } catch {
                guard let self, !Task.isCancelled, !(error is CancellationError) else { return }
                self.settleMemberAnswer(reason: self.transportSentence(error))
                return
            }
            guard let self, self.conversation.id == sessionID else { return }
            self.settleMemberAnswer(reason: nil)
        }
    }

    /// The body `POST /api/router/agent/confirm` takes for these answers.
    ///
    /// Four rules, read off `AgentRequest.kt:120-168` and `DefaultAgentRunner.kt:342-442`:
    ///
    /// * `toolInfoList` always names every row of the block — the server reads it for the tools either way.
    /// * A uniform approve or a uniform refusal goes bulk, which is the shape the console sends
    ///   (`ChatWindow.tsx:1730-1749`) and the only shape `isConfirmed` alone can express.
    /// * An answer that asks for a standing rule has to go per-tool: `alwaysAllow` lives on
    ///   `ToolConfirmResult` and has no bulk leg. A mixed panel goes per-tool too, with `isConfirmed`
    ///   reading as "everything in here was approved", which is what makes a panel holding one refusal not
    ///   look like a batch approval.
    /// * A member run is always bulk, because the server ANDs a per-tool list into one answer for the whole
    ///   round (`DefaultAgentRunner.kt:427`), so a mixed list would silently deny the tools the user
    ///   approved; the `childRunId` goes back on the request that parked the run.
    static func confirmRequest(
        sessionID: String,
        pending: ChatPendingConfirmation,
        answers: [ToolConfirmAnswer]
    ) -> ConfirmAgentRequest {
        let tools = pending.tools
        let info = tools.map { ConfirmAgentRequest.ToolInfo(toolId: $0.toolId, toolName: $0.toolName) }
        let approved = answers.isEmpty ? false : answers.allSatisfy(\.isConfirmed)
        if let runID = pending.childRunId {
            return ConfirmAgentRequest(
                sessionId: sessionID,
                isConfirmed: approved,
                toolInfoList: info,
                childRunId: runID
            )
        }
        let wantsRule = answers.contains(where: \.addsPermissionRule)
        if !wantsRule, Set(answers.map(\.isConfirmed)).count <= 1 {
            return ConfirmAgentRequest(sessionId: sessionID, isConfirmed: approved, toolInfoList: info)
        }
        let decisions = zip(tools, answers).map {
            ConfirmAgentRequest.Decision(
                toolId: $0.toolId,
                toolName: $0.toolName,
                confirmed: $1.isConfirmed,
                alwaysAllow: $1.addsPermissionRule
            )
        }
        return ConfirmAgentRequest(
            sessionId: sessionID,
            isConfirmed: approved,
            toolInfoList: info,
            toolResults: decisions
        )
    }

    // MARK: - composer config

    /// The conversation's capability flags and stored settings, read when the screen opens.
    ///
    /// The console does the same read on mount (`ChatWindow.tsx:718-737`) and it is the only source for the
    /// four gates in the matrix at spec line 449-462 — a screen that guessed them would offer a switch the
    /// model behind the conversation cannot honour.
    public func loadComposerConfig() async {
        guard let configReader else { return }
        let sessionID = conversation.id
        let result = await configReader.sessionConfig(sessionId: sessionID)
        guard conversation.id == sessionID else { return }
        switch result {
        case let .success(row):
            composer = SessionChatConfig(from: row)
            // The stored `enablePlan` is what the console's two plan effects read
            // (`ChatWindow.tsx:642-646`, `:649-669`), so this is where the follow starts on a real conversation.
            syncPlanPanel()
        case let .failure(error):
            // The switches keep the DTO's own defaults, which is why the reason they may be wrong has to be
            // said out loud rather than swallowed.
            composerNotice = .error(ErrorMessage.text(for: error))
        }
    }

    // MARK: - plan

    /// The calls that open the drawer by themselves (`ChatWindow.tsx:1500`). `plan_exit` is deliberately absent:
    /// its result closes the plan rather than showing it, and it is not gated on the switch the way these two are.
    static let planOpeningTools: Set<String> = ["plan_write", "plan_enter"]
    /// The one tool whose result breaks the answer bubble (`:1566-1591`).
    static let planExitTool = "plan_exit"

    /// This conversation's panel, and the only reader of its plan.
    ///
    /// One per conversation: `bind` retires the old one before the new session id goes in, so a 2 s loop can
    /// never answer for a plan the user has already left — the same reason the stream is aborted there.
    private func adoptPlanPanel() {
        guard let planReading else { return }
        let panel = PlanPanelViewModel(
            sessionId: conversation.id,
            reading: planReading,
            isEnabled: composer.enablePlan
        )
        panel.onCurrentPlanRead = { [weak self] note in self?.currentPlanRead(note) }
        // The card in the stream is a row of this object's transcript, and it reads the panel's expansion and
        // its spinner through the two bridges below — so the panel's changes have to count as changes here, or a
        // tap on the card would move nothing.
        planObserver = panel.objectWillChange.sink { [weak self] in
            MainActor.assumeIsolated { self?.objectWillChange.send() }
        }
        planPanel = panel
        syncPlanPanel()
    }

    /// Let go of the panel this conversation was following and take one for the conversation now on screen.
    ///
    /// The follow task is cancelled because its reply would arrive addressed to the session just left, and the
    /// callback goes with the panel: `currentPlanRead` writes into *this* transcript, and a late reading of the
    /// old plan has no business landing in the new conversation's answer.
    private func retirePlanPanel() {
        planFollowTask?.cancel()
        planFollowTask = nil
        planObserver = nil
        planPanel?.stopFollowing()
        planPanel = nil
        isPlanPanelPresented = false
        adoptPlanPanel()
    }

    /// The drawer's panel, for the conversation now on screen. Building it reads nothing: the drawer's own
    /// `.task` is what asks for the status and the listing, so a panel sitting here unopened has cost nothing.
    private func adoptWorkspacePanel() {
        guard let workspace else { return }
        workspacePanel = WorkspaceViewModel(
            workspace: workspace,
            sessionId: conversation.id,
            share: share
        )
    }

    /// Take a panel for the conversation now on screen, and put away a drawer that is showing the one just left.
    private func retireWorkspacePanel() {
        workspacePanel = nil
        isWorkspacePresented = false
        adoptWorkspacePanel()
    }

    /// Keep the panel in step with the conversation's plan switch: the flag that both starts the reading and
    /// arms the loop, and the flag that stops both (`:642-646` against `:649-669`).
    private func syncPlanPanel() {
        guard let planPanel else { return }
        planPanel.isEnabled = composer.enablePlan
        if composer.enablePlan {
            planFollowTask = Task { await planPanel.startFollowing() }
        } else {
            planPanel.stopFollowing()
        }
    }

    /// The plan's two frames, answered where the fold cannot see them.
    ///
    /// `ChatTranscript` drops every plan frame — no card, no break in the sentence — so the raw event is the only
    /// place that still knows which tool was called, and this is the only place the two side effects
    /// (`ChatWindow.tsx:1499-1504`, `:1566-1591`) can live. A member's plan frame belongs to that member's run,
    /// and neither of the console's guards reaches it.
    private func reactToPlanFrame(_ event: ChatEvent) {
        guard event.memberRunID == nil else { return }
        switch event {
        case let .toolCall(call) where Self.planOpeningTools.contains(call.toolName):
            // `&& enablePlan` is the console's own guard at `:1500`. A screen with no panel wired has no card to
            // draw either, and its toolbar entry is already off.
            guard composer.enablePlan, let planPanel else { return }
            isPlanPanelPresented = true
            // The load starts on the call, not on the drawer's appearance: the reading is what puts the card in
            // the stream (`:1503`).
            planFollowTask = Task { await planPanel.startFollowing() }
        case let .toolResult(result) where result.toolName == Self.planExitTool:
            // The plan phase is over: the reading stops, and the execution phase says its next sentence in a
            // bubble of its own. `settled` is what `flushUI()` is — the words still in the frame window belong
            // to the plan phase and have to land above the break, not under it.
            planPanel?.exitCurrentPlan()
            settled { $0.transcript.endAnswerForPlanExit() }
        default:
            break
        }
    }

    /// The one place a plan reading reaches the transcript, and the one place the reducer's card is written.
    ///
    /// A gone plan (`nil`) writes nothing, which is the whole of the console's freeze: the card keeps the last
    /// reading it was given and stops being followed (`:2576-2584`). A repeat of the same plan writes nothing
    /// either, which is what keeps a 2 s poll from rewriting the stream twice a second.
    private func currentPlanRead(_ note: PlanNote?) {
        guard let note, planPanel?.isFollowing == true, transcript.planCard != note else { return }
        settled { screen in
            screen.transcript.showPlan(note)
            if isAnchoredToBottom { screen.scrollToBottomID += 1 }
        }
    }

    /// The card and the drawer are one plan with one expansion (`ChatWindow.tsx:593`, whose
    /// `currentPlanExpanded` both renderings read), so the stream's card reports the panel's state rather than
    /// keeping a fold-out of its own.
    public var isPlanExpanded: Bool { planPanel?.isCurrentPlanExpanded ?? true }
    /// Whether the loop behind both cards is actually running — the console's `Live` line, off once `plan_exit`
    /// let the plan go.
    public var isPlanLive: Bool { planPanel?.isLive ?? false }
    public func togglePlanExpansion() { planPanel?.toggleCurrentPlan() }

    // MARK: - pictures

    /// How much of one request body the router's edge will take: 1 MB.
    ///
    /// The console puts no number on its own strip — its picker appends every file it is handed
    /// (`ChatWindow.tsx:2442-2468`) off a bare `multiple` input (`:3692-3699`) — so the only limit that is
    /// really there is the server's, and the smaller byte answers for it. The router would take 16 MB
    /// (`harnax-session-router/src/main/resources/application.yml:31`, its WebClient at `:104`), but the
    /// public entry this console is deployed behind sets no `client_max_body_size` on the streaming
    /// `location` (`harnax-deploy/nginx.conf:72-101`), which leaves nginx's own 1 MB default and a bare 413
    /// page (`:196-197`). A turn bigger than that dies before the model sees it.
    public static let streamBodyBudget = 1_048_576

    /// The part of that body the attachments may have.
    ///
    /// The remainder has to hold the prompt, the session's identity and the envelope, none of which is
    /// bounded here — so this margin is ours rather than the server's: the number above is the anchor, this
    /// one is how much of it we are willing to hand to pictures.
    public static let imagePayloadBudget = streamBodyBudget - 65_536

    /// The attachment leg's size, counted the way the edge counts it: bytes on the wire.
    ///
    /// A data URL goes into the JSON body whole, so its utf-8 length is what the body pays for it.
    private static func bodyBytes(of pictures: [String]) -> Int {
        pictures.reduce(0) { $0 + $1.utf8.count }
    }

    /// The picture button's tap: may the sheet open?
    ///
    /// A model with no vision gets the warning instead of a picker it could not use, so the view has no
    /// capability branch of its own (`ChatWindow.tsx:3493-3505`).
    public func requestImages() -> Bool {
        guard canPickImages else {
            composerNotice = .warning(hx("chat.model.noVision"))
            return false
        }
        return true
    }

    /// The camera row's tap: may the camera be opened here?
    ///
    /// Two gates, in this order. Vision comes first, exactly as in ``requestImages()``, so a model that could
    /// not read the shot never sees either sheet. Then the device: a camera is not on the Simulator and not on
    /// every handset, and the answer to that has to be a sentence rather than a sheet that opens only to fail —
    /// the same rule that keeps every chip in the row tappable, a muted style plus a warning instead of a dead
    /// control (`Sources/HarnaxFeatures/Chat/ChatView.swift:366-368`).
    ///
    /// - Parameter hasCamera: what this host's probe says, `ChatCameraDevice.isAvailable`
    ///   (`Sources/HarnaxFeatures/Chat/ChatCameraPicker.swift:89-104`). It is an argument rather than a read
    ///   because `UIImagePickerController` does not exist on the macOS test host (`Package.swift:7`), and the
    ///   refusal is the part worth being able to assert — the same split `ChatImagePicker.swift:63-70`
    ///   documents for the photo library.
    public func requestCamera(hasCamera: Bool) -> Bool {
        guard canPickImages else {
            composerNotice = .warning(hx("chat.model.noVision"))
            return false
        }
        guard hasCamera else {
            composerNotice = .warning(hx("chat.image.noCamera"))
            return false
        }
        return true
    }

    /// What the picker produced.
    ///
    /// Only `data:image…` strings are kept: the runtime reads any other string as a path inside the sandbox
    /// and fails (`HarnessAgentWrapper.kt:814-829`), so a URL that looked like a picture would only ever
    /// become an error bubble later. `ChatImageData` builds the strings; nothing here asks the network.
    ///
    /// Nothing says how *many* may be attached, because the ceiling is bytes and not a count: each one that
    /// still fits joins the strip, and only the ones that would push the body past
    /// ``imagePayloadBudget`` are refused — the whole batch is not lost with them. Refusing them here is the
    /// point, since the alternative is the unreadable 413 the edge answers with after the send.
    public func addImages(_ dataURLs: [String]) {
        guard canPickImages else {
            composerNotice = .warning(hx("chat.model.noVision"))
            return
        }
        var filled = Self.bodyBytes(of: images)
        var overLimit = 0
        var accepted: [String] = []
        for dataURL in dataURLs where ChatImageData.isDataURL(dataURL) {
            let cost = dataURL.utf8.count
            guard filled + cost <= Self.imagePayloadBudget else {
                overLimit += 1
                continue
            }
            filled += cost
            accepted.append(dataURL)
        }
        if accepted.count + overLimit != dataURLs.count {
            composerNotice = .warning(hx("chat.image.rejected"))
        }
        if overLimit > 0 {
            composerNotice = .warning(hx("chat.image.overLimit"))
        }
        images.append(contentsOf: accepted)
    }

    public func removeImage(at index: Int) {
        guard images.indices.contains(index) else { return }
        images.remove(at: index)
    }

    // MARK: - capability switches

    /// Deep thinking. Three answers, in the console's own order (`ChatWindow.tsx:3506-3572`): a model that
    /// cannot reason gets a warning and no command, a model that *must* reason gets an explanation and no
    /// command at all, and only then does the switch move.
    public func toggleThink() {
        if !composer.modelSupportReasoning {
            composerNotice = .warning(hx("chat.model.noReasoning"))
            return
        }
        if composer.thinkLockedOn {
            // The console returns before it computes a new value, so a locked model never even sees the switch
            // move (`:3512-3517`), and the server would refuse the attempt the same way
            // (`DefaultAgentRunner.kt:961-970`).
            composerNotice = .info(hx("chat.model.thinkingRequired"))
            return
        }
        flip(.think, capability: "thinking")
    }

    /// Web search, gated by the model's internet flag and nothing else (`ChatWindow.tsx:3573-3589`).
    public func toggleSearch() {
        if !composer.canToggleSearch {
            composerNotice = .warning(hx("chat.model.noInternet"))
            return
        }
        flip(.search, capability: "search")
    }

    /// Plan has no model gate at all (`ChatWindow.tsx:3590-3600`) — it is a session column, not a capability.
    public func togglePlan() {
        flip(.plan, capability: "plan")
    }

    /// The permission picker's row. Same value is a no-op, then optimistic, then back out if the server says
    /// no (`ChatWindow.tsx:3601-3638`).
    ///
    /// A `task-` conversation is not offered this at all: the runtime refuses the command outright for those
    /// ids (`DefaultAgentRunner.kt:1025-1027`), so an option that could only ever fail is left out rather than
    /// shown and retracted.
    public func selectPermission(_ mode: ChatPermissionMode) {
        guard canPickPermission else {
            composerNotice = .warning(hx("chat.permission.taskRefused"))
            return
        }
        guard mode != composer.permissionMode else { return }
        let previous = composer.permissionMode
        composer.permissionMode = mode
        Task { [weak self] in
            guard let self else { return }
            if await self.sendSilent(.permission, args: mode.wireValue) { return }
            // Only if the user has not since chosen a third mode; the console reverts blindly.
            if self.composer.permissionMode == mode { self.composer.permissionMode = previous }
        }
    }

    /// The five modes in the order the console's dropdown lists them (`ChatPermissionMode.allCases`).
    public var permissionOptions: [ChatPermissionMode] { ChatPermissionMode.allCases }

    private enum ComposerFlag {
        case think, search, plan
    }

    private func value(of flag: ComposerFlag) -> Bool {
        switch flag {
        case .think: return composer.enableThink
        case .search: return composer.enableSearch
        case .plan: return composer.enablePlan
        }
    }

    private func apply(_ flag: ComposerFlag, _ value: Bool) {
        switch flag {
        case .think: composer.enableThink = value
        case .search: composer.enableSearch = value
        case .plan:
            composer.enablePlan = value
            // The panel reads the same flag the console's two plan effects read, on the optimistic write and on
            // the rollback alike (`ChatWindow.tsx:642-646`), so a switch the server refused stops the follow it
            // never got.
            syncPlanPanel()
        }
    }

    /// Move one flag ahead of the server, then send `ENABLE`/`DISABLE` with the capability name
    /// (`ChatWindow.tsx:3506-3600`: `setEnableThink(newValue)` first, revert on a `false`).
    ///
    /// The capability names are the server's whitelist — `search`, `thinking`, `plan`
    /// (`DefaultAgentRunner.kt:1052-1053`); a fourth spelling would be refused as `Unknown capability`.
    private func flip(_ flag: ComposerFlag, capability: String) {
        let previous = value(of: flag)
        let next = !previous
        apply(flag, next)
        Task { [weak self] in
            guard let self else { return }
            if await self.sendSilent(next ? .enable : .disable, args: capability) { return }
            if self.value(of: flag) == next { self.apply(flag, previous) }
        }
    }

    /// `sendSilentCommand` (`ChatWindow.tsx:2408-2430`): no bubble, and the caller rolls back on the answer.
    ///
    /// Answers false when the command did not land. The console only calls a failure a failure when the server
    /// said `success === false` *and* supplied a message (`:2422`), which leaves a bare `false` keeping its
    /// switch flipped — that reads as an oversight, so this side rolls back on any `false` and shows the
    /// server's sentence when it gave one.
    @discardableResult
    private func sendSilent(_ command: AgentCommandType, args: String) async -> Bool {
        guard let commands else {
            composerNotice = .error(hx("chat.command.unwired"))
            return false
        }
        let sessionID = conversation.id
        let result = await commands.command(CommandAgentRequest(sessionId: sessionID, command: command, args: args))
        guard conversation.id == sessionID else { return true }
        switch result {
        case .failure:
            composerNotice = .error(Self.commandSentence(for: result))
            return false
        case let .success(reply):
            guard reply.success != false else {
                composerNotice = .warning(Self.commandSentence(for: result))
                return false
            }
            return true
        }
    }

    // MARK: - slash commands

    /// Where a recognised command goes.
    ///
    /// Two of the nine need a word with the user first, and the rest go straight through: clear behind a
    /// confirmation and stop-sandbox behind a status read and then a confirmation
    /// (`ChatWindow.tsx:3639-3677`, spec lines 318-322).
    private func dispatch(_ command: ChatSlashCommand, rawText: String) {
        switch command.command {
        case .clear:
            pendingCommand = ChatPendingCommand(command: command, rawText: rawText)
        case .stopSandbox:
            checkSandbox(command: command, rawText: rawText)
        default:
            run(command: command, rawText: rawText)
        }
    }

    /// The toolbar's Clear button. A toolbar tap has no typed line to draw, which is why its `rawText` is
    /// empty: the console's `handleClearChat` (`ChatWindow.tsx:2678-2708`) sends silently and empties the
    /// transcript instead of adding to it.
    ///
    /// Dead while a run is reading, like the send button: the console leaves both chips disabled for the
    /// length of a turn (`ChatWindow.tsx:3679-3686`) because the backend refuses a second command on a busy
    /// session — and clearing the transcript would pull it out from under the read still folding into it.
    public func requestClear() {
        guard !isStreaming else { return }
        pendingCommand = ChatPendingCommand(command: ChatSlashCommand(command: .clear), rawText: "")
    }

    /// The toolbar's Stop-sandbox button, behind the same status read as the typed form.
    public func requestStopSandbox() {
        guard !isStreaming else { return }
        checkSandbox(command: ChatSlashCommand(command: .stopSandbox), rawText: "")
    }

    /// The compaction entry. It needs no confirmation: the console's menu item is wired straight to
    /// `handleCompact` (`harnax-webui/src/pages/session/components/ChatWindow.tsx:3717-3726`), because
    /// compaction is the one command that leaves the conversation itself untouched — the archive still holds
    /// every original bubble. Dead while a run reads like the other two entries, since the backend refuses a
    /// second command on a busy session and compacting mid-turn would rewrite the context the run is reading.
    public func requestCompact() {
        guard !isStreaming else { return }
        run(command: ChatSlashCommand(command: .compact), rawText: "")
    }

    /// Ask the runtime whether the sandbox is up, and only offer the confirmation when it answers yes
    /// (`ChatWindow.tsx:3639-3664`).
    ///
    /// A status read that *failed* is not the same news as a sandbox that is down, and the console keeps them
    /// apart — the second is a warning, the first an error. Both refuse to send.
    private func checkSandbox(command: ChatSlashCommand, rawText: String) {
        guard let workspace else {
            composerNotice = .error(hx("chat.command.unwired"))
            return
        }
        let sessionID = conversation.id
        Task { [weak self] in
            guard let self else { return }
            guard case let .success(status) = await workspace.sandboxStatus(sessionId: sessionID) else {
                self.composerNotice = .error(hx("chat.sandbox.checkFailed"))
                return
            }
            guard self.conversation.id == sessionID else { return }
            // A turn can have started while the status read was out; its confirmation would land on a busy
            // session, so the offer goes back the way it came.
            guard !self.isStreaming else { return }
            guard status == .running else {
                // Idle, and a reply that said nothing about this conversation, both read as "not running" —
                // the console's test is `res.code === 200 && res.data?.active` and its `else` is one warning.
                self.composerNotice = .warning(hx("chat.sandbox.notRunning"))
                return
            }
            self.pendingCommand = ChatPendingCommand(command: command, rawText: rawText)
        }
    }

    /// Ask once whether this conversation has a sandbox, and let the toolbar's drawer entry follow the answer.
    ///
    /// Nothing is said when the read fails: an entry that is not there is a quiet state, and the user has not
    /// asked for anything yet — unlike `/stop-sandbox`, where a refused command has to explain itself.
    public func refreshSandboxStatus() async {
        guard let workspace else { return }
        let sessionID = conversation.id
        var running = false
        if case let .success(status) = await workspace.sandboxStatus(sessionId: sessionID) {
            running = status == .running
        }
        // The id is the identity of the answer: a reply for the conversation the user has already moved on from
        // must not light this one's entry.
        guard conversation.id == sessionID else { return }
        sandboxIsRunning = running
    }

    /// Re-ask at a turn's last word.
    ///
    /// A sandbox is not there when the send goes out — the run's first tool call is what creates the container —
    /// so a screen that only read the status on open would never show the entry, and the user would have to
    /// leave and come back to reach the files the turn just wrote.
    private func followUpSandboxStatus() {
        guard workspace != nil else { return }
        Task { [weak self] in await self?.refreshSandboxStatus() }
    }

    /// Ask the runtime how full this conversation's context is, and let the header's tag follow the answer.
    ///
    /// The console's `loadContextUsage` (`harnax-webui/src/pages/session/index.tsx:142-154`) overwrites on
    /// every read, including with null: a business failure means no instance holds this session, an absent
    /// `data` means it was never bound, and both hide the tag rather than showing a `0%` for a context the
    /// router cannot see. Nothing is said when the read fails — the tag not being there is a state, and the
    /// user has not asked for anything.
    public func refreshContextUsage() async {
        guard let reader = contextReader else { return }
        let sessionID = conversation.id
        contextUsageGeneration += 1
        let generation = contextUsageGeneration
        var reading: ContextUsage?
        if case let .success(usage) = await reader.contextUsage(sessionId: sessionID), usage.isReadable {
            reading = usage
        }
        // The id is the identity of the answer, same as the status read above: a reply about the conversation
        // the user has already moved on from must not put a number under this one's title. The generation is
        // its order: this read overwrites whatever it brings, so an older ask that lands after a newer one
        // would put the number from before that newer one back on screen.
        guard conversation.id == sessionID, generation == contextUsageGeneration else { return }
        contextUsage = reading
    }

    /// Re-read at a turn's last word.
    ///
    /// The reading's numerator is the *last billed call*, so mid-turn it is one answer behind; a turn is
    /// exactly what changes it (`harnax-webui/src/pages/session/components/ChatWindow.tsx:2503-2507` re-reads
    /// on the same edge).
    private func followUpContextUsage() {
        guard contextReader != nil else { return }
        Task { [weak self] in await self?.refreshContextUsage() }
    }

    /// The confirmation's confirm button.
    public func confirmPendingCommand() {
        guard let pending = pendingCommand else { return }
        pendingCommand = nil
        run(command: pending.command, rawText: pending.rawText)
    }

    /// The confirmation's cancel button: nothing left the composer and nothing entered the transcript.
    public func cancelPendingCommand() {
        pendingCommand = nil
    }

    /// One command down `POST /api/router/agent/command`.
    ///
    /// `rawText` is the line the user typed, which becomes the user bubble before the request goes out
    /// (`ChatWindow.tsx:992-998`); empty means a toolbar tap, whose answer the console keeps out of the
    /// transcript and puts in a toast instead. Either way the answer is the server's sentence and not a
    /// stream — the command channel is plain JSON, so `isStreaming` here only parks the composer for the
    /// length of one request.
    private func run(command: ChatSlashCommand, rawText: String) {
        guard let client = commands else {
            composerNotice = .error(hx("chat.command.unwired"))
            return
        }
        stopNotice = nil
        isAnchoredToBottom = true
        if !rawText.isEmpty { settled { $0.transcript.appendUserMessage(rawText) } }
        let request = CommandAgentRequest(
            sessionId: conversation.id,
            command: command.command,
            args: command.args
        )
        isStreaming = true
        commit { $0.scrollToBottomID += 1 }
        streamTask = Task { [weak self] in
            let result = await client.command(request)
            guard let self else { return }
            if Task.isCancelled {
                // A stop is the user letting the *answer* go: no banner, no bubble, and `abort()` has already
                // put `isStreaming` back so the composer is free. The context is another matter — the server
                // ran this command whatever this side does with its reply — so the re-read the compact leg owes
                // is still taken, in the console's `finally` place (`ChatWindow.tsx:2497-2499`).
                if command.command == .compact { await self.refreshContextUsage() }
                return
            }
            self.streamTask = nil
            self.isStreaming = false
            // A tapped compaction answers with the four outcome strings; a typed command line keeps the
            // console's reply chain, whose first leg is the server's own sentence.
            let isTappedCompaction = rawText.isEmpty && command.command == .compact
            let sentence = isTappedCompaction
                ? Self.compactionSentence(for: result)
                : Self.commandSentence(for: result)
            if rawText.isEmpty {
                if isTappedCompaction {
                    // The console answers a completed compaction green and a too-short one blue; this banner
                    // has one calm tone for both, and keeps `warning` — not `error` — for the refusal, because
                    // a rejected command is the server saying no rather than a call that broke.
                    self.composerNotice = CompactionOutcome.compact(result) == .failed
                        ? .warning(sentence)
                        : .info(sentence)
                } else {
                    self.composerNotice = Self.commandSucceeded(result) ? .info(sentence) : .error(sentence)
                }
                // Clearing a conversation's history really does empty it server-side
                // (`DefaultAgentRunner.kt:241-244`), so the bubbles on screen go with it
                // (`ChatWindow.tsx:2681`).
                if Self.commandSucceeded(result), command.command == .clear {
                    self.settled { $0.transcript = ChatTranscript() }
                }
            } else {
                self.settled { $0.transcript.appendCommandReply(sentence) }
            }
            // The reading is the context this command just rewrote, so it is asked again either way — the
            // console re-reads in `handleCompact`'s `finally` (`:2497-2499`).
            if command.command == .compact { await self.refreshContextUsage() }
            self.commit { $0.scrollToBottomID += 1 }
        }
    }

    /// The console's reply chain, in order: `data.message`, then `Done` for a success that said nothing, then
    /// the failure's own words (`ChatWindow.tsx:1023`).
    ///
    /// `AgentCommandReply` has no envelope message of its own — the client's `Result` carries the business
    /// error — so the third leg is `ErrorMessage`'s reading of that failure. The one difference from the
    /// console is a reply with neither a sentence nor a flag, which is an envelope that carried no command body
    /// (`AgentCommandReply: HarnaxVoid`): this chain answers with a local「完成」where the console's
    /// `json.data?.success ? 'Done' : …` would answer「Failed」. That leg is only a word here; the compaction
    /// banner has to say whether the context got shorter, so it requires the flag (`CompactionOutcome.compact`).
    static func commandSentence(for result: Result<AgentCommandReply, APIError>) -> String {
        switch result {
        case let .failure(error):
            return ErrorMessage.text(for: error)
        case let .success(reply):
            if let message = reply.message, !message.isEmpty { return message }
            return reply.success == false ? hx("chat.command.failed") : hx("chat.command.done")
        }
    }

    private static func commandSucceeded(_ result: Result<AgentCommandReply, APIError>) -> Bool {
        switch result {
        case .failure: return false
        case let .success(reply): return reply.success != false
        }
    }

    /// What a tapped compaction has to say, in the console's own four branches
    /// (`harnax-webui/src/pages/session/components/ChatWindow.tsx:2443-2501`).
    ///
    /// A completed compaction names its two counts, because 「the context got shorter」 and 「this session was
    /// too short to compact」 are otherwise the same sentence — and the second is a report the user has to be
    /// able to tell apart (`CompactionOutcome`). The server's own words are used only on the refusal leg: a
    /// rejection carries why (a member's child session, a task session, a call already running), which is more
    /// actionable than a local 「压缩失败」, while a success carries an English summary no one on either client
    /// shows on this path.
    static func compactionSentence(for result: Result<AgentCommandReply, APIError>) -> String {
        switch CompactionOutcome.compact(result) {
        case .done:
            guard case let .success(reply) = result,
                  let before = reply.result?.beforeMessages,
                  let after = reply.result?.afterMessages
            else { return hx("chat.context.compact.donePlain") }
            return hx("chat.context.compact.done", before, after)
        case .noop:
            return hx("chat.context.compact.noop")
        case .failed:
            if case let .success(reply) = result, let message = reply.message, !message.isEmpty {
                return message
            }
            if case let .failure(error) = result { return ErrorMessage.text(for: error) }
            return hx("chat.context.compact.failed")
        }
    }

    // MARK: - run artifacts

    /// Whether a file row may offer its bytes at all.
    ///
    /// The artifact store is off unless the deployment turns it on (`OutputFileController.kt:38` registers the
    /// whole controller behind `minio.enabled=true`), and the read is optional here for the same reason: a row
    /// that could only ever answer 404 shows its size and nothing else.
    public var canTakeArtifacts: Bool { workspace != nil }

    public func isDownloading(_ attachment: ChatFileAttachment) -> Bool {
        pendingDownloads.contains(attachment.fileId)
    }

    /// One file row's bytes, to the share sheet — the same door the two drawers use, for the same reason:
    /// nothing is written to the app's own storage.
    ///
    /// The gate is the first line rather than a `.disabled()` on the row, because the second tap arrives before
    /// the first has had a render to turn itself off.
    public func download(_ attachment: ChatFileAttachment) async {
        guard !isDownloading(attachment) else { return }
        guard let workspace else {
            composerNotice = .error(hx("chat.command.unwired"))
            return
        }
        let sessionID = conversation.id
        pendingDownloads.insert(attachment.fileId)
        defer { pendingDownloads.remove(attachment.fileId) }
        switch await workspace.downloadAttachment(attachment, sessionId: sessionID) {
        case let .success(payload):
            // The read is still the right conversation's — it was given that id — but a sheet thrown over a
            // conversation the user has since opened, and a banner about a file they are no longer looking at,
            // are both news they did not ask for. `bind` has already dropped the row.
            guard conversation.id == sessionID else { return }
            let name = WorkspacePath.safeFileName(payload.name)
            share(HXSharedFile(name: name, data: payload.data, mimeType: payload.mimeType))
            composerNotice = .info(hx("chat.workspace.download.done", name))
        case let .failure(error):
            // 404 is both "no such artifact" and "the store is off" (`:38`, `:66`), and the banner says what
            // the transport said. A conversation switch does not hide it: the file they tapped is still the
            // one that failed.
            composerNotice = .error(ErrorMessage.text(for: error))
        }
    }

    // MARK: - scroll

    /// The view reports the distance from its last row to the bottom of the viewport, which is the one
    /// number the console's `handleScroll` keeps (`ChatWindow.tsx:620-627`).
    public func didScroll(distanceFromBottom: CGFloat) {
        let anchored = distanceFromBottom <= Self.nearBottomThreshold
        guard anchored != isAnchoredToBottom else { return }
        isAnchoredToBottom = anchored
    }

    /// The "back to bottom" control: coming back down re-arms the follow, so the stream pulls the view
    /// again (`ChatWindow.tsx:635-639`).
    public func jumpToBottom() {
        isAnchoredToBottom = true
        commit { $0.scrollToBottomID += 1 }
    }

    // MARK: - copy

    /// The ErrorEvent's own words, falling back to its machine name, which is the same order the console
    /// uses (`ChatWindow.tsx:1619-1633`).
    private func errorSentence(_ failure: ChatEvent.StreamFailure) -> String {
        failure.message.isEmpty ? failure.code : failure.message
    }

    /// A transport failure already has localised copy of its own; a frame the client could not read is the
    /// same news to the user as a socket that died.
    private func transportSentence(_ error: any Error) -> String {
        guard let error = error as? APIError else { return hx("chat.error.disconnected") }
        return ErrorMessage.text(for: error)
    }
}

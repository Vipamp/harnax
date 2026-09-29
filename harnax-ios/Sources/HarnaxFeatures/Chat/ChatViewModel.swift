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
    @Published public private(set) var transcript = ChatTranscript()
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
    /// (`ChatWindow.tsx:614-617`).
    @Published public private(set) var scrollToBottomID = 0

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

    private let streaming: any AgentStreaming
    private let commands: (any AgentCommanding)?
    private let history: (any ChatHistoryReading)?
    private let configReader: (any SessionConfiguring)?
    private let workspace: (any SessionWorkspaceReading)?
    private var streamTask: Task<Void, Never>?
    /// The stored rows are already on screen for this conversation. A reload is a re-entry, not a refresh.
    private var loadedConversationID: String?

    /// `commands` is the console's second leg of a stop: the read is aborted locally, and `INTERRUPT` tells
    /// the server to stop billing the run (`ChatWindow.tsx:2401-2405`). A host that has no command channel
    /// wired yet still gets a working abort, so the dependency is optional. `history` is optional for the
    /// same reason: a host that has not wired it starts on an empty transcript instead of failing to build.
    ///
    /// `config` is what the composer's four controls are gated by — the model capability flags and the stored
    /// permission mode (`ChatWindow.tsx:718-737`) — and `workspace` is the sandbox status read that
    /// `/stop-sandbox` has to make before it offers the confirmation (`:3639-3664`). Both optional like the
    /// other two: without them the switches keep the shipped defaults and the send path is untouched.
    public init(
        streaming: any AgentStreaming,
        commands: (any AgentCommanding)? = nil,
        history: (any ChatHistoryReading)? = nil,
        config: (any SessionConfiguring)? = nil,
        workspace: (any SessionWorkspaceReading)? = nil,
        conversation: ChatConversation
    ) {
        self.streaming = streaming
        self.commands = commands
        self.history = history
        self.configReader = config
        self.workspace = workspace
        self.conversation = conversation
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
            loadedConversationID = sessionID
            historyFailure = nil
            // A turn that started while the read was in flight owns the screen now.
            guard transcript.turns.isEmpty else { return }
            transcript = ChatTranscript(replaying: logs)
            scrollToBottomID += 1
        case let .failure(error):
            historyFailure = ErrorMessage.text(for: error)
        }
    }

    // MARK: - turn

    /// The send button and the Return key.
    ///
    /// A slash line takes the text and nothing else: the console's command leg never reads `imageUrls`, so a
    /// picture picked before typing `/clear` is still in the strip afterwards (`ChatWindow.tsx:986-1032`).
    public func send() {
        let text = draft
        let isCommand = ChatSlashCommand.parse(text.trimmingCharacters(in: .whitespacesAndNewlines)) != nil
        guard send(text) else { return }
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
        guard !isStreaming else { return false }
        let text = message.trimmingCharacters(in: .whitespacesAndNewlines)
        if let command = ChatSlashCommand.parse(text) {
            dispatch(command, rawText: text)
            return true
        }
        // A picture with no words is still a message — the disabled test at `:3684` reads exactly this way.
        guard !text.isEmpty || !images.isEmpty else { return false }
        stopNotice = nil
        isAnchoredToBottom = true
        let pictures = images
        transcript.send(text, images: pictures)
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

    /// The screen is going away. The answer stays as it is, with its open cards closed.
    public func detach() {
        abort()
    }

    /// Move to another conversation. The old read is aborted before the new conversation is adopted, so its
    /// frames cannot land here.
    public func bind(_ conversation: ChatConversation) {
        guard conversation != self.conversation else { return }
        abort()
        self.conversation = conversation
        transcript = ChatTranscript()
        stopNotice = nil
        historyFailure = nil
        loadedConversationID = nil
        draft = ""
        // The three switches and the permission mode are per-conversation columns, and the console clears
        // them on the session change rather than letting the previous one's values sit under a new title
        // (`ChatWindow.tsx:683-705`). Same for the pictures: a half-picked send belongs to one conversation.
        composer = SessionChatConfig()
        images = []
        composerNotice = nil
        pendingCommand = nil
        isAnchoredToBottom = true
        scrollToBottomID += 1
    }

    private func abort() {
        streamTask?.cancel()
        streamTask = nil
        isStreaming = false
        transcript.terminate(as: .interrupted)
    }

    private func startStream(_ message: String, images: [String] = []) {
        let request = ChatAgentRequest(sessionId: conversation.id, message: message, imageUrls: images)
        isStreaming = true
        scrollToBottomID += 1
        let reader = streaming
        streamTask = Task { [weak self] in
            do {
                for try await event in await reader.chat(request) {
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
    private func receive(_ event: ChatEvent) {
        transcript.fold(event)
        switch event {
        case let .failure(failure):
            isStreaming = false
            stopNotice = .failed(text: hx("chat.error.occurred", errorSentence(failure)))
        case .end:
            isStreaming = false
        case .keepAlive:
            break
        default:
            break
        }
        followTail()
    }

    /// The read ended on its own. A stream that never saw an end frame was cut, which is the console's
    /// disconnect case (`ChatWindow.tsx:2361-2366`): words only when nothing got through.
    private func readerClosed() {
        streamTask = nil
        isStreaming = false
        guard !transcript.isTerminated else { return }
        let sawContent = transcript.turns.last?.hasContent ?? false
        transcript.terminate(as: .interrupted)
        if !sawContent { stopNotice = .disconnected }
    }

    /// The read threw. Cancellation is not a failure — the abort that got us here already settled the
    /// transcript, and a stop has nothing to apologise for.
    private func readerFailed(with error: any Error) {
        streamTask = nil
        isStreaming = false
        guard !Task.isCancelled, !(error is CancellationError) else { return }
        transcript.terminate(as: .interrupted)
        stopNotice = .failed(text: transportSentence(error))
    }

    private func followTail() {
        guard isAnchoredToBottom else { return }
        scrollToBottomID += 1
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
        case let .failure(error):
            // The switches keep the DTO's own defaults, which is why the reason they may be wrong has to be
            // said out loud rather than swallowed.
            composerNotice = .error(ErrorMessage.text(for: error))
        }
    }

    // MARK: - pictures

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

    /// What the picker produced.
    ///
    /// Only `data:image…` strings are kept: the runtime reads any other string as a path inside the sandbox
    /// and fails (`HarnessAgentWrapper.kt:814-829`), so a URL that looked like a picture would only ever
    /// become an error bubble later. `ChatImageData` builds the strings; nothing here asks the network.
    public func addImages(_ dataURLs: [String]) {
        guard canPickImages else {
            composerNotice = .warning(hx("chat.model.noVision"))
            return
        }
        let accepted = dataURLs.filter { ChatImageData.isDataURL($0) }
        if accepted.count != dataURLs.count {
            composerNotice = .warning(hx("chat.image.rejected"))
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
        case .plan: composer.enablePlan = value
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
    public func requestClear() {
        pendingCommand = ChatPendingCommand(command: ChatSlashCommand(command: .clear), rawText: "")
    }

    /// The toolbar's Stop-sandbox button, behind the same status read as the typed form.
    public func requestStopSandbox() {
        checkSandbox(command: ChatSlashCommand(command: .stopSandbox), rawText: "")
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
            guard status == .running else {
                // Idle, and a reply that said nothing about this conversation, both read as "not running" —
                // the console's test is `res.code === 200 && res.data?.active` and its `else` is one warning.
                self.composerNotice = .warning(hx("chat.sandbox.notRunning"))
                return
            }
            self.pendingCommand = ChatPendingCommand(command: command, rawText: rawText)
        }
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
        if !rawText.isEmpty { transcript.appendUserMessage(rawText) }
        let request = CommandAgentRequest(
            sessionId: conversation.id,
            command: command.command,
            args: command.args
        )
        isStreaming = true
        scrollToBottomID += 1
        streamTask = Task { [weak self] in
            let result = await client.command(request)
            guard let self, !Task.isCancelled else { return }
            self.streamTask = nil
            self.isStreaming = false
            let sentence = Self.commandSentence(for: result)
            if rawText.isEmpty {
                self.composerNotice = Self.commandSucceeded(result) ? .info(sentence) : .error(sentence)
                // Clearing a conversation's history really does empty it server-side
                // (`DefaultAgentRunner.kt:241-244`), so the bubbles on screen go with it
                // (`ChatWindow.tsx:2681`).
                if Self.commandSucceeded(result), command.command == .clear {
                    self.transcript = ChatTranscript()
                }
            } else {
                self.transcript.appendCommandReply(sentence)
            }
            self.scrollToBottomID += 1
        }
    }

    /// The console's reply chain, in order: `data.message`, then `Done` for a success that said nothing, then
    /// the failure's own words (`ChatWindow.tsx:1012`).
    ///
    /// `AgentCommandReply` has no envelope message of its own — the client's `Result` carries the business
    /// error — so the third leg is `ErrorMessage`'s reading of that failure. A reply with no `success` key at
    /// all is the envelope's `ResultVo.success(null)`, which is a command that changed nothing and reported
    /// nothing; the console's `json.data?.success ? 'Done' : …` calls that a failure, and this side does not.
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
        scrollToBottomID += 1
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

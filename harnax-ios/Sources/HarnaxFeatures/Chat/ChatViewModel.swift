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

    private let streaming: any AgentStreaming
    private let commands: (any AgentCommanding)?
    private let history: (any ChatHistoryReading)?
    private var streamTask: Task<Void, Never>?
    /// The conversation whose rows are already on screen. A reload is a re-entry, not a refresh.
    private var loadedConversationID: String?

    /// `commands` is the console's second leg of a stop: the read is aborted locally, and `INTERRUPT` tells
    /// the server to stop billing the run (`ChatWindow.tsx:2401-2405`). A host that has no command channel
    /// wired yet still gets a working abort, so the dependency is optional. `history` is optional for the
    /// same reason: a host that has not wired it starts on an empty transcript instead of failing to build.
    public init(
        streaming: any AgentStreaming,
        commands: (any AgentCommanding)? = nil,
        history: (any ChatHistoryReading)? = nil,
        conversation: ChatConversation
    ) {
        self.streaming = streaming
        self.commands = commands
        self.history = history
        self.conversation = conversation
    }

    public var canSend: Bool {
        !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !isStreaming
    }

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

    public func send() {
        guard send(draft) else { return }
        draft = ""
    }

    /// Start a turn. Returns false when there was nothing to send or a turn is already reading — the input
    /// bar's send button becomes a stop button while streaming (`ChatWindow.tsx:3679-3686`), and the
    /// backend refuses a second run on a session that is still busy.
    @discardableResult
    public func send(_ message: String) -> Bool {
        let text = message.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !isStreaming else { return false }
        stopNotice = nil
        isAnchoredToBottom = true
        transcript.send(text)
        startStream(text)
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
        isAnchoredToBottom = true
        scrollToBottomID += 1
    }

    private func abort() {
        streamTask?.cancel()
        streamTask = nil
        isStreaming = false
        transcript.terminate(as: .interrupted)
    }

    private func startStream(_ message: String) {
        let request = ChatAgentRequest(sessionId: conversation.id, message: message)
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

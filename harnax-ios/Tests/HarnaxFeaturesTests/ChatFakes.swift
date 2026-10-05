import Foundation
import HarnaxCore
@testable import HarnaxFeatures

/// The streaming facade, driven by the test.
///
/// Each `chat` call hands out a `ChatStreamPort` and records the request, so a test can say exactly what the
/// screen asked for and then feed that turn frame by frame. Nothing arrives until the test sends it, which is
/// what makes asserting on a half-finished stream possible at all.
final class ScriptedChatStream: AgentStreaming, @unchecked Sendable {
    private(set) var chatRequests: [ChatAgentRequest] = []
    private(set) var confirmRequests: [ConfirmAgentRequest] = []
    private(set) var ports: [ChatStreamPort] = []
    /// Queued for the next call: a turn that dies before it starts has no stream to read, only an error.
    var errorToThrow: (any Error)?

    /// The stream the screen is reading now.
    var latest: ChatStreamPort {
        guard let port = ports.last else {
            fatalError("the screen never asked for a stream")
        }
        return port
    }

    func chat(_ request: ChatAgentRequest) async -> AsyncThrowingStream<ChatEvent, any Error> {
        chatRequests.append(request)
        if let error = errorToThrow {
            errorToThrow = nil
            return AsyncThrowingStream { $0.finish(throwing: error) }
        }
        let port = ChatStreamPort()
        ports.append(port)
        return port.makeStream()
    }

    func confirm(_ request: ConfirmAgentRequest) async -> AsyncThrowingStream<ChatEvent, any Error> {
        confirmRequests.append(request)
        let port = ChatStreamPort()
        ports.append(port)
        return port.makeStream()
    }
}

/// The command facade: it records what the stop button sent and answers with a canned reply.
final class ScriptedAgentCommands: AgentCommanding, @unchecked Sendable {
    private(set) var requests: [CommandAgentRequest] = []
    var reply: Result<AgentCommandReply, APIError> = .success(AgentCommandReply(success: true))
    /// Answers handed out in order before `reply` is used. A switch that goes optimistic and then has to come
    /// back needs the first command to land and the second to refuse.
    var replies: [Result<AgentCommandReply, APIError>] = []
    /// Holds the answer back until `release()`, so what a stop pressed mid-request leaves behind is a race
    /// rather than a sleep (`ScriptedContextUsage` does the same for the reading).
    var gate = false
    private var parked: [() -> Void] = []

    func command(_ request: CommandAgentRequest) async -> Result<AgentCommandReply, APIError> {
        requests.append(request)
        let answer = replies.isEmpty ? reply : replies.removeFirst()
        guard gate else { return answer }
        return await withCheckedContinuation { continuation in
            parked.append { continuation.resume(returning: answer) }
        }
    }

    /// Answers every request that is parked.
    func release() {
        let waiting = parked
        parked = []
        for resume in waiting { resume() }
    }
}

/// The admin config leg: one row, or one failure.
///
/// The chat screen reads the conversation's model flags once on entry and then drives every switch from the
/// command channel, so this fake records the write instead of performing it and its `writes` stays empty there
/// (`ChatWindow.tsx:2408-2430`). The detail sheet is the caller that does write: it turns `writeResult` into a
/// landing answer and asserts the body the panel sent.
final class ScriptedSessionConfig: SessionConfiguring, @unchecked Sendable {
    private(set) var requested: [String] = []
    private(set) var writes: [String] = []
    private(set) var changes: [SessionChatChange] = []
    var row = SessionSummary()
    var error: APIError?
    var writeResult: Result<EmptyResponse, APIError> = .failure(.offline)

    func sessionConfig(sessionId: String) async -> Result<SessionSummary, APIError> {
        requested.append(sessionId)
        if let error { return .failure(error) }
        return .success(row)
    }

    func updateSessionConfig(
        sessionId: String,
        _ change: SessionChatChange
    ) async -> Result<EmptyResponse, APIError> {
        writes.append(sessionId)
        changes.append(change)
        return writeResult
    }
}

/// The sandbox status the stop-sandbox confirmation reads before it asks anything.
final class ScriptedSandbox: SessionWorkspaceReading, @unchecked Sendable {
    private(set) var statusRequested: [String] = []
    var status: SandboxStatus = .unknown
    var statusFails = false
    /// Parking the status leg is the only way to ask what an answer that arrives after the user moved on does:
    /// the gate holds the reply until the test says so, in the shape `FakeWorkspaceSandbox` uses for its own
    /// downloads.
    var gateStatus = false
    private var parkedStatus: [() -> Void] = []
    /// The leg's *answer*, not its request: a parked reply that was released and one that never left are only
    /// tellable apart from here, which is what makes the dropped-late-answer test a race rather than a wait.
    private(set) var statusAnswered: [String] = []

    func sandboxStatus(sessionId: String) async -> Result<SandboxStatus, APIError> {
        statusRequested.append(sessionId)
        guard gateStatus else { return nextStatus(sessionId) }
        return await withCheckedContinuation { continuation in
            parkedStatus.append { continuation.resume(returning: self.nextStatus(sessionId)) }
        }
    }

    func releaseStatuses() {
        let waiting = parkedStatus
        parkedStatus = []
        for resume in waiting { resume() }
    }

    private func nextStatus(_ sessionId: String) -> Result<SandboxStatus, APIError> {
        statusAnswered.append(sessionId)
        return statusFails ? .failure(.offline) : .success(status)
    }

    func workspaceFiles(sessionId: String, path: String) async -> Result<[WorkspaceFile], APIError> {
        .failure(.offline)
    }

    func readWorkspaceFile(sessionId: String, path: String) async -> Result<WorkspaceFileContent, APIError> {
        .failure(.offline)
    }

    func uploadWorkspaceFile(
        sessionId: String,
        path: String,
        fileName: String,
        mimeType: String,
        payload: Data
    ) async -> Result<WorkspaceUpload, APIError> {
        .failure(.offline)
    }

    func downloadWorkspaceFile(sessionId: String, path: String) async -> Result<WorkspaceDownload, APIError> {
        .failure(.offline)
    }

    func downloadAttachment(
        _ attachment: ChatFileAttachment,
        sessionId: String
    ) async -> Result<WorkspaceDownload, APIError> {
        .failure(.offline)
    }
}

/// The context-occupancy read behind the header's tag.
///
/// Same shape as `ScriptedSandbox`: a canned answer, a failure switch, and a park so the one question that
/// cannot be asked any other way — what does a reply that lands after the user has moved on do — is a race
/// rather than a sleep.
final class ScriptedContextUsage: ContextUsageReading, @unchecked Sendable {
    private(set) var requested: [String] = []
    /// The leg's *answer*, not its request: a parked reply that was released and one that never left are only
    /// tellable apart from here.
    private(set) var answered: [String] = []
    /// A readable reading by default, because that is what a conversation with a billed call has.
    var reading = ContextUsage(
        messageCount: 12,
        estimatedTokens: 4_000,
        lastCallInputTokens: 8_000,
        contextWindow: 32_000,
        ratio: 0.25
    )
    var fails = false
    var gate = false
    private var parked: [() -> Void] = []

    func contextUsage(sessionId: String) async -> Result<ContextUsage, APIError> {
        requested.append(sessionId)
        // The payload belongs to the request: two reads parked one after the other have to carry different
        // numbers, or the race between them says nothing.
        let answer: Result<ContextUsage, APIError> = fails ? .failure(.offline) : .success(reading)
        guard gate else {
            answered.append(sessionId)
            return answer
        }
        return await withCheckedContinuation { continuation in
            parked.append {
                self.answered.append(sessionId)
                continuation.resume(returning: answer)
            }
        }
    }

    func release() {
        let waiting = parked
        parked = []
        for resume in waiting { resume() }
    }
}

/// One hand-driven stream.
///
/// Frames are written as the wire JSON the router sends, because the payload structs behind `ChatEvent` keep
/// internal memberwise inits: `decode` is the only door a test target has, and it is the same door the socket
/// reader walks through.
final class ChatStreamPort: @unchecked Sendable {
    private let box = StreamBox()

    init() {}

    /// How many consumers gave up on this stream. A stop or a conversation switch has to raise it, or the
    /// router keeps a run open behind a reader nobody is waiting on.
    var isTerminated: Bool { box.terminated }

    /// One stream per read, handed to whoever asked and kept by nobody — the shape the real client has. A
    /// stored sequence would outlive the loop that broke out of it and hold the read open, so the port
    /// would never see the consumer let go.
    func makeStream() -> AsyncThrowingStream<ChatEvent, any Error> { box.makeStream() }

    /// Feeds frames in order. Anything fed before the consumer attaches is held, so the order the test
    /// writes them in is the order the screen reads them.
    func feed(_ payloads: String...) throws {
        try feed(payloads)
    }

    func feed(_ payloads: [String]) throws {
        var items: [StreamBox.Item] = []
        for payload in payloads {
            items.append(.event(try ChatEvent.decode(payload)))
        }
        box.send(items)
    }

    /// The socket closed with no end frame, which is the disconnect case.
    func close() {
        box.send([.finish])
    }

    /// The read threw.
    func fail(_ error: any Error) {
        box.send([.failure(error)])
    }
}

/// The port's state, in an object the stream's builder closure can capture without capturing `self`.
private final class StreamBox: @unchecked Sendable {
    enum Item {
        case event(ChatEvent)
        case failure(any Error)
        case finish
    }

    typealias Continuation = AsyncThrowingStream<ChatEvent, any Error>.Continuation

    private let lock = NSLock()
    private var continuation: Continuation?
    private var queued: [Item] = []
    private var terminations = 0

    var terminated: Bool { lock.withLock { terminations > 0 } }

    func makeStream() -> AsyncThrowingStream<ChatEvent, any Error> {
        AsyncThrowingStream { [self] continuation in attach(continuation) }
    }

    func attach(_ continuation: Continuation) {
        continuation.onTermination = { [self] _ in
            lock.withLock { terminations += 1 }
        }
        let held = lock.withLock {
            self.continuation = continuation
            let held = queued
            queued = []
            return held
        }
        // Publishing outside the lock: `finish` calls its termination handler right here, and that handler
        // wants this lock.
        for item in held { Self.publish(item, to: continuation) }
    }

    func send(_ items: [Item]) {
        let target: Continuation? = lock.withLock {
            guard let continuation else {
                queued.append(contentsOf: items)
                return nil
            }
            return continuation
        }
        guard let target else { return }
        for item in items { Self.publish(item, to: target) }
    }

    private static func publish(_ item: Item, to continuation: Continuation) {
        switch item {
        case let .event(event):
            continuation.yield(event)
        case let .failure(error):
            continuation.finish(throwing: error)
        case .finish:
            continuation.finish()
        }
    }
}

/// The frame shapes, spelled once. `source` stays null: routing a frame to another bubble is the multi-run
/// merge, not the fold.
enum ChatFrames {
    static func text(_ message: String, isLast: Bool = false) -> String {
        #"{"eventType":"TextEvent","message":"\#(message)","isLast":\#(isLast),"source":null}"#
    }

    static func thinking(_ message: String, isLast: Bool = false) -> String {
        #"{"eventType":"ThinkingEvent","message":"\#(message)","isLast":\#(isLast),"source":null}"#
    }

    static func call(id: String, name: String, arguments: String = "{}") -> String {
        """
        {"eventType":"CallToolEvent","toolId":"\(id)","toolName":"\(name)",\
        "arguments":\(arguments),"tokenUsage":null,"source":null}
        """
    }

    static func result(id: String, name: String, message: String, success: Bool = true) -> String {
        """
        {"eventType":"ToolResultEvent","toolId":"\(id)","toolName":"\(name)",\
        "message":"\(message)","success":\(success),"source":null}
        """
    }

    /// A confirmation frame over the given `pendingCallTools` rows.
    static func confirm(_ rows: String...) -> String {
        """
        {"eventType":"ToolConfirmEvent","pendingCallTools":[\(rows.joined(separator: ","))],"source":null}
        """
    }

    /// One entry of a `pendingCallTools` array.
    static func pending(id: String, name: String, arguments: String = "{}", dangerous: Bool = false) -> String {
        """
        {"toolId":"\(id)","toolName":"\(name)","arguments":\(arguments),"isDangerous":\(dangerous)}
        """
    }

    static func end(attachments: String = "[]") -> String {
        #"{"eventType":"EndEvent","attachments":\#(attachments),"source":null}"#
    }

    /// One `EndEvent.attachments` entry.
    static func file(id: String, name: String, size: Int = 1024) -> String {
        """
        {"fileId":"\(id)","fileName":"\(name)","filePath":"/workspace/\(name)",\
        "fileSize":\(size),"mimeType":"text/markdown","url":"https://minio/\(id)","objectKey":"k"}
        """
    }

    static func failure(code: String, message: String) -> String {
        #"{"eventType":"ErrorEvent","code":"\#(code)","message":"\#(message)","source":null}"#
    }

    static let keepAlive = #"{"eventType":"KeepAliveEvent","source":null}"#

    /// A member run's ping, which has to be dropped just the same.
    static func memberKeepAlive(run: String) -> String {
        """
        {"eventType":"KeepAliveEvent","source":{"teamId":7,"teamName":"翻译组","memberAgentId":3,\
        "memberAgentName":"审校","childRunId":"\(run)","childSessionId":"s-3"}}
        """
    }
}

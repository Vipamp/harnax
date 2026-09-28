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

    func command(_ request: CommandAgentRequest) async -> Result<AgentCommandReply, APIError> {
        requests.append(request)
        return reply
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

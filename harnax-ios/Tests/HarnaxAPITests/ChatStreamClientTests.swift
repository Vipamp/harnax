import Network
import XCTest
import HarnaxCore
@testable import HarnaxAPI

/// The streaming half of the router contract: which lines count as frames, what credential goes out, and
/// what a rejected turn reports. `StubTransport` cannot stand in here — it waits for a whole body.
final class ChatStreamClientTests: XCTestCase {
    // MARK: framing

    func testOnlyDataLinesAreFrames() {
        XCTAssertEqual(SSE.payload(from: #"data:{"eventType":"EndEvent"}"#), #"{"eventType":"EndEvent"}"#)
        XCTAssertEqual(SSE.payload(from: #"data: {"eventType":"EndEvent"}"#), #"{"eventType":"EndEvent"}"#)
        XCTAssertNil(SSE.payload(from: "event: TextEvent"))
        XCTAssertNil(SSE.payload(from: "id: 12"))
        XCTAssertNil(SSE.payload(from: ": keep-alive comment"))
        XCTAssertNil(SSE.payload(from: ""), "the blank separator between frames")
        XCTAssertNil(SSE.payload(from: "data:"), "an empty payload is not JSON")
    }

    // MARK: credentials

    func testRouterStreamSendsThePermanentKeyAndEventNegotiation() async throws {
        let harness = APIHarness()
        try await harness.signIn()
        try await harness.configs.update(ServerConfig(adminBaseURL: "https://a.example.com", routerBaseURL: "https://r.example.com"))

        let request = try await ChatStreamClient(configs: harness.configs, session: harness.session, language: { "en-US" })
            .buildRequest(path: ChatStreamClient.chatPath, body: Data("{}".utf8))

        XCTAssertEqual(request.url?.absoluteString, "https://r.example.com/api/router/agent/chat/stream")
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(headerValue(request, "Accept"), "text/event-stream")
        XCTAssertEqual(headerValue(request, "Content-Type"), "application/json")
        XCTAssertEqual(headerValue(request, "Accept-Language"), "en-US")
        XCTAssertEqual(headerValue(request, "X-Api-Key"), "rk-1")
        XCTAssertNil(headerValue(request, "Authorization"))
    }

    func testKeylessAccountFallsBackToTheBearerToken() async throws {
        let harness = APIHarness()
        try await harness.session.signIn(
            JSONDecoder().decode(Envelope<LoginResponse>.self, from: Data(Wire.login(routerKey: nil).utf8)).data!
        )
        try await harness.configs.update(ServerConfig(adminBaseURL: "https://a.example.com", routerBaseURL: "https://r.example.com"))

        let request = try await ChatStreamClient(configs: harness.configs, session: harness.session).buildRequest(
            path: ChatStreamClient.confirmPath, body: Data("{}".utf8)
        )

        XCTAssertEqual(request.url?.path, "/api/router/agent/confirm")
        XCTAssertNil(headerValue(request, "X-Api-Key"))
        XCTAssertEqual(headerValue(request, "Authorization"), "Bearer tok-1")
        XCTAssertEqual(headerValue(request, "X-Tenant-ID"), "1")
    }

    /// The streaming leg reads its credential every time it builds a request, which is the only moment one
    /// exists: an SSE call authenticates when the router accepts it and is never re-sent, so a token rotated
    /// between two turns has to be the one the next turn carries. Nothing here is cached across requests.
    func testARotatedTokenGoesOutOnTheNextStreamRequest() async throws {
        let harness = APIHarness()
        try await harness.session.signIn(
            JSONDecoder().decode(Envelope<LoginResponse>.self, from: Data(Wire.login(routerKey: nil).utf8)).data!
        )
        try await harness.configs.update(ServerConfig(adminBaseURL: "https://a.example.com", routerBaseURL: "https://r.example.com"))
        let client = ChatStreamClient(configs: harness.configs, session: harness.session)

        let before = try await client.buildRequest(path: ChatStreamClient.chatPath, body: Data("{}".utf8))
        XCTAssertEqual(headerValue(before, "Authorization"), "Bearer tok-1")

        try await harness.session.adopt(
            JSONDecoder().decode(
                RefreshedToken.self,
                from: Data(#"{"accessToken":"tok-2","tenantId":1,"expiresIn":3600}"#.utf8)
            )
        )
        let after = try await client.buildRequest(path: ChatStreamClient.chatPath, body: Data("{}".utf8))
        XCTAssertEqual(headerValue(after, "Authorization"), "Bearer tok-2", "the stream asks the session, not a snapshot")
    }

    // MARK: a turn that never started

    func testEnvelopeUnderANonStreamContentTypeBecomesTheServersError() {
        XCTAssertEqual(
            ChatStreamClient.envelopeError(Data(Wire.business(403, "会话已禁用").utf8), statusCode: 200),
            APIError.business(code: 403, message: "会话已禁用")
        )
        XCTAssertEqual(
            ChatStreamClient.envelopeError(Data(Wire.unauthorized.utf8), statusCode: 401),
            APIError.unauthorized
        )
        XCTAssertEqual(
            ChatStreamClient.envelopeError(Data("not json".utf8), statusCode: 502),
            APIError.business(code: 502, message: "")
        )
        XCTAssertEqual(
            ChatStreamClient.envelopeError(Data(Wire.success("null").utf8), statusCode: 200),
            APIError.decoding,
            "a 2xx envelope that is not an event stream says nothing the client can show"
        )
    }

    // MARK: reading a live stream

    func testFramesArriveInOrderAndTheStreamClosesAtTheLastFrame() async throws {
        let server = SSELoopback(writes: [
            "data: {\"eventType\":\"TextEvent\",\"message\":\"你\",\"isLast\":false,\"source\":null}\n\n",
            ": ping\n",
            "event: ThinkingEvent\n",
            "data: {\"eventType\":\"CallToolEvent\",\"toolId\":\"t\",\"toolName\":\"bash\",\"arguments\":{},\"source\":null}\n\n",
            "data: {\"eventType\":\"EndEvent\",\"attachments\":[],\"source\":null}\n\n",
        ])
        let port = try server.start()
        defer { server.stop() }

        let events = try await collect(await client(on: port))

        XCTAssertEqual(events.count, 3)
        guard case let .text(first) = events.first else { return XCTFail("expected a text frame") }
        XCTAssertEqual(first.message, "你")
        guard case .toolCall = events[1] else { return XCTFail("expected a tool call") }
        guard case .end = events[2] else { return XCTFail("expected the end frame") }
    }

    /// A model token can land in two socket reads, so the half-line must be held rather than parsed.
    func testAFrameSplitAcrossWritesDecodesOnce() async throws {
        let server = SSELoopback(writes: [
            "data: {\"eventType\":\"TextEvent\",\"mess",
            "age\":\"两半\",\"isLast\":false}\n\n",
        ])
        let port = try server.start()
        defer { server.stop() }

        let events = try await collect(await client(on: port))

        XCTAssertEqual(events.count, 1)
        guard case let .text(delta) = events.first else { return XCTFail("expected a text frame") }
        XCTAssertEqual(delta.message, "两半")
    }

    /// One frame the client cannot read is not a dead turn. The console's reader wraps every single
    /// `JSON.parse` in its own `try/catch` and moves on with the next line (`ChatWindow.tsx:1389-1394`,
    /// with the same catch again at `:2349-2351`), so a torn or unparseable frame cannot end a run that
    /// is still answering — it only loses that one frame.
    func testABadFrameIsSkippedAndTheFramesAfterItStillLand() async throws {
        let server = SSELoopback(writes: [
            "data: {\"eventType\":\"TextEvent\",\"message\":\"好的一半\",\"isLast\":false,\"source\":null}\n\n",
            // Terminated by a real newline, but the JSON stops mid-object.
            "data: {\"eventType\":\"TextEvent\",\"message\":\"截\",\"isLast\":true"
                + "\n\n",
            "data: {\"eventType\":\"TextEvent\",\"message\":\"还在说\",\"isLast\":false,\"source\":null}\n\n",
            "data: {\"eventType\":\"EndEvent\",\"attachments\":[],\"source\":null}\n\n",
        ])
        let port = try server.start()
        defer { server.stop() }

        let events = try await collect(await client(on: port))

        XCTAssertEqual(events.count, 3, "the unreadable frame is dropped, the readable lines are not")
        guard case let .text(first) = events.first else { return XCTFail("expected a text frame") }
        XCTAssertEqual(first.message, "好的一半")
        guard case let .text(second) = events[1] else {
            return XCTFail("expected the text frame that came after the bad one")
        }
        XCTAssertEqual(second.message, "还在说")
        guard case .end = events[2] else { return XCTFail("expected the end frame") }
    }

    /// A newer server adds a ninth event type; a reader that has never heard of it has to stay quiet about
    /// it rather than stop the answer the user is watching.
    func testAnEventThisClientDoesNotKnowIsSkipped() async throws {
        let server = SSELoopback(writes: [
            "data: {\"eventType\":\"TextEvent\",\"message\":\"前\",\"isLast\":false,\"source\":null}\n\n",
            "data: {\"eventType\":\"BudgetEvent\",\"amount\":3,\"source\":null}\n\n",
            "data: {\"eventType\":\"EndEvent\",\"attachments\":[],\"source\":null}\n\n",
        ])
        let port = try server.start()
        defer { server.stop() }

        let events = try await collect(await client(on: port))

        XCTAssertEqual(events.count, 2, "the frame nobody recognises is skipped")
        guard case .end = events.last else { return XCTFail("expected the end frame after the unknown one") }
    }

    /// The server cut the corner mid-frame. The console keeps the bytes still in its buffer when the reader
    /// reports `done` and never parses them (`ChatWindow.tsx:1385-1386`), and the socket dying is still the
    /// turn's own news — the screen calls it a disconnect
    /// (`ChatViewModelTests.testACloseWithNothingOnScreenSaysConnectionLost`), a truer sentence than a
    /// JSON error the user cannot act on.
    func testATrunchedTailIsDroppedAndTheStreamCloses() async throws {
        let server = SSELoopback(writes: ["data: {\"eventType\":\"TextEvent\",\"message\":\"a\""])
        let port = try server.start()
        defer { server.stop() }

        let events = try await collect(await client(on: port))

        XCTAssertTrue(events.isEmpty, "nothing whole arrived, so nothing renders as an answer")
    }

    private func client(on port: UInt16) async -> ChatStreamClient {
        let harness = APIHarness()
        try? await harness.signIn()
        let base = "http://127.0.0.1:\(port)"
        try? await harness.configs.update(ServerConfig(adminBaseURL: base, routerBaseURL: base))
        return ChatStreamClient(configs: harness.configs, session: harness.session)
    }

    private func collect(_ client: ChatStreamClient, confirm: Bool = false) async throws -> [ChatEvent] {
        let stream = confirm
            ? await client.confirm(ConfirmAgentRequest(sessionId: "s-1", isConfirmed: true))
            : await client.chat(ChatAgentRequest(sessionId: "s-1", message: "hi"))
        var events: [ChatEvent] = []
        for try await event in stream { events.append(event) }
        return events
    }
}

/// A one-shot loopback responder, one string per socket write. It closes once the bytes are acknowledged, so
/// the client sees a normal end of stream.
private final class SSELoopback: @unchecked Sendable {
    private let head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n"
    private let writes: [String]
    private let listener: NWListener
    private let ready = DispatchSemaphore(value: 0)
    private let queue = DispatchQueue(label: "harnax.sse.loopback")

    init(writes: [String]) {
        self.writes = writes
        self.listener = try! NWListener(using: .tcp)
    }

    /// Blocks until the port is bound, then hands it back.
    func start() throws -> UInt16 {
        listener.stateUpdateHandler = { [weak self] state in
            if case .ready = state { self?.ready.signal() }
        }
        listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
        listener.start(queue: queue)
        ready.wait()
        return listener.port?.rawValue ?? 0
    }

    func stop() {
        listener.cancel()
    }

    private func accept(_ connection: NWConnection) {
        connection.start(queue: queue)
        readRequest(connection, buffer: Data())
    }

    /// Answers only once the whole request has landed. A server that never reads it leaves the client's
    /// upload unacknowledged, and the closing reset then reaches the reader as a -1005 instead of a framing
    /// outcome — which is what this fixture used to make flaky.
    private func readRequest(_ connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 8192) { [weak self] data, _, isComplete, error in
            guard let self, error == nil else { return }
            var received = buffer
            received.append(data ?? Data())
            guard let separator = received.range(of: Data("\r\n\r\n".utf8)) else {
                if isComplete { self.send([self.head] + self.writes, to: connection) } else {
                    self.readRequest(connection, buffer: received)
                }
                return
            }
            let headers = String(decoding: received[..<separator.lowerBound], as: UTF8.self)
            let declared = Int((headers.range(of: "content-length:", options: [.caseInsensitive, .backwards])
                .map { headers[$0.upperBound...] } ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines)) ?? 0
            guard received.count - separator.upperBound >= declared else {
                return self.readRequest(connection, buffer: received)
            }
            self.send([self.head] + self.writes, to: connection)
        }
    }

    /// One write at a time: the next goes out only after the socket took the previous, which is what lets a
    /// frame land in pieces. The empty final message half-closes — `cancel()` here would send an RST and the
    /// reader would lose the bytes it was meant to parse.
    private func send(_ payloads: [String], to connection: NWConnection) {
        guard let first = payloads.first else {
            connection.send(content: nil, contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { _ in
                connection.cancel()
            })
            return
        }
        connection.send(content: Data(first.utf8), completion: .contentProcessed { _ in
            self.send(Array(payloads.dropFirst()), to: connection)
        })
    }
}

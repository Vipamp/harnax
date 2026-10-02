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

    // MARK: - a bearer that went stale while the screen was shut

    /// A session idle past its token's life cannot start a turn with the bearer it still holds, and every
    /// other call in the app knows that: `APIClient` renews a token inside the window before it sends
    /// (`Sources/HarnaxAPI/APIClient.swift:160-166`) and replays once on a 401 (`:29-37`). The stream reads
    /// the same keychain, so it has to do the same — and this test drives it through no injected seam at all,
    /// which is the shape `HarnaxDependencies.live()` builds.
    func testAStreamOnASessionIdlePastItsTokenRenewsBeforeItGoesOut() async throws {
        let server = SSELoopback(answers: [
            .refreshed(token: "tok-2"),
            .stream([SSEAnswer.endFrame]),
        ])
        let port = try server.start()
        defer { server.stop() }
        // 29 seconds left of a 90-second life is below the one-third threshold, so the client's own copy of the
        // deadline is what says "renew first" — the router never gets the chance to reject the turn.
        let harness = try await keylessHarness(port: port, expiresIn: 90)
        harness.clock.advance(61)

        let events = try await collect(ChatStreamClient(configs: harness.configs, session: harness.session))

        XCTAssertEqual(events.count, 1, "the answer starts on the renewed credential")
        XCTAssertEqual(server.recorded.count, 2, "the renewal goes up front, so nothing has to be rejected first")
        XCTAssertTrue(
            server.recorded[0].hasPrefix("POST \(TokenRefresher.path)"),
            "the first request out was the renewal, not the turn: \(server.recorded[0])"
        )
        XCTAssertTrue(server.recorded[1].hasPrefix("POST \(ChatStreamClient.chatPath)"))
        XCTAssertTrue(server.recorded[1].contains("Authorization: Bearer tok-2"), server.recorded[1])
    }

    /// The window is the client's guess; the server's word is a 401. A turn that never started — the router
    /// answers an envelope before it writes a stream — is safe to send again, and the user's expectation is
    /// that the answer arrives rather than that the screen explains their token expired.
    func testAStreamTheRouterRejectsAsUnauthorizedStartsAfterOneRenewal() async throws {
        let server = SSELoopback(answers: [
            .envelope(status: 401, body: Wire.unauthorized),
            .refreshed(token: "tok-2"),
            .stream([SSEAnswer.endFrame]),
        ])
        let port = try server.start()
        defer { server.stop() }
        let harness = try await keylessHarness(port: port, expiresIn: 3600)

        let events = try await collect(ChatStreamClient(configs: harness.configs, session: harness.session))

        XCTAssertEqual(events.count, 1, "a rejected credential is renewed, not reported")
        XCTAssertEqual(server.recorded.count, 3, "the rejected turn, the renewal, the turn again")
        XCTAssertTrue(server.recorded[1].hasPrefix("POST \(TokenRefresher.path)"), server.recorded[1])
        XCTAssertTrue(server.recorded[2].hasPrefix("POST \(ChatStreamClient.chatPath)"), server.recorded[2])
        XCTAssertTrue(server.recorded[2].contains("Authorization: Bearer tok-2"), server.recorded[2])
    }

    /// The other credential does not expire. A permanent key is what the router prefers
    /// (`ChatWindow.tsx:153-164`), so paying for a refresh round trip before a turn that carries it would
    /// only make the first answer slower.
    func testAStreamOnAPermanentKeyRenewsNothing() async throws {
        let server = SSELoopback(answers: [.stream([SSEAnswer.endFrame])])
        let port = try server.start()
        defer { server.stop() }
        let harness = APIHarness()
        try await harness.signIn(expiresIn: 30)
        try await harness.configs.update(
            ServerConfig(adminBaseURL: "http://127.0.0.1:\(port)", routerBaseURL: "http://127.0.0.1:\(port)")
        )

        let events = try await collect(ChatStreamClient(configs: harness.configs, session: harness.session))

        XCTAssertEqual(events.count, 1)
        XCTAssertEqual(server.recorded.count, 1, "a key that cannot expire needs no renewal")
        XCTAssertTrue(server.recorded[0].contains("X-Api-Key: rk-1"), server.recorded[0])
    }

    /// One renewal, one retry, and no more: a refresh the server also rejected says the session is dead, and
    /// sending the turn a third time would only say it a third way.
    func testAStreamWhoseRenewalWasRefusedIsNotSentAgain() async throws {
        let server = SSELoopback(answers: [
            .envelope(status: 401, body: Wire.unauthorized),
            .envelope(status: 401, body: Wire.unauthorized),
            .stream([SSEAnswer.endFrame]),
        ])
        let port = try server.start()
        defer { server.stop() }
        let harness = try await keylessHarness(port: port, expiresIn: 3600)

        do {
            _ = try await collect(ChatStreamClient(configs: harness.configs, session: harness.session))
            XCTFail("a session the refresh route also rejects is not a stream")
        } catch let error as APIError {
            XCTAssertEqual(error, .unauthorized)
        }
        XCTAssertEqual(server.recorded.count, 2, "the turn, the renewal, and it stops there")
    }

    /// A session whose only credential is the bearer — the leg that can go stale. Both bases point at the
    /// same loopback, so the renewal and the turn arrive at one fixture in the order they were sent.
    private func keylessHarness(port: UInt16, expiresIn: Int64) async throws -> APIHarness {
        let harness = APIHarness()
        try await harness.session.signIn(
            harness.decode(Wire.login(token: "tok-1", expiresIn: expiresIn, routerKey: nil))
        )
        let base = "http://127.0.0.1:\(port)"
        try await harness.configs.update(ServerConfig(adminBaseURL: base, routerBaseURL: base))
        return harness
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

/// A canned answer: the status line and headers, then the body in the pieces the socket takes them.
private struct SSEAnswer {
    let head: String
    let writes: [String]

    /// An event stream that opens as soon as the request has landed.
    static func stream(_ writes: [String]) -> SSEAnswer {
        SSEAnswer(
            head: "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n",
            writes: writes
        )
    }

    static let endFrame = "data: {\"eventType\":\"EndEvent\",\"attachments\":[],\"source\":null}\n\n"

    /// A rejected call: an envelope under a JSON content type, which is what the client reads instead of a
    /// stream (`Self.envelopeError`).
    static func envelope(status: Int, body: String) -> SSEAnswer {
        let reason = status == 401 ? "Unauthorized" : status == 200 ? "OK" : "Bad Request"
        let head = "HTTP/1.1 \(status) \(reason)\r\nContent-Type: application/json\r\n"
            + "Content-Length: \(body.utf8.count)\r\nConnection: close\r\n\r\n"
        return SSEAnswer(head: head, writes: [body])
    }

    /// `POST /api/admin/auth/refresh-token` handing back a new bearer, over the same loopback host.
    static func refreshed(token: String) -> SSEAnswer {
        envelope(status: 200, body: Wire.refreshed(token: token, expiresIn: 3600))
    }
}

/// A loopback responder that answers each connection it accepts from a list, in order, repeating the last
/// answer once the list runs out. It closes once the bytes are acknowledged, so the client sees a normal end
/// of stream. Because every answer goes to the next connection, one fixture can play a whole conversation:
/// a rejection, the renewal it asks for, and the stream that follows.
private final class SSELoopback: @unchecked Sendable {
    private let answers: [SSEAnswer]
    private let listener: NWListener
    private let ready = DispatchSemaphore(value: 0)
    private let queue = DispatchQueue(label: "harnax.sse.loopback")
    private var next = 0
    private var received: [String] = []

    init(writes: [String]) {
        answers = [.stream(writes)]
        listener = try! NWListener(using: .tcp)
    }

    init(answers: [SSEAnswer]) {
        self.answers = answers
        listener = try! NWListener(using: .tcp)
    }

    /// The header block of every request that reached this fixture, in arrival order — the only way to see
    /// which credential the client put on which attempt.
    var recorded: [String] {
        queue.sync { received }
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
        let answer = answers[min(next, answers.count - 1)]
        next += 1
        readRequest(connection, buffer: Data(), answering: answer)
    }

    /// Answers only once the whole request has landed. A server that never reads it leaves the client's
    /// upload unacknowledged, and the closing reset then reaches the reader as a -1005 instead of a framing
    /// outcome — which is what this fixture used to make flaky.
    private func readRequest(_ connection: NWConnection, buffer: Data, answering answer: SSEAnswer) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 8192) { [weak self] data, _, isComplete, error in
            guard let self, error == nil else { return }
            var received = buffer
            received.append(data ?? Data())
            guard let separator = received.range(of: Data("\r\n\r\n".utf8)) else {
                if isComplete { self.send(answer, to: connection) } else {
                    self.readRequest(connection, buffer: received, answering: answer)
                }
                return
            }
            let headers = String(decoding: received[..<separator.lowerBound], as: UTF8.self)
            let declared = Int((headers.range(of: "content-length:", options: [.caseInsensitive, .backwards])
                .map { headers[$0.upperBound...] } ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines)) ?? 0
            guard received.count - separator.upperBound >= declared else {
                return self.readRequest(connection, buffer: received, answering: answer)
            }
            self.received.append(headers)
            self.send(answer, to: connection)
        }
    }

    private func send(_ answer: SSEAnswer, to connection: NWConnection) {
        send([answer.head] + answer.writes, to: connection)
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

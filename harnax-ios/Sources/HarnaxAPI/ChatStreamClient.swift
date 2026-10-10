import Foundation
import HarnaxCore

/// The router's two streaming agent calls.
///
/// This cannot ride on `APIClient`: that transport waits for a whole body, and a chat answer is read frame by
/// frame. The credential the router expects is the account's permanent key (`X-Api-Key`), with the bearer
/// token as the fallback — the same order `getRouterHeaders` uses in `webui` (`ChatWindow.tsx:158-169`).
///
/// Being a separate transport is no reason to hold a different standard about that fallback: a bearer expires
/// whether it is going to a JSON call or to a stream, so this client renews it the way `APIClient` does —
/// up front when the keychain's own deadline is close, and once more after the router says 401.
public struct ChatStreamClient: AgentStreaming {
    public static let chatPath = "/api/router/agent/chat/stream"
    public static let confirmPath = "/api/router/agent/confirm"

    private let configs: ServerConfigStore
    private let session: AuthSession
    private let urlSession: URLSession
    private let refresher: any TokenRefreshing
    private let language: @Sendable () -> String

    /// `refresher` defaults to one over the same server configs rather than being a required argument, so the
    /// composition root that builds this client keeps compiling and keeps working: the bearer leg cannot
    /// start a turn at all without a way to renew, and an unwired stream is worse than an unwired refresh.
    /// A host that already has a `TokenRefresher` — `HarnaxDependencies.live()` has one for `APIClient` —
    /// may hand it over here instead.
    public init(
        configs: ServerConfigStore,
        session: AuthSession,
        urlSession: URLSession = ChatStreamClient.default,
        refresher: (any TokenRefreshing)? = nil,
        language: @escaping @Sendable () -> String = { "zh-CN" }
    ) {
        self.configs = configs
        self.session = session
        self.urlSession = urlSession
        self.refresher = refresher ?? TokenRefresher(configs: configs)
        self.language = language
    }

    /// The server ends a silent stream after 120s and a live one after 30min, so the client must not be the
    /// hop that gives up first: the idle allowance sits above the server's, the resource cap above its max.
    public static let `default`: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 180
        configuration.timeoutIntervalForResource = 2100
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        return URLSession(configuration: configuration)
    }()

    public func chat(_ request: ChatAgentRequest) async -> AsyncThrowingStream<ChatEvent, any Error> {
        await events(path: Self.chatPath, body: request)
    }

    public func confirm(_ request: ConfirmAgentRequest) async -> AsyncThrowingStream<ChatEvent, any Error> {
        await events(path: Self.confirmPath, body: request)
    }

    private func events(path: String, body: some Encodable) async -> AsyncThrowingStream<ChatEvent, any Error> {
        let payload: Data
        do {
            payload = try JSONEncoder().encode(body)
        } catch {
            return AsyncThrowingStream { $0.finish(throwing: error) }
        }
        let urlSession = self.urlSession
        return AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    try await open(path: path, payload: payload, through: urlSession, into: continuation)
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            // Dropping the stream — a cancelled turn, a screen that went away — has to close the socket,
            // or the router keeps a run billing against a reader nobody is waiting on.
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// One turn, sent with a credential that still works.
    ///
    /// A rejected bearer answers an envelope *before* the stream opens, so it is the one failure this side can
    /// undo: renew, and send the same turn again. That is what every JSON call gets
    /// (`Sources/HarnaxAPI/APIClient.swift:29-37`), and the user's sentence is the same either way — an answer
    /// that will not start.
    ///
    /// Exactly one retry, and only on a rejection that came before a frame. A stream that had already said
    /// something was accepted, and the router bills the run it starts
    /// (`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt:166`),
    /// so a second copy of that message would be a second answer nobody asked for. `read` can only produce an
    /// `APIError` from that pre-stream branch, which is what makes the gate hold by construction.
    private func open(
        path: String,
        payload: Data,
        through urlSession: URLSession,
        into continuation: AsyncThrowingStream<ChatEvent, any Error>.Continuation
    ) async throws {
        let request = try await buildRequest(path: path, body: payload)
        do {
            try await Self.read(urlSession, request, into: continuation)
        } catch APIError.unauthorized {
            guard await isBearerLeg() else { throw APIError.unauthorized }
            // The renewal's own failure is what goes back: it says the session is over, where the 401 only
            // said this one request was. Clearing the session is the root's call, not the stream's.
            try await refreshNow()
            let retry = try await buildRequest(path: path, body: payload)
            try await Self.read(urlSession, retry, into: continuation)
        }
    }

    /// Whether the credential about to go out is the one a renewal can replace. The permanent key does not
    /// expire, so a 401 carrying it says the key was revoked, and a fresher bearer would change nothing.
    private func isBearerLeg() async -> Bool {
        ((try? await session.routerAPIKey()) ?? "").isEmpty
    }

    /// `POST /api/admin/auth/refresh-token`, and the new bearer into the keychain so every later call — the
    /// admin surfaces included — reads it back. Same two steps as `APIClient.refreshNow()`
    /// (`Sources/HarnaxAPI/APIClient.swift:176-182`).
    private func refreshNow() async throws {
        guard let current = try await session.accessToken(), !current.isEmpty else {
            throw APIError.unauthorized
        }
        let fresh = try await refresher.refresh(current: current)
        try await session.adopt(fresh)
    }

    static func read(
        _ urlSession: URLSession,
        _ request: URLRequest,
        into continuation: AsyncThrowingStream<ChatEvent, any Error>.Continuation
    ) async throws {
        let (bytes, response) = try await urlSession.bytes(for: request)
        guard let http = response as? HTTPURLResponse else { throw APIError.unreachable }
        guard http.value(forHTTPHeaderField: "Content-Type")?.contains("text/event-stream") ?? false else {
            // A rejected call answers a JSON envelope instead of a stream.
            var body = Data()
            for try await byte in bytes { body.append(byte) }
            throw Self.envelopeError(body, statusCode: http.statusCode)
        }
        for try await line in bytes.lines {
            try Task.checkCancellation()
            guard let payload = SSE.payload(from: line) else { continue }
            // One frame the client cannot read does not end the turn. The console catches around every
            // `JSON.parse` in its line loop and goes on with the next line (`ChatWindow.tsx:1394-1399`,
            // `:2349-2351`), and that is the right call for two different reasons at once: a frame the
            // server wrote across a buffer boundary, and an event type older clients have never seen, are
            // both news about one frame — not news about a run that is still going. The socket's own
            // errors keep propagating from the `for try await` above, and a stream that ends without an
            // end frame is still reported by the reader's owner.
            guard let event = try? ChatEvent.decode(payload) else { continue }
            continuation.yield(event)
        }
    }

    /// 403 disabled session, 404 missing session, and a business `code` under HTTP 200 all arrive with the
    /// admin envelope shape, which is the text worth showing on a turn that never started.
    static func envelopeError(_ body: Data, statusCode: Int) -> APIError {
        do {
            _ = try ResponseMapper.map(EmptyResponse.self, data: body, statusCode: statusCode)
            return APIError.decoding
        } catch let error as APIError {
            return error
        } catch {
            return APIError.decoding
        }
    }

    func buildRequest(path: String, body: Data) async throws -> URLRequest {
        let base = try await configs.baseURL(for: .router)
        guard let url = Endpoint(.post, path: path, base: .router).url(baseURL: base) else {
            throw APIError.invalidServerConfig(base)
        }
        var request = URLRequest(url: url)
        request.httpMethod = HTTPMethod.post.rawValue
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(language(), forHTTPHeaderField: "Accept-Language")
        if let key = try await session.routerAPIKey(), !key.isEmpty {
            request.setValue(key, forHTTPHeaderField: "X-Api-Key")
        } else {
            // A bearer is the credential that can die between two turns, so it gets the same up-front renewal
            // every other call pays for (`Sources/HarnaxAPI/APIClient.swift:160-166`). The keychain's own
            // deadline is the only warning a session that sat idle overnight gives, and a stream is the leg
            // that can least afford to learn about it from the server: without this the user watches a turn
            // fail for a token this side already knew was going away.
            if (try? await session.needsRefresh()) ?? false { try? await refreshNow() }
            if let token = try await session.accessToken(), !token.isEmpty {
                // The webui falls back to the bearer with its `getAuthHeaders`, so a keyless account keeps the
                // same shape here. It is the weaker of the two credentials by construction: admin signs a user
                // JWT with `JWT_SECRET` while this service's filter verifies with `HARNAX_AUTH_SECRET`, and
                // those are separate properties on separate services on purpose
                // (`harnax-auth/src/main/kotlin/com/agnetix/harnax/auth/InternalTokenProvider.kt:59-67`).
                // Measured on the live stack 2026-09-30, that bearer comes back 401 — the leg only works where
                // an operator has pointed both secrets at one value, which is why the key above is preferred.
                request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                if let tenantID = try await session.tenantID(), !tenantID.isEmpty {
                    request.setValue(tenantID, forHTTPHeaderField: "X-Tenant-ID")
                }
            }
        }
        request.httpBody = body
        return request
    }
}

/// The same client answers a confirmation, because the answer is the other half of the one stream contract:
/// `POST /api/router/agent/confirm` replies with SSE and carries the resumed run on it. `AgentStreaming`
/// already requires `confirm`, so this adds no code — it names the narrower capability the chat screen is
/// given as an optional dependency, so a host can leave the answer path unwired.
extension ChatStreamClient: ToolConfirming {}

/// Server-Sent Events framing, matching the browser reader `webui` uses (`ChatWindow.tsx:1385-1399`): a
/// payload line is `data:` plus JSON, and everything else — `event:`, `id:`, a `:` comment, the blank
/// separator between frames — is skipped.
enum SSE {
    static func payload(from line: String) -> String? {
        guard line.hasPrefix("data:") else { return nil }
        let text = line.dropFirst(5).trimmingCharacters(in: .whitespaces)
        return text.isEmpty ? nil : text
    }
}

import Foundation
import HarnaxCore

/// The router's two streaming agent calls.
///
/// This cannot ride on `APIClient`: that transport waits for a whole body, and a chat answer is read frame by
/// frame. The credential the router expects is the account's permanent key (`X-Api-Key`), with the bearer
/// token as the fallback — the same order `getRouterHeaders` uses in `webui` (`ChatWindow.tsx:153-164`).
public struct ChatStreamClient: AgentStreaming {
    public static let chatPath = "/api/router/agent/chat/stream"
    public static let confirmPath = "/api/router/agent/confirm"

    private let configs: ServerConfigStore
    private let session: AuthSession
    private let urlSession: URLSession
    private let language: @Sendable () -> String

    public init(
        configs: ServerConfigStore,
        session: AuthSession,
        urlSession: URLSession = ChatStreamClient.default,
        language: @escaping @Sendable () -> String = { "zh-CN" }
    ) {
        self.configs = configs
        self.session = session
        self.urlSession = urlSession
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
        let request: URLRequest
        do {
            request = try await buildRequest(path: path, body: try JSONEncoder().encode(body))
        } catch {
            return AsyncThrowingStream { $0.finish(throwing: error) }
        }
        let urlSession = self.urlSession
        return AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    try await Self.read(urlSession, request, into: continuation)
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

    static func read(
        _ urlSession: URLSession,
        _ request: URLRequest,
        into continuation: AsyncThrowingStream<ChatEvent, any Error>.Continuation
    ) async throws {
        let (bytes, response) = try await urlSession.bytes(for: request)
        guard let http = response as? HTTPURLResponse else { throw APIError.offline }
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
            // `JSON.parse` in its line loop and goes on with the next line (`ChatWindow.tsx:1389-1394`,
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
        } else if let token = try await session.accessToken(), !token.isEmpty {
            // The webui falls back to the bearer with its `getAuthHeaders`; a keyless account is the
            // pre-permanent-key shape, and the router still accepts it.
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            if let tenantID = try await session.tenantID(), !tenantID.isEmpty {
                request.setValue(tenantID, forHTTPHeaderField: "X-Tenant-ID")
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

/// Server-Sent Events framing, matching the browser reader `webui` uses (`ChatWindow.tsx:1380-1394`): a
/// payload line is `data:` plus JSON, and everything else — `event:`, `id:`, a `:` comment, the blank
/// separator between frames — is skipped.
enum SSE {
    static func payload(from line: String) -> String? {
        guard line.hasPrefix("data:") else { return nil }
        let text = line.dropFirst(5).trimmingCharacters(in: .whitespaces)
        return text.isEmpty ? nil : text
    }
}

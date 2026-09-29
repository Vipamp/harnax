import Foundation
import HarnaxCore

/// The one way a feature screen reaches the backend: headers get injected here, and a 401 is turned into
/// a refresh-and-replay before the caller ever sees an error.
public actor APIClient {
    private let transport: HTTPRequesting
    private let session: AuthSession
    private let configs: ServerConfigStore
    private let refresher: any TokenRefreshing
    private let language: @Sendable () -> String

    public init(
        transport: HTTPRequesting = URLSessionTransport(),
        session: AuthSession,
        configs: ServerConfigStore,
        refresher: any TokenRefreshing,
        language: @escaping @Sendable () -> String = { "zh-CN" }
    ) {
        self.transport = transport
        self.session = session
        self.configs = configs
        self.refresher = refresher
        self.language = language
    }

    public func send<T: Decodable>(_ type: T.Type, _ endpoint: Endpoint) async -> Result<T, APIError> {
        let first = await perform(type, endpoint)
        guard endpoint.authenticated, case .failure(.unauthorized) = first else { return first }
        do {
            try await refreshNow()
        } catch {
            try? await session.signOut()
            return .failure(.unauthorized)
        }
        return await perform(type, endpoint)
    }

    /// A body of bytes rather than an envelope: the team artifact download answers
    /// `ResponseEntity<ByteArray>` (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TeamArtifactController.kt:77-100`).
    ///
    /// The 401 replay is the same one `send` does, because a stale token looks identical on this route. What
    /// is deliberately *not* the same is 403: on the JSON surface that status means the filter rejected the
    /// token and `ResponseMapper` turns it into a session event, while here the controller itself answers 403
    /// for "this session is not yours" (`:79`) and 400 for a `fileId` that is not a UUID (`:78`), next to 404
    /// for an artifact that belongs to another conversation (`:81-86`). None of those three is a sign-out, so
    /// they go back as the business error the status names.
    public func sendRaw(_ endpoint: Endpoint) async -> Result<RawResponse, APIError> {
        let first = await performRaw(endpoint)
        guard endpoint.authenticated, case .failure(.unauthorized) = first else { return first }
        do {
            try await refreshNow()
        } catch {
            try? await session.signOut()
            return .failure(.unauthorized)
        }
        return await performRaw(endpoint)
    }

    private func perform<T: Decodable>(_ type: T.Type, _ endpoint: Endpoint) async -> Result<T, APIError> {
        let request: URLRequest
        do {
            request = try await buildRequest(for: endpoint)
        } catch let error as APIError {
            return .failure(error)
        } catch {
            return .failure(.offline)
        }
        let data: Data
        let response: HTTPURLResponse
        do {
            (data, response) = try await transport.perform(request)
        } catch {
            return .failure(URLSessionTransport.map(error))
        }
        do {
            return .success(try ResponseMapper.map(type, data: data, statusCode: response.statusCode))
        } catch let error as APIError {
            return .failure(error)
        } catch {
            return .failure(.decoding)
        }
    }

    private func performRaw(_ endpoint: Endpoint) async -> Result<RawResponse, APIError> {
        let request: URLRequest
        do {
            // `*/*` rather than the JSON `Accept` every other call carries: this route writes a concrete
            // `Content-Type` of its own (`TeamArtifactController.kt:97`), and asking for JSON on the way in is
            // how a client gets a 406 for a file that exists.
            request = try await buildRequest(for: endpoint, accept: "*/*")
        } catch let error as APIError {
            return .failure(error)
        } catch {
            return .failure(.offline)
        }
        let data: Data
        let response: HTTPURLResponse
        do {
            (data, response) = try await transport.perform(request)
        } catch {
            return .failure(URLSessionTransport.map(error))
        }
        if response.statusCode == 401 { return .failure(.unauthorized) }
        guard (200..<300).contains(response.statusCode) else {
            return .failure(.business(code: response.statusCode, message: ""))
        }
        return .success(RawResponse(
            data: data,
            mimeType: response.mimeType ?? "application/octet-stream",
            // Foundation reads the name out of `Content-Disposition`, which is the shape this route writes:
            // `attachment; filename="…"`, control characters and path parts already stripped server-side
            // (`TeamArtifactController.kt:96`, `:268-273` of the workspace route's shared rule).
            suggestedFilename: response.suggestedFilename
        ))
    }

    private func buildRequest(for endpoint: Endpoint, accept: String = "application/json") async throws -> URLRequest {
        let base = try await configs.baseURL(for: endpoint.base)
        guard let url = endpoint.url(baseURL: base) else {
            throw APIError.invalidServerConfig(base)
        }
        var request = URLRequest(url: url)
        request.httpMethod = endpoint.method.rawValue
        request.setValue(accept, forHTTPHeaderField: "Accept")
        request.setValue(language(), forHTTPHeaderField: "Accept-Language")
        if let body = endpoint.body {
            request.setValue(endpoint.contentType ?? "application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = body
        }
        guard endpoint.authenticated else { return request }
        // Refreshing a soon-to-expire token up front avoids a 401 round trip on the first call after the
        // app has been backgrounded. Concurrent calls may each refresh; the backend tokens are stateless
        // and both stay valid, so no single-flight lock is needed yet.
        if (try? await session.needsRefresh()) ?? false {
            try? await refreshNow()
        }
        if let token = try await session.accessToken(), !token.isEmpty {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        if let tenantID = try await session.tenantID(), !tenantID.isEmpty, endpoint.sendsTenantHeader {
            request.setValue(tenantID, forHTTPHeaderField: "X-Tenant-ID")
        }
        return request
    }

    public func refreshNow() async throws {
        guard let current = try await session.accessToken(), !current.isEmpty else {
            throw APIError.unauthorized
        }
        let fresh = try await refresher.refresh(current: current)
        try await session.adopt(fresh)
    }

    /// Tenant and token live in the keychain; the body encoder is shared so every request serialises the
    /// same way, including the login body that must not grow extra keys.
    static func encodeBody<T: Encodable>(_ value: T) throws -> Data {
        try JSONEncoder().encode(value)
    }
}

/// What `sendRaw` read back: the bytes, and the two things the response headers said about them.
public struct RawResponse: Equatable, Sendable {
    public let data: Data
    public let mimeType: String
    /// The name from `Content-Disposition`, which the server sanitises before writing it
    /// (`TeamArtifactController.kt:96`). `nil` when the header named no file, which is the caller's cue to
    /// use the name its own row carries.
    public let suggestedFilename: String?

    public init(data: Data, mimeType: String, suggestedFilename: String?) {
        self.data = data
        self.mimeType = mimeType
        self.suggestedFilename = hxPresented(suggestedFilename)
    }
}

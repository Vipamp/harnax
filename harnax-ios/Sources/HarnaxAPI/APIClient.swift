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
        guard endpoint.authenticated, endpoint.mayRefresh, case .failure(.unauthorized) = first,
              await isBearerLeg(endpoint)
        else { return first }
        do {
            try await refreshNow()
        } catch {
            return .failure(await settleRefresh(error))
        }
        let replay = await perform(type, endpoint)
        await settleReplay(replay)
        return replay
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
        guard endpoint.authenticated, endpoint.mayRefresh, case .failure(.unauthorized) = first,
              await isBearerLeg(endpoint)
        else { return first }
        do {
            try await refreshNow()
        } catch {
            return .failure(await settleRefresh(error))
        }
        let replay = await performRaw(endpoint)
        await settleReplay(replay)
        return replay
    }

    /// Whether the credential this call went out on is one a renewal can replace. The permanent key does not
    /// expire, so a 401 carrying it says the *key* was rejected, and a fresher bearer would change nothing —
    /// except the session this side would clear for it. Same test the stream makes
    /// (`Sources/HarnaxAPI/ChatStreamClient.swift:115-119`).
    private func isBearerLeg(_ endpoint: Endpoint) async -> Bool {
        if endpoint.base != .router { return true }
        return ((try? await session.routerAPIKey()) ?? "").isEmpty
    }

    /// What a failed refresh means: only the server saying "this credential is dead" ends the session.
    ///
    /// A timeout, an unreachable host or a 5xx on the refresh route says something about the network, not
    /// about the token — and the previous behaviour signed the user out of all of them. The backend only ever
    /// rejects a refresh with its own 401 (`harnax-admin/.../TokenController.kt:31-68` requires the old token,
    /// `JwtAuthenticationFilter.kt` answers `AuthenticationServiceException` for an invalid or blacklisted one),
    /// and the web reference client does exactly this split: the 401 *response* branch clears the stored
    /// token, while the branch with no response at all only asks the user to retry
    /// (`harnax-webui/src/requestErrorConfig.ts:135-176`).
    ///
    /// So the refresh's own error goes back verbatim, which also keeps the caller honest: `AuthFlow.profile()`
    /// and `switchTenant(to:)` treat `.unauthorized` as a session event, and an `.offline` reaching them means
    /// "retry", not "log out".
    private func settleRefresh(_ error: any Error) async -> APIError {
        let failure = (error as? APIError) ?? URLSessionTransport.map(error)
        guard case .unauthorized = failure else { return failure }
        try? await session.signOut()
        return .unauthorized
    }

    /// The refresh minted a token and the server still said 401: that is the second, and only other, place a
    /// session is known to be dead.
    private func settleReplay<T>(_ replay: Result<T, APIError>) async {
        if case .failure(.unauthorized) = replay { try? await session.signOut() }
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
            // The body is the file when the call succeeded, but a failure carries admin's own `ResultVo`, and
            // which sentence it is decides whether the 404 is about the deployment or about the file.
            return .failure(.business(code: response.statusCode, message: ResponseMapper.serverMessage(in: data) ?? ""))
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
        if endpoint.base == .router, let key = try await session.routerAPIKey(), !key.isEmpty {
            // The key goes out alone, because the router's filter reads `Authorization` first and answers 401
            // on a bearer it cannot verify before it ever reaches `X-Api-Key`
            // (`harnax-auth/src/main/kotlin/com/agnetix/harnax/auth/UnifiedAuthFilter.kt:47-93`) — and admin's
            // user JWT is signed with a different secret than the one the router verifies with, so it never
            // verifies there. The console sends the same shape (`ChatWindow.tsx:153-164`), and with no tenant
            // header: the router derives the tenant off the credential itself
            // (`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/RouterMonitorController.kt:108`).
            request.setValue(key, forHTTPHeaderField: "X-Api-Key")
            return request
        }
        // Refreshing a soon-to-expire token up front avoids a 401 round trip on the first call after the
        // app has been backgrounded. Concurrent calls may each refresh; the backend tokens are stateless
        // and both stay valid, so no single-flight lock is needed yet. A call that ends the session opts out:
        // revoking a freshly minted token while the one being discarded stays live would leak the session.
        if endpoint.mayRefresh, (try? await session.needsRefresh()) ?? false {
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

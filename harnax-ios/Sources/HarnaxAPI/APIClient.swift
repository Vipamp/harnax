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

    private func buildRequest(for endpoint: Endpoint) async throws -> URLRequest {
        let base = try await configs.baseURL(for: endpoint.base)
        guard let url = endpoint.url(baseURL: base) else {
            throw APIError.invalidServerConfig(base)
        }
        var request = URLRequest(url: url)
        request.httpMethod = endpoint.method.rawValue
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(language(), forHTTPHeaderField: "Accept-Language")
        if let body = endpoint.body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
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
        if let tenantID = try await session.tenantID(), !tenantID.isEmpty {
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

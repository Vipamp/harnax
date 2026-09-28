import Foundation
import HarnaxCore

/// `POST /api/admin/auth/refresh-token`. Deliberately bypasses `APIClient`, because the client is what
/// calls this and a retry loop across the two would recurse.
public struct TokenRefresher: TokenRefreshing {
    public static let path = "/api/admin/auth/refresh-token"

    private let transport: HTTPRequesting
    private let configs: ServerConfigStore

    public init(transport: HTTPRequesting = URLSessionTransport(), configs: ServerConfigStore) {
        self.transport = transport
        self.configs = configs
    }

    public func refresh(current accessToken: String) async throws -> RefreshedToken {
        let base = try await configs.baseURL(for: .admin)
        guard let url = URL(string: base + Self.path) else { throw APIError.invalidServerConfig(base) }
        var request = URLRequest(url: url)
        request.httpMethod = HTTPMethod.post.rawValue
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        // The backend reads the caller and the tenant from this header's token (`TokenController.kt:39-45`).
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        let (data, response) = try await transport.perform(request)
        return try ResponseMapper.map(RefreshedToken.self, data: data, statusCode: response.statusCode)
    }
}

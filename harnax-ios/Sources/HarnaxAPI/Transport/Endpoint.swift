import Foundation
import HarnaxCore

public enum HTTPMethod: String, Sendable {
    case get = "GET"
    case post = "POST"
    case put = "PUT"
    case delete = "DELETE"
}

public enum APIBase: Sendable {
    case admin
    case router
}

/// A request before it gets a host, an auth header and a tenant header.
public struct Endpoint: Sendable {
    public let method: HTTPMethod
    public let path: String
    public let query: [URLQueryItem]
    public let body: Data?
    public let base: APIBase
    /// Login and refresh are the two endpoints that must be sent without a bearer token.
    public let authenticated: Bool

    public init(
        _ method: HTTPMethod,
        path: String,
        query: [URLQueryItem] = [],
        body: Data? = nil,
        base: APIBase = .admin,
        authenticated: Bool = true
    ) {
        self.method = method
        self.path = path
        self.query = query
        self.body = body
        self.base = base
        self.authenticated = authenticated
    }

    public func url(baseURL: String) -> URL? {
        var components = URLComponents(string: baseURL + path)
        if !query.isEmpty { components?.queryItems = query }
        return components?.url
    }
}

extension Endpoint {
    /// The query every admin list route shares. Both filters are optional server-side
    /// (`AgentController.kt:47-54`), so an unused one is left off the URL instead of sent blank.
    static func pageItems(num: Int, size: Int, name: String?, status: Int?) -> [URLQueryItem] {
        var items = [
            URLQueryItem(name: "pageNum", value: String(num)),
            URLQueryItem(name: "pageSize", value: String(size)),
        ]
        if let name, !name.isEmpty {
            items.append(URLQueryItem(name: "name", value: name))
        }
        if let status {
            items.append(URLQueryItem(name: "status", value: String(status)))
        }
        return items
    }
}

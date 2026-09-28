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
    /// `nil` means JSON. The ZIP skill source route is the one endpoint whose body is
    /// `multipart/form-data`, and its value has to carry the boundary.
    public let contentType: String?
    /// Login and refresh are the two endpoints that must be sent without a bearer token.
    public let authenticated: Bool

    public init(
        _ method: HTTPMethod,
        path: String,
        query: [URLQueryItem] = [],
        body: Data? = nil,
        base: APIBase = .admin,
        contentType: String? = nil,
        authenticated: Bool = true
    ) {
        self.method = method
        self.path = path
        self.query = query
        self.body = body
        self.base = base
        self.contentType = contentType
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

    /// A caller-supplied value going into one path segment. Opaque ids reach the path as often as the query,
    /// and `URLComponents(string:)` encodes nothing it is handed, so a value carrying a `/` or a `?` would
    /// silently be read as another segment or as a query. `/`, `?` and `#` are the three characters a path
    /// segment must never contain.
    static func segment(_ value: String) -> String {
        let allowed = CharacterSet.urlPathAllowed.subtracting(CharacterSet(charactersIn: "/?#"))
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }
}

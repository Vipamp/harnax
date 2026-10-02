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
    /// `false` for the one call that ends the session instead of using it. It still carries the bearer — the
    /// backend reads the token off the header to revoke it (`AuthController.kt:54-58`) — but neither the
    /// proactive refresh nor the 401 replay runs for it, so a logout can never mint a fresher token than the
    /// one the user is throwing away, nor answer its own 401 with a second logout.
    public let mayRefresh: Bool
    /// `false` for the one route the console sends on the bearer token alone: the team artifact download reads
    /// the tenant off the session row rather than off a header
    /// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TeamArtifactController.kt:108-127`),
    /// and `harnax-webui/src/services/ant-design-pro/team.ts:91-113` sends no `X-Tenant-ID` with it. Everything
    /// else in the app is tenant-scoped by that header, so the flag is on unless a route says otherwise.
    public let sendsTenantHeader: Bool

    public init(
        _ method: HTTPMethod,
        path: String,
        query: [URLQueryItem] = [],
        body: Data? = nil,
        base: APIBase = .admin,
        contentType: String? = nil,
        authenticated: Bool = true,
        mayRefresh: Bool = true,
        sendsTenantHeader: Bool = true
    ) {
        self.method = method
        self.path = path
        self.query = query
        self.body = body
        self.base = base
        self.contentType = contentType
        self.authenticated = authenticated
        self.mayRefresh = mayRefresh
        self.sendsTenantHeader = sendsTenantHeader
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

    /// A number going into the query string. Spring binds `2.0` and `2` the same, but the console puts a bare
    /// JavaScript number into the parameter
    /// (`harnax-webui/src/pages/model/components/ModelListTable.tsx:73-77`), and one shape on both apps is what
    /// lets a request be diffed by eye. A value no `Int` holds exactly keeps Swift's own rendering.
    static func number(_ value: Double) -> String {
        if let whole = Int(exactly: value) { return String(whole) }
        return String(value)
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

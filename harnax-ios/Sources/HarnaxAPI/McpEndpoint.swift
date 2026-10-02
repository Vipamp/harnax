import Foundation
import HarnaxCore

/// The MCP routes, mirroring `McpServerController.kt:32-175` and `McpOAuthController.kt:45-135`.
///
/// Two shapes are easy to get wrong and are the reason these are built here rather than inline:
/// `toggle` carries `status` in the query and sends no body, and the page filter is named `keyword`, not
/// `name` like the agent and team routes (`McpServerController.kt:43-46`).
enum McpEndpoint {
    static let base = "/api/admin/mcp"

    static func page(keyword: String?, status: Int?, type: String?, num: Int, size: Int) -> Endpoint {
        var query = [
            URLQueryItem(name: "pageNum", value: String(num)),
            URLQueryItem(name: "pageSize", value: String(size)),
        ]
        // An unused filter is left off the URL: the server reads a present-but-blank `keyword` as a
        // LIKE pattern, which is not the same question.
        if let keyword = hxPresented(keyword) {
            query.append(URLQueryItem(name: "keyword", value: keyword))
        }
        if let status {
            query.append(URLQueryItem(name: "status", value: String(status)))
        }
        if let type = hxPresented(type) {
            query.append(URLQueryItem(name: "type", value: type))
        }
        return Endpoint(.get, path: "\(base)/page", query: query)
    }

    static func detail(id: Int64) -> Endpoint {
        Endpoint(.get, path: "\(base)/\(id)")
    }

    static func create(_ draft: McpServerDraft) throws -> Endpoint {
        Endpoint(.post, path: base, body: try APIClient.encodeBody(draft))
    }

    /// `update/{id}`, not `PUT /{id}` — the route the console posts its patch to
    /// (`McpServerController.kt:87-95`).
    static func update(id: Int64, patch: McpServerPatch) throws -> Endpoint {
        Endpoint(.put, path: "\(base)/update/\(id)", body: try APIClient.encodeBody(patch))
    }

    static func toggle(id: Int64, status: Int) -> Endpoint {
        Endpoint(
            .put,
            path: "\(base)/toggle/\(id)",
            query: [URLQueryItem(name: "status", value: String(status))]
        )
    }

    static func relatedAgents(id: Int64) -> Endpoint {
        Endpoint(.get, path: "\(base)/\(id)/related-agents")
    }

    /// Logical delete; the server also drops the agent bindings and the per-user grants with it
    /// (`McpServerServiceImpl.kt:320-337`).
    static func delete(id: Int64) -> Endpoint {
        Endpoint(.delete, path: "\(base)/\(id)")
    }

    /// No body, and the answer is a bare `ResultVo<Boolean>` whose refusal text rides in `message`.
    static func connectivityTest(id: Int64) -> Endpoint {
        Endpoint(.post, path: "\(base)/\(id)/connectivity-test")
    }

    /// Snake-case on the wire, unlike every other route here.
    static func tools(id: Int64) -> Endpoint {
        Endpoint(.get, path: "\(base)/\(id)/list_tools")
    }

    /// No body: the run reads the server row and reaches the network itself
    /// (`McpOAuthController.kt:45-56`).
    static func oauthDiscover(id: Int64) -> Endpoint {
        Endpoint(.post, path: "\(base)/\(id)/oauth/discover")
    }

    static func oauthClient(id: Int64, _ draft: McpOAuthClientDraft) throws -> Endpoint {
        Endpoint(.post, path: "\(base)/\(id)/oauth/client", body: try APIClient.encodeBody(draft))
    }

    /// Not under `/{id}`: the pending request the state names already says which server this consent was
    /// for, and taking an id here would let a captured state be pointed at a server of the caller's
    /// choosing (`McpOAuthController.kt:90-104`).
    static func oauthExchange(_ draft: McpOAuthExchangeDraft) throws -> Endpoint {
        Endpoint(.post, path: "\(base)/oauth/exchange", body: try APIClient.encodeBody(draft))
    }

    static func oauthStatus(id: Int64) -> Endpoint {
        Endpoint(.get, path: "\(base)/\(id)/oauth/status")
    }

    static func oauthRevoke(id: Int64) -> Endpoint {
        Endpoint(.post, path: "\(base)/\(id)/oauth/revoke")
    }

    /// `scope` is an override of the configured scopes list; omitted asks for what the row carries.
    static func oauthAuthorizeURL(id: Int64, scope: String?) -> Endpoint {
        var query: [URLQueryItem] = []
        if let scope = hxPresented(scope) {
            query.append(URLQueryItem(name: "scope", value: scope))
        }
        return Endpoint(.get, path: "\(base)/\(id)/oauth/authorize-url", query: query)
    }
}

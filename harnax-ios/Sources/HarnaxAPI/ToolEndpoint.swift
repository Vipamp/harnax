import Foundation
import HarnaxCore

/// The three tool routes this stack touches. All are GETs under `/api/admin/tools`, and there is nothing
/// else to model: this API has no write path (`AgentToolController.kt:13-15`).
///
/// The one shape that is easy to get wrong: this page route names its filter `keyword`, where the agent and
/// team routes name theirs `name` (`AgentToolController.kt:31`) — so it cannot reuse `Endpoint.pageItems`.
enum ToolEndpoint {
    static func page(keyword: String?, status: Int?, num: Int, size: Int) -> Endpoint {
        var items = [
            URLQueryItem(name: "pageNum", value: String(num)),
            URLQueryItem(name: "pageSize", value: String(size)),
        ]
        // An unused filter is left off the URL rather than sent blank — and "unused" includes a keyword
        // that is only spaces, which would otherwise reach the server as a pattern matching almost nothing.
        if let keyword = hxPresented(keyword) {
            items.append(URLQueryItem(name: "keyword", value: keyword))
        }
        if let status {
            items.append(URLQueryItem(name: "status", value: String(status)))
        }
        return Endpoint(.get, path: "/api/admin/tools/page", query: items)
    }

    /// `AgentToolController.kt:41-52`: a missing id is a normal envelope answer of `code: 404`, not an HTTP
    /// 404, so the caller's error mapping already carries the server's "tool not found" sentence.
    static func detail(id: Int64) -> Endpoint {
        Endpoint(.get, path: "/api/admin/tools/\(id)")
    }

    /// `AgentToolController.kt:54-65`, the agent wizard's tool candidates. No path parameter and no query
    /// item: the server does the filtering (`WHERE status = 1 AND active = 1 AND is_required = 0`, ordered
    /// by `name`, `AgentToolMapper.xml:60-62`), so narrowing or re-sorting this list locally would simply
    /// diverge from the web form.
    static var available: Endpoint {
        Endpoint(.get, path: "/api/admin/tools/available")
    }
}

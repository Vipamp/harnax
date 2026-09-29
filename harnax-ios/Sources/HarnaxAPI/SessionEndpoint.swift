import Foundation
import HarnaxCore

/// The conversation routes the list screen touches: the admin class mapped at
/// `/api/admin/sessions` (`SessionController.kt:20`).
///
/// Clearing a conversation is not one of them — it is a `CLEAR` on the router's command channel, which
/// `AgentCommanding` already maps, so this file does not restate it.
///
/// The shape that is easy to get wrong: this page route names its filter `keyword`, where the agent and
/// team routes name theirs `name` (`SessionController.kt:40-41`) — so it cannot reuse `Endpoint.pageItems`,
/// exactly like the tool and MCP pages before it.
enum SessionEndpoint {
    static let base = "/api/admin/sessions"

    static func page(keyword: String?, status: Int?, num: Int, size: Int) -> Endpoint {
        var query = [
            URLQueryItem(name: "pageNum", value: String(num)),
            URLQueryItem(name: "pageSize", value: String(size)),
        ]
        // An unused filter is left off the URL rather than sent blank: a present-but-empty `keyword` would
        // reach the mapper as a `LIKE '%%'` (`SessionMapper.xml:108-110`) — harmless — but `status=` would
        // fail to bind the `Int?` parameter outright.
        if let keyword = hxPresented(keyword) {
            query.append(URLQueryItem(name: "keyword", value: keyword))
        }
        if let status {
            query.append(URLQueryItem(name: "status", value: String(status)))
        }
        return Endpoint(.get, path: "\(base)/page", query: query)
    }

    /// `PUT /update/{id}`, not `PUT /{id}` — and the body is the create DTO, whole-row update included
    /// (`SessionController.kt:91-109`).
    static func rename(id: Int64, request: SessionRenameRequest) throws -> Endpoint {
        Endpoint(.put, path: "\(base)/update/\(id)", body: try APIClient.encodeBody(request))
    }

    /// `status` is a query parameter and the request sends no body (`SessionController.kt:143-161`).
    static func toggle(id: Int64, status: Int) -> Endpoint {
        Endpoint(
            .put,
            path: "\(base)/toggle/\(id)",
            query: [URLQueryItem(name: "status", value: String(status))]
        )
    }

    /// The numeric primary key, not the string business key (`SessionController.kt:163-172`).
    static func delete(id: Int64) -> Endpoint {
        Endpoint(.delete, path: "\(base)/\(id)")
    }

    /// `GET /check-title?title=`. A duplicate-name lookup is a **query** on the same path the create `POST`
    /// answers (`SessionController.kt:68-89`), so the two differ by method and query only.
    static func checkTitle(title: String) -> Endpoint {
        Endpoint(.get, path: "\(base)/check-title", query: [URLQueryItem(name: "title", value: title)])
    }

    /// `POST /api/admin/sessions` — the base path with no extra segment (`SessionController.kt:80-82`).
    static func create(_ draft: SessionCreateDraft) throws -> Endpoint {
        Endpoint(.post, path: base, body: try APIClient.encodeBody(draft))
    }

    /// The two config routes take the **string** business key, where the rename, the toggle and the delete
    /// above take the numeric id (`SessionController.kt:111-141`). A `web-…` id cannot break a path segment,
    /// but the key is caller-supplied on the detail screen, so it goes through `Endpoint.segment` anyway.
    static func config(sessionId: String) -> Endpoint {
        Endpoint(.get, path: "\(base)/\(Endpoint.segment(sessionId))/config")
    }

    static func updateConfig(sessionId: String, _ change: SessionChatChange) throws -> Endpoint {
        Endpoint(
            .put,
            path: "\(base)/\(Endpoint.segment(sessionId))/config",
            body: try APIClient.encodeBody(change)
        )
    }
}

import Foundation
import HarnaxCore

/// The agent routes the card screen and the edit form touch — the same ones the web console calls. The one
/// shape that is easy to guess wrong: `toggle` carries `status` in the query string and sends no body.
enum AgentEndpoint {
    static let root = "/api/admin/agents"
    static let pagePath = root + "/page"

    static func page(name: String?, status: Int?, num: Int, size: Int) -> Endpoint {
        Endpoint(
            .get,
            path: pagePath,
            query: Endpoint.pageItems(num: num, size: size, name: name, status: status)
        )
    }

    /// `status` is a query parameter, not a body (`AgentController.kt:120-130`).
    static func toggle(id: Int64, status: Int) -> Endpoint {
        Endpoint(
            .put,
            path: "/api/admin/agents/toggle/\(id)",
            query: [URLQueryItem(name: "status", value: String(status))]
        )
    }

    /// A refused delete answers `code != 200` with the reason in `message`, and the message is the
    /// server's own English sentence naming the blocking teams or sessions.
    /// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:212-254`)
    static func delete(id: Int64) -> Endpoint {
        Endpoint(.delete, path: "/api/admin/agents/\(id)")
    }

    static func relatedSessions(id: Int64) -> Endpoint {
        Endpoint(.get, path: "\(root)/\(id)/related-sessions")
    }

    /// Create is the bare collection path (`AgentController.kt:71-80`), and the reply names no new id — the
    /// caller re-reads the page to find the row it just made.
    static func create(_ draft: AgentSaveDraft) throws -> Endpoint {
        Endpoint(.post, path: root, body: try APIClient.encodeBody(draft))
    }

    /// `PUT /api/admin/agents/update/{agentId}` (`AgentController.kt:82-92`): the id is a path variable and the
    /// body carries no `id` field at all.
    static func update(id: Int64, _ draft: AgentSaveDraft) throws -> Endpoint {
        Endpoint(.put, path: "\(root)/update/\(id)", body: try APIClient.encodeBody(draft))
    }
}

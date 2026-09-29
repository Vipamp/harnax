import Foundation
import HarnaxCore

/// The team routes the list screen and the team form touch: the agent set mirrored onto `/api/admin/teams/**`.
enum TeamEndpoint {
    static let root = "/api/admin/teams"

    static func page(name: String?, status: Int?, num: Int, size: Int) -> Endpoint {
        Endpoint(
            .get,
            path: root + "/page",
            query: Endpoint.pageItems(num: num, size: size, name: name, status: status)
        )
    }

    static func toggle(id: Int64, status: Int) -> Endpoint {
        Endpoint(
            .put,
            path: "/api/admin/teams/toggle/\(id)",
            query: [URLQueryItem(name: "status", value: String(status))]
        )
    }

    /// Refused only when sessions still bind the team; the message names up to five session ids
    /// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/TeamServiceImpl.kt:153-168`).
    static func delete(id: Int64) -> Endpoint {
        Endpoint(.delete, path: "/api/admin/teams/\(id)")
    }

    static func relatedSessions(id: Int64) -> Endpoint {
        Endpoint(.get, path: "\(root)/\(id)/related-sessions")
    }

    /// `POST /api/admin/teams` (`TeamController.kt:68-75`). The reply names no new id.
    static func create(_ draft: TeamSaveDraft) throws -> Endpoint {
        Endpoint(.post, path: root, body: try APIClient.encodeBody(draft))
    }

    /// `PUT /api/admin/teams/update/{id}` (`TeamController.kt:77-86`).
    static func update(id: Int64, _ draft: TeamSaveDraft) throws -> Endpoint {
        Endpoint(.put, path: "\(root)/update/\(id)", body: try APIClient.encodeBody(draft))
    }
}

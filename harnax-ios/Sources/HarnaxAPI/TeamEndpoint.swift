import Foundation

/// The team routes the list screen touches: the agent set mirrored onto `/api/admin/teams/**`.
enum TeamEndpoint {
    static func page(name: String?, status: Int?, num: Int, size: Int) -> Endpoint {
        Endpoint(
            .get,
            path: "/api/admin/teams/page",
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
        Endpoint(.get, path: "/api/admin/teams/\(id)/related-sessions")
    }
}

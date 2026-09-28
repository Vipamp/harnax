import Foundation

/// The CLI routes the two screens touch, all of them under `/api/admin/clis`.
///
/// There is no create/update/delete route to build an endpoint for — `CliController.kt:18-25` says so in
/// its own class comment — and the two `related-*` reads exist purely so the effect of the switch is
/// visible before it is thrown.
enum CliEndpoint {
    static func page(name: String?, status: Int?, num: Int, size: Int) -> Endpoint {
        Endpoint(
            .get,
            path: "/api/admin/clis/page",
            query: Endpoint.pageItems(num: num, size: size, name: name, status: status)
        )
    }

    /// The only reader of `payloadDigest` / `depsApt` / `runtimeEnv`; the page shape answers without them.
    static func detail(id: Int64) -> Endpoint {
        Endpoint(.get, path: "/api/admin/clis/\(id)")
    }

    /// `status` is a query parameter and the request sends no body (`CliController.kt:72-82`).
    static func toggle(id: Int64, status: Int) -> Endpoint {
        Endpoint(
            .put,
            path: "/api/admin/clis/toggle/\(id)",
            query: [URLQueryItem(name: "status", value: String(status))]
        )
    }

    /// Read before the switch is thrown. A refused read blocks the write rather than passing for "nobody
    /// binds it" (`harnax-webui/src/pages/cli/index.tsx:114-131`).
    static func relatedAgents(id: Int64) -> Endpoint {
        Endpoint(.get, path: "/api/admin/clis/\(id)/related-agents")
    }

    static func relatedSessions(id: Int64) -> Endpoint {
        Endpoint(.get, path: "/api/admin/clis/\(id)/related-sessions")
    }
}

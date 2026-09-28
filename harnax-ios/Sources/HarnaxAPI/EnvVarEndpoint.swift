import Foundation
import HarnaxCore

/// The environment-variable routes the two screens touch.
///
/// The shape that is easy to guess wrong is the toggle: this domain puts the id *before* the verb
/// (`PUT /{id}/toggle?enabled=`) where agents, teams and API Keys put it after
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/EnvVariableController.kt:95-99`
/// against `ApiKeyController.kt:89-99`). The asymmetry is on the wire, so it stays in the endpoint file
/// rather than being smoothed into a shared helper.
enum EnvVarEndpoint {
    static let root = "/api/admin/env-variables"

    /// `keyword` only — this page takes no status filter (`EnvVariableController.kt:27-31`).
    static func page(keyword: String?, num: Int, size: Int) -> Endpoint {
        var items = [
            URLQueryItem(name: "pageNum", value: String(num)),
            URLQueryItem(name: "pageSize", value: String(size)),
        ]
        if let keyword = hxPresented(keyword) {
            items.append(URLQueryItem(name: "keyword", value: keyword))
        }
        return Endpoint(.get, path: "\(root)/page", query: items)
    }

    /// Answers `ResultVo<Void>`, so the caller re-reads the list instead of taking a row from here.
    static func create(_ draft: EnvVarDraft) throws -> Endpoint {
        Endpoint(.post, path: root, body: try APIClient.encodeBody(draft))
    }

    static func update(id: Int64, _ change: EnvVarChange) throws -> Endpoint {
        Endpoint(.put, path: "\(root)/update/\(id)", body: try APIClient.encodeBody(change))
    }

    static func toggle(id: Int64, enabled: Int) -> Endpoint {
        Endpoint(
            .put,
            path: "\(root)/\(id)/toggle",
            query: [URLQueryItem(name: "enabled", value: String(enabled))]
        )
    }

    /// Refused while an agent still binds the variable; the message names up to five of them
    /// (`EnvVariableServiceImpl.kt:199-208`).
    static func delete(id: Int64) -> Endpoint {
        Endpoint(.delete, path: "\(root)/\(id)")
    }
}

import Foundation
import HarnaxCore

/// The API Key routes the list and the two forms touch (`ApiKeyController.kt:23`, prefix
/// `/api/admin/api-keys`).
///
/// Where this domain differs from the agent and team sets: the update route answers `ResultVo<Void>`, so
/// a saved row is re-read; `enabled` rides the toggle's query string, as elsewhere; and the two routes
/// that hand out a raw key are POSTs with no body of their own beyond the create draft.
enum ApiKeyEndpoint {
    static let root = "/api/admin/api-keys"

    /// `pageNum,pageSize,keyword,enabled` (`ApiKeyController.kt:36-39`). An unused filter is left off the
    /// URL rather than sent blank, the same rule `Endpoint.pageItems` follows for the agent set.
    static func page(keyword: String?, enabled: Int?, num: Int, size: Int) -> Endpoint {
        var items = [
            URLQueryItem(name: "pageNum", value: String(num)),
            URLQueryItem(name: "pageSize", value: String(size)),
        ]
        if let keyword, !keyword.isEmpty {
            items.append(URLQueryItem(name: "keyword", value: keyword))
        }
        if let enabled {
            items.append(URLQueryItem(name: "enabled", value: String(enabled)))
        }
        return Endpoint(.get, path: "\(root)/page", query: items)
    }

    static func create(_ draft: ApiKeyDraft) throws -> Endpoint {
        Endpoint(.post, path: root, body: try APIClient.encodeBody(draft))
    }

    static func update(id: Int64, _ change: ApiKeyChange) throws -> Endpoint {
        Endpoint(.put, path: "\(root)/update/\(id)", body: try APIClient.encodeBody(change))
    }

    static func toggle(id: Int64, enabled: Int) -> Endpoint {
        Endpoint(
            .put,
            path: "\(root)/toggle/\(id)",
            query: [URLQueryItem(name: "enabled", value: String(enabled))]
        )
    }

    static func delete(id: Int64) -> Endpoint {
        Endpoint(.delete, path: "\(root)/\(id)")
    }

    /// No body, and the answer is the one and only copy of the new raw key (`:112-122`).
    static func regenerate(id: Int64) -> Endpoint {
        Endpoint(.post, path: "\(root)/\(id)/regenerate")
    }
}

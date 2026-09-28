import Foundation
import HarnaxCore

/// The model routes both levels touch — the provider set under `/api/admin/model-providers/**` and its
/// models under `/api/admin/models/**`.
///
/// The three shapes worth writing down, because they are the ones a guess gets wrong:
/// - `toggle` carries `status` in the query string and sends no body, on both levels;
/// - an update is a partial update, so a field the screen did not send keeps its stored value — which is
///   why the save bodies in `ModelPayloads.swift` send every capability bit explicitly;
/// - the model page's `tags` filter is one comma-joined string, not a repeated query key.
enum ModelEndpoint {
    static let providerPath = "/api/admin/model-providers"
    static let modelPath = "/api/admin/models"

    static func providerPage(name: String?, type: String?, status: Int?, num: Int, size: Int) -> Endpoint {
        var query = Endpoint.pageItems(num: num, size: size, name: name, status: status)
        if let type = hxPresented(type) {
            query.append(URLQueryItem(name: "type", value: type))
        }
        return Endpoint(.get, path: "\(providerPath)/page", query: query)
    }

    static func providerStats(id: Int64) -> Endpoint {
        Endpoint(.get, path: "\(providerPath)/\(id)/stats")
    }

    static func providerToggle(id: Int64, status: Int) -> Endpoint {
        Endpoint(
            .put,
            path: "\(providerPath)/toggle/\(id)",
            query: [URLQueryItem(name: "status", value: String(status))]
        )
    }

    /// Refused while the provider still owns a model; the envelope message is the server's own sentence.
    static func providerDelete(id: Int64) -> Endpoint {
        Endpoint(.delete, path: "\(providerPath)/\(id)")
    }

    static func providerCreate(body: Data) -> Endpoint {
        Endpoint(.post, path: providerPath, body: body)
    }

    static func providerUpdate(id: Int64, body: Data) -> Endpoint {
        Endpoint(.put, path: "\(providerPath)/update/\(id)", body: body)
    }

    /// No body, and the answer is a bare boolean (`ModelProviderController.kt:122-129`).
    static func providerTest(id: Int64) -> Endpoint {
        Endpoint(.post, path: "\(providerPath)/\(id)/test")
    }

    static func modelPage(
        providerID: Int64,
        name: String?,
        status: Int?,
        tags: [String],
        num: Int,
        size: Int
    ) -> Endpoint {
        var query = Endpoint.pageItems(num: num, size: size, name: name, status: status)
        query.append(URLQueryItem(name: "providerId", value: String(providerID)))
        if !tags.isEmpty {
            query.append(URLQueryItem(name: "tags", value: tags.joined(separator: ",")))
        }
        return Endpoint(.get, path: "\(modelPath)/page", query: query)
    }

    static func modelToggle(id: Int64, status: Int) -> Endpoint {
        Endpoint(
            .put,
            path: "\(modelPath)/toggle/\(id)",
            query: [URLQueryItem(name: "status", value: String(status))]
        )
    }

    static func modelDelete(id: Int64) -> Endpoint {
        Endpoint(.delete, path: "\(modelPath)/\(id)")
    }

    static func modelCreate(body: Data) -> Endpoint {
        Endpoint(.post, path: modelPath, body: body)
    }

    static func modelUpdate(id: Int64, body: Data) -> Endpoint {
        Endpoint(.put, path: "\(modelPath)/update/\(id)", body: body)
    }
}

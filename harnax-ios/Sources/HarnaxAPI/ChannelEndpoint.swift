import Foundation
import HarnaxCore

/// The channel routes the list, the form and the scan sheet touch.
///
/// Two shapes here differ from the rest of the surface and cannot be copied from a neighbour: the update
/// puts the id after the verb (`PUT /update/{id}`, `ChannelController.kt:73`) while the detail and delete
/// put it alone in the path, and the toggle's flag is called `status`
/// (`ChannelController.kt:83-87`) where the API Key and environment-variable toggles call theirs `enabled`.
enum ChannelEndpoint {
    static let root = "/api/admin/channels"

    /// `pageNum,pageSize,keyword,type,status` (`ChannelController.kt:34-46`). An unused filter is left off
    /// the URL rather than sent blank — the type code goes to `ChannelType.fromCode`, which is
    /// case-sensitive and would simply match nothing.
    static func page(keyword: String?, type: String?, status: Int?, num: Int, size: Int) -> Endpoint {
        var items = [
            URLQueryItem(name: "pageNum", value: String(num)),
            URLQueryItem(name: "pageSize", value: String(size)),
        ]
        if let keyword = hxPresented(keyword) {
            items.append(URLQueryItem(name: "keyword", value: keyword))
        }
        if let type = hxPresented(type) {
            items.append(URLQueryItem(name: "type", value: type))
        }
        if let status {
            items.append(URLQueryItem(name: "status", value: String(status)))
        }
        return Endpoint(.get, path: "\(root)/page", query: items)
    }

    static func create(_ draft: ChannelDraft) throws -> Endpoint {
        Endpoint(.post, path: root, body: try APIClient.encodeBody(draft))
    }

    static func update(id: Int64, _ change: ChannelChange) throws -> Endpoint {
        Endpoint(.put, path: "\(root)/update/\(id)", body: try APIClient.encodeBody(change))
    }

    static func toggle(id: Int64, status: Int) -> Endpoint {
        Endpoint(
            .put,
            path: "\(root)/toggle/\(id)",
            query: [URLQueryItem(name: "status", value: String(status))]
        )
    }

    static func delete(id: Int64) -> Endpoint {
        Endpoint(.delete, path: "\(root)/\(id)")
    }

    /// The one call on the *runtime* base rather than the admin one. An empty id list is refused by the
    /// server (`AgentProxyController.kt:218-221`), so the client never sends one.
    static func sandboxStatuses(sessionIds: [String]) -> Endpoint {
        Endpoint(
            .get,
            path: "/api/router/agent/workspace/status",
            query: [URLQueryItem(name: "sessionIds", value: sessionIds.joined(separator: ","))],
            base: .router
        )
    }

    // MARK: - WeChat QR login (`WechatLoginController.kt:28-63`)

    static func startWechatLogin(id: Int64) -> Endpoint {
        Endpoint(.post, path: "\(root)/\(id)/wechat/login")
    }

    static func wechatLoginStatus(id: Int64) -> Endpoint {
        Endpoint(.get, path: "\(root)/\(id)/wechat/login/status")
    }

    static func cancelWechatLogin(id: Int64) -> Endpoint {
        Endpoint(.post, path: "\(root)/\(id)/wechat/login/cancel")
    }
}

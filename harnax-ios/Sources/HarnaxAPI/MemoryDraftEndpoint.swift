import Foundation
import HarnaxCore

/// The memory review queue: `/api/admin/memory-drafts` and its four reads/writes
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/MemoryDraftController.kt:43,51,89,106,124`).
///
/// Two shapes copied from the skill queue, and both are the route's own rather than the app's convention:
/// 1. the list has **no `/page` suffix** — compare `SkillDraftEndpoint.swift:13` with `:51` of this controller;
/// 2. its status filter is a *name* (`PENDING` / `APPROVED` / `REJECTED`), validated against those three
///    server-side (`MemoryDraftServiceImpl.kt:594`), not the 0/1 column `Endpoint.pageItems` sends.
enum MemoryDraftEndpoint {
    static let draftsPath = "/api/admin/memory-drafts"

    /// The queue's one page. `status` and the two paging keys always go out; `agentName` (partial) and
    /// `sessionId` (exact) are sent only when the caller has a value. The service trims both and hands the
    /// result straight to the mapper (`MemoryDraftServiceImpl.kt:162-168`), so a blank would be ignored —
    /// leaving it off the URL is what keeps one request shape on both apps.
    static func page(
        status: MemoryDraftStatus,
        agentName: String?,
        sessionId: String?,
        num: Int,
        size: Int
    ) -> Endpoint {
        var query = [
            URLQueryItem(name: "pageNum", value: String(num)),
            URLQueryItem(name: "pageSize", value: String(size)),
            // Sent even though the server defaults to PENDING: it is the only way to ask for a decided arm.
            URLQueryItem(name: "status", value: status.rawValue),
        ]
        if let name = hxPresented(agentName) {
            query.append(URLQueryItem(name: "agentName", value: name))
        }
        if let session = hxPresented(sessionId) {
            query.append(URLQueryItem(name: "sessionId", value: session))
        }
        return Endpoint(.get, path: draftsPath, query: query)
    }

    static func detail(id: Int64) -> Endpoint {
        Endpoint(.get, path: "\(draftsPath)/\(id)")
    }

    static func approve(id: Int64, _ payload: MemoryDraftApprovePayload) throws -> Endpoint {
        Endpoint(.post, path: "\(draftsPath)/\(id)/approve", body: try APIClient.encodeBody(payload))
    }

    static func reject(id: Int64, _ payload: MemoryDraftRejectPayload) throws -> Endpoint {
        Endpoint(.post, path: "\(draftsPath)/\(id)/reject", body: try APIClient.encodeBody(payload))
    }
}

import Foundation
import HarnaxCore

/// The draft-review routes. `/api/admin/skill-drafts` and its three reads/writes
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillDraftController.kt:42,50,88,105,126`).
///
/// Two shapes here differ from every other list in the app, and both are easy to copy wrong:
/// 1. the queue has **no `/page` suffix** — compare `SkillEndpoint.swift:86` (`/api/admin/skills/page`) with
///    `SkillDraftController.kt:50`;
/// 2. its `status` filter is a *name*, not the 0/1 column every `Endpoint.pageItems` caller sends, so this
///    builds its query by hand instead of reaching for the shared helper (`Endpoint.swift:72-84`).
enum SkillDraftEndpoint {
    static let draftsPath = "/api/admin/skill-drafts"

    static func page(status: SkillDraftStatus, name: String?, num: Int, size: Int) -> Endpoint {
        var query = [
            URLQueryItem(name: "pageNum", value: String(num)),
            URLQueryItem(name: "pageSize", value: String(size)),
            // Sent even though the server's own default is PENDING: it is the only way to ask for one
            // particular arm, and the console sends it too (`skillDraft.ts:5-8`).
            URLQueryItem(name: "status", value: status.rawValue),
        ]
        if let keyword = name?.trimmingCharacters(in: .whitespacesAndNewlines), !keyword.isEmpty {
            query.append(URLQueryItem(name: "name", value: keyword))
        }
        return Endpoint(.get, path: draftsPath, query: query)
    }

    static func detail(id: Int64) -> Endpoint {
        Endpoint(.get, path: "\(draftsPath)/\(id)")
    }

    static func approve(id: Int64, _ payload: SkillDraftApprovePayload) throws -> Endpoint {
        Endpoint(.post, path: "\(draftsPath)/\(id)/approve", body: try APIClient.encodeBody(payload))
    }

    static func reject(id: Int64, _ payload: SkillDraftRejectPayload) throws -> Endpoint {
        Endpoint(.post, path: "\(draftsPath)/\(id)/reject", body: try APIClient.encodeBody(payload))
    }
}

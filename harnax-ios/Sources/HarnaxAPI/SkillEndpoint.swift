import Foundation
import HarnaxCore

/// The skill routes the domain touches — `/api/admin/skill-sources/**` plus the three `/api/admin/skills/**`
/// reads the right-hand table and the detail screen need.
///
/// The shapes that are easy to get wrong, all three verified against
/// `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/`:
/// 1. both toggles carry `status` in the query string and send no body
///    (`SkillSourceController.kt:114-121`, `SkillController.kt:101-112`);
/// 2. `install` is a POST whose body may be entirely absent, and absent means "the whole source"
///    (`SkillSourceController.kt:74-84`);
/// 3. `upload` is the only multipart route, and its `name` is a form field rather than a query item
///    (`SkillSourceController.kt:137-160`).
enum SkillEndpoint {
    static let sourcesPath = "/api/admin/skill-sources"
    static let skillsPath = "/api/admin/skills"

    static func sourcePage(name: String?, sourceType: String?, status: Int?, num: Int, size: Int) -> Endpoint {
        var query = Endpoint.pageItems(num: num, size: size, name: name, status: status)
        if let sourceType, !sourceType.isEmpty {
            query.append(URLQueryItem(name: "sourceType", value: sourceType))
        }
        return Endpoint(.get, path: "\(sourcesPath)/page", query: query)
    }

    static func source(id: Int64) -> Endpoint {
        Endpoint(.get, path: "\(sourcesPath)/\(id)")
    }

    static func create(_ payload: SkillSourceCreatePayload) throws -> Endpoint {
        Endpoint(.post, path: sourcesPath, body: try APIClient.encodeBody(payload))
    }

    static func update(id: Int64, _ payload: SkillSourceUpdatePayload) throws -> Endpoint {
        Endpoint(.put, path: "\(sourcesPath)/\(id)", body: try APIClient.encodeBody(payload))
    }

    static func install(id: Int64, names: [String]?) throws -> Endpoint {
        // Absent body and absent `names` both mean the whole source, so the field is only encoded when the
        // caller picked a selection. An empty selection is a different answer and has to go out as `[]`.
        guard let names else {
            return Endpoint(.post, path: "\(sourcesPath)/\(id)/install")
        }
        return Endpoint(
            .post,
            path: "\(sourcesPath)/\(id)/install",
            body: try APIClient.encodeBody(SkillInstallPayload(names: names))
        )
    }

    static func delete(id: Int64) -> Endpoint {
        Endpoint(.delete, path: "\(sourcesPath)/\(id)")
    }

    static func toggleSource(id: Int64, status: Int) -> Endpoint {
        Endpoint(
            .put,
            path: "\(sourcesPath)/toggle/\(id)",
            query: [URLQueryItem(name: "status", value: String(status))]
        )
    }

    /// Read without store; the first half of a sync.
    static func fetch(id: Int64) -> Endpoint {
        Endpoint(.get, path: "\(sourcesPath)/\(id)/fetch")
    }

    /// ZIP source creation. Part order and field names come straight from the controller signature.
    static func upload(name: String, fileName: String, payload: Data) -> Endpoint {
        let parts: [HarnaxMultipart.Part] = [.field("name", name), .file("file", fileName: fileName, payload: payload)]
        let multipart = HarnaxMultipart.makeResult(parts: parts)
        return Endpoint(
            .post,
            path: "\(sourcesPath)/upload",
            body: multipart.body,
            contentType: multipart.contentType
        )
    }

    static func skillPage(name: String?, repositoryID: Int64?, status: Int?, num: Int, size: Int) -> Endpoint {
        var query = Endpoint.pageItems(num: num, size: size, name: name, status: status)
        if let repositoryID {
            query.append(URLQueryItem(name: "repositoryId", value: String(repositoryID)))
        }
        return Endpoint(.get, path: "\(skillsPath)/page", query: query)
    }

    static func skill(id: Int64) -> Endpoint {
        Endpoint(.get, path: "\(skillsPath)/\(id)")
    }

    static func toggleSkill(id: Int64, status: Int) -> Endpoint {
        Endpoint(
            .put,
            path: "\(skillsPath)/toggle/\(id)",
            query: [URLQueryItem(name: "status", value: String(status))]
        )
    }
}

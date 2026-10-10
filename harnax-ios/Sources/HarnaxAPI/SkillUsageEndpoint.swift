import Foundation
import HarnaxCore

/// The skill-usage read: `/api/admin/skill-usage/summary`
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillUsageController.kt:23,31`).
///
/// One route, read-only. It is *not* under `/api/admin/skills/{id}`, whose `{id}` is typed `Long` — a
/// `skills/usage` segment would be matched by that route and fail to parse (`:19-20`), which is why the path
/// reads as its own family.
enum SkillUsageEndpoint {
    static let root = "/api/admin/skill-usage"

    /// `days` is always sent rather than left to the server's 30 (`:37`, `:46`): the response echoes the window
    /// it answered, so a screen can check its own label against that echo rather than assume a default.
    static func summary(days: Int) -> Endpoint {
        Endpoint(.get, path: "\(root)/summary", query: [URLQueryItem(name: "days", value: String(max(1, days)))])
    }
}

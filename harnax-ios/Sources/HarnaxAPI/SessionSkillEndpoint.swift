import Foundation
import HarnaxCore

/// The session panel's two routes, both on the router.
///
/// `GET /api/router/agent/session-skills/{sessionId}` and
/// `POST /api/router/agent/session-skills/{sessionId}/{name}/enable` —
/// `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt`, which
/// forwards to whichever agent-service instance holds the session and passes its `ResultVo` through
/// (`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/proxy/SessionRouterService.kt`). The keys are
/// agent-service's own DTO names (`SessionSkillView(name, description, enabledAt)`), camelCase, so no coding-key
/// strategy is involved.
///
/// The enable carries no body: the console sends none either, and the router reads the acting user off its own
/// auth context before it forwards (`AuthContextHolder`), because a body a client typed cannot name who pressed
/// the button.
///
/// The queue half of the panel is *not* here — it is an admin route and lives on
/// `SkillDraftEndpoint.page(status:name:sessionId:num:size:)`.
enum SessionSkillEndpoint {
    static let base = "/api/router/agent/session-skills"

    /// The path segments are encoded first, the same rule the context and plan routes follow
    /// (`ContextUsageEndpoint.swift:39-41`): a session id with a `/` in it would otherwise read as extra path
    /// segments on the way to the router, and the skill name is a caller-supplied string that goes into a path
    /// just the same.
    static func rows(sessionId: String) -> Endpoint {
        Endpoint(.get, path: "\(base)/\(Endpoint.segment(sessionId))", base: .router)
    }

    static func enable(sessionId: String, name: String) -> Endpoint {
        Endpoint(
            .post,
            path: "\(base)/\(Endpoint.segment(sessionId))/\(Endpoint.segment(name))/enable",
            base: .router
        )
    }
}

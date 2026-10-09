import Foundation
import HarnaxCore

/// The one read behind the context-occupancy number, on the runtime's own route.
///
/// `GET /api/router/agent/context/{sessionId}`
/// (`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:183-191`)
/// forwards to whichever agent-service instance holds the session and passes the runtime's
/// `ContextUsageResponse` through untouched, so the keys are that data class's property names
/// (`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/ContextUsageResponse.kt:49-59`).
/// The console reads the same route (`harnax-webui/src/services/ant-design-pro/chat.ts:50-60`,
/// mounted at `harnax-webui/src/pages/session/index.tsx:142-153`).
///
/// Its own file beside `AgentPlanClient.swift` for the same reason: one router controller answers for several
/// screens, and this read failing has to leave the plan and the transcript untouched.
extension AdminClient: ContextUsageReading {
    /// A session the router cannot see answers two different ways and neither is a failure worth a message:
    /// never bound to an instance gives `ResultVo.success(null)`
    /// (`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/proxy/SessionRouterService.kt:397-407`),
    /// which lands here as `ContextUsage()` with `isReadable == false`; a session whose bound instance holds no
    /// live agent answers with a business error
    /// (`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/controller/AgentController.kt:123-134`),
    /// which lands as `.failure`. The caller treats the two alike — see `ContextUsage.isReadable`.
    public func contextUsage(sessionId: String) async -> Result<ContextUsage, APIError> {
        await client.send(ContextUsage.self, ContextUsageEndpoint.usage(sessionId: sessionId))
    }
}

/// A session with no context bound answers `ResultVo.success(null)`, so an absent `data` is a reply of its own
/// rather than an unpackable body.
extension ContextUsage: HarnaxVoid {}

enum ContextUsageEndpoint {
    static let base = "/api/router/agent/context"

    /// The business key goes in the path, so it is encoded first — the same rule the plan routes follow, and
    /// for the same reason: a key with a `/` in it would otherwise read as extra path segments on the way to
    /// the router.
    static func usage(sessionId: String) -> Endpoint {
        Endpoint(.get, path: "\(base)/\(Endpoint.segment(sessionId))", base: .router)
    }
}

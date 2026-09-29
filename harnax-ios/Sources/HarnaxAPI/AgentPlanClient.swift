import Foundation
import HarnaxCore

/// The two reads of `PlanReading`, on the runtime's own routes.
///
/// `GET /api/router/agent/session/{sessionId}/plans` and `.../current-plan`
/// (`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:157-178`)
/// both forward to whichever agent-service instance owns the session and pass the runtime's object through
/// untouched — `ResultVo<List<Any>>` and `ResultVo<Any?>`. The shape behind them is
/// `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/adaptor/PlanNoteAdaptor.kt:16-45`,
/// so the keys are that data class's property names and the timestamps are strings.
///
/// Beside `ChatHistoryClient.swift` rather than inside it: one controller class on the router, two screens, and
/// a conversation with no plan open still has to be able to load its transcript.
extension AdminClient: PlanReading {
    public func planNotes(sessionId: String) async -> Result<[PlanNote], APIError> {
        await client.send([PlanNote].self, AgentPlanEndpoint.notes(sessionId: sessionId))
    }

    /// `data` is absent when the conversation has no plan open, which is the ordinary answer rather than an
    /// error — see `CurrentPlan`.
    public func currentPlan(sessionId: String) async -> Result<CurrentPlan, APIError> {
        await client.send(CurrentPlan.self, AgentPlanEndpoint.current(sessionId: sessionId))
    }
}

/// A session with no plan open answers `ResultVo.success(null)`, so an absent `data` is a reply of its own
/// rather than an unpackable body.
extension CurrentPlan: HarnaxVoid {}

enum AgentPlanEndpoint {
    static let base = "/api/router/agent/session"

    /// Every plan this conversation has written. The business key goes in the path, so it is encoded first —
    /// the same rule the history route follows, and for the same reason: a key with a `/` in it would
    /// otherwise read as extra path segments on the way to the router.
    static func notes(sessionId: String) -> Endpoint {
        Endpoint(.get, path: "\(base)/\(Endpoint.segment(sessionId))/plans", base: .router)
    }

    static func current(sessionId: String) -> Endpoint {
        Endpoint(.get, path: "\(base)/\(Endpoint.segment(sessionId))/current-plan", base: .router)
    }
}

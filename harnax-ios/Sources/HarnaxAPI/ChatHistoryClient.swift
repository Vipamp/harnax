import Foundation
import HarnaxCore

/// `GET /api/router/agent/chat/history/{sessionId}` — the session's stored rows, in one call.
///
/// Backend: `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:144-152`,
/// which forwards to agent-service's `/api/agent/chat/history/{sessionId}`
/// (`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/controller/AgentController.kt:104-113`)
/// and passes its list through as `ResultVo<List<Any>>`. There is no paging and no limit: the whole
/// conversation arrives at once, and the team member rows are already merged in by the time it does
/// (`TeamHistoryReplay.kt:36-56`).
///
/// Like the command route this is plain JSON, so it rides the shared client and its header injection rather
/// than the streaming one. The router takes either the account's permanent key or the bearer token
/// (`harnax-session-router/README.md:187`), and `APIClient` injects the bearer for every base.
extension AdminClient: ChatHistoryReading {
    public func history(sessionId: String) async -> Result<[ChatHistoryLog], APIError> {
        // The id goes in the path, so it is encoded first: a session key with a slash in it would otherwise
        // read as extra path segments on the way to the router.
        let path = "/api/router/agent/chat/history/\(Endpoint.segment(sessionId))"
        return await client.send([ChatHistoryLog].self, Endpoint(.get, path: path, base: .router))
    }
}

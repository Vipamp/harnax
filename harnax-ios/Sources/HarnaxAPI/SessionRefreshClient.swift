import Foundation
import HarnaxCore

extension AdminClient: SessionRefreshing {
    /// `POST /api/admin/agents/refresh-sessions`. Answered `200` even when some sessions failed, so the
    /// caller reads the per-session verdict rather than the envelope code.
    ///
    /// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentController.kt:105-117`
    public func refreshSessions(_ ids: [String]) async -> Result<[SessionRefreshOutcome], APIError> {
        guard !ids.isEmpty else { return .success([]) }
        let body: Data
        do {
            body = try APIClient.encodeBody(SessionRefreshRequest(sessionIds: ids))
        } catch {
            return .failure(.decoding)
        }
        let endpoint = Endpoint(.post, path: "/api/admin/agents/refresh-sessions", body: body)
        return await client.send([SessionRefreshOutcome].self, endpoint)
    }
}

import Foundation
import HarnaxCore

/// `POST /api/router/agent/command`. Unlike the two streaming calls this is plain JSON, so it rides the
/// shared client and gets its header injection and 401 refresh for free.
///
/// Backend: `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:105-113`
extension AdminClient: AgentCommanding {
    public func command(_ request: CommandAgentRequest) async -> Result<AgentCommandReply, APIError> {
        let body: Data
        do {
            body = try APIClient.encodeBody(request)
        } catch {
            return .failure(.decoding)
        }
        let endpoint = Endpoint(.post, path: "/api/router/agent/command", body: body, base: .router)
        return await client.send(AgentCommandReply.self, endpoint)
    }
}

/// A command that changes nothing answers `ResultVo.success(null)`, so an absent `data` is a success with no
/// server text rather than a decode failure.
extension AgentCommandReply: HarnaxVoid {
    public init() {
        self.init(success: nil, message: nil)
    }
}

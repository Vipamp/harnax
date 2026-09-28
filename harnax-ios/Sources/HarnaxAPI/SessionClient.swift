import Foundation
import HarnaxCore

extension AdminClient: SessionCataloging {
    public func sessionPage(
        keyword: String?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<SessionSummary>, APIError> {
        await client.send(
            Page<SessionSummary>.self,
            SessionEndpoint.page(keyword: keyword, status: status, num: num, size: size)
        )
    }

    /// A rename that the row cannot describe — no numeric id, or a title that is blank once trimmed — is
    /// refused here rather than sent. The update route takes the create DTO with no `@Validated`
    /// (`SessionController.kt:91-95`), so its `@NotBlank` never runs and an empty string would reach the row
    /// and render as a card with no title.
    public func renameSession(_ session: SessionSummary, to title: String) async -> Result<EmptyResponse, APIError> {
        guard let id = session.id, let request = SessionRenameRequest(renaming: session, to: title) else {
            return .failure(.unpackable)
        }
        return await send(EmptyResponse.self) { try SessionEndpoint.rename(id: id, request: request) }
    }

    public func setSessionStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> {
        await client.send(EmptyResponse.self, SessionEndpoint.toggle(id: id, status: enabled.hxInt))
    }

    /// A refusal is a normal answer here: the runtime or the artifact store can hold the delete back, and the
    /// envelope message names what is still in the way (`SessionServiceImpl.kt:326-346`).
    public func deleteSession(id: Int64) async -> Result<EmptyResponse, APIError> {
        await client.send(EmptyResponse.self, SessionEndpoint.delete(id: id))
    }

    /// Clearing is a `CLEAR` on the router's command channel, not a session route: the admin service never
    /// touches the history rows, the runtime does. The router's verdict is handed back whole — `CLEAR`
    /// succeeds against a conversation that has no history, so the only honest report is the server's own
    /// sentence (`DefaultAgentRunner.kt:241-244`).
    public func clearMessages(sessionId: String) async -> Result<AgentCommandReply, APIError> {
        await command(CommandAgentRequest(sessionId: sessionId, command: .clear))
    }

    /// The write whose body can fail to serialise. A body this side cannot build never reaches the socket, so
    /// the failure is reported as the decoding case it is rather than as a refused request.
    private func send<T: Decodable>(
        _ type: T.Type,
        _ build: @escaping @Sendable () throws -> Endpoint
    ) async -> Result<T, APIError> {
        let endpoint: Endpoint
        do {
            endpoint = try build()
        } catch {
            return .failure(.decoding)
        }
        return await client.send(type, endpoint)
    }
}

import Foundation
import HarnaxCore

/// Saving an agent: the two routes the wizard submits to.
///
/// Nothing here second-guesses the server. A duplicate name, a model outside the tenant, a tool row that has
/// been deleted, a disabled MCP — all four are refused with the server's own sentence, and the form shows that
/// sentence rather than a local guess (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:101-204`).
extension AdminClient: AgentWriting {
    /// `ResultVo<Void>` (`AgentController.kt:71-80`), so the caller cannot learn the new id from this reply.
    public func createAgent(_ draft: AgentSaveDraft) async -> Result<EmptyResponse, APIError> {
        guard let endpoint = try? AgentEndpoint.create(draft) else { return .failure(.decoding) }
        return await client.send(EmptyResponse.self, endpoint)
    }

    /// Only the fields the draft carries are written; a binding group left nil keeps the rows it has
    /// (`AgentServiceImpl.kt:185-197`), which is why the form sends the groups the user actually reached.
    public func updateAgent(id: Int64, _ draft: AgentSaveDraft) async -> Result<EmptyResponse, APIError> {
        guard let endpoint = try? AgentEndpoint.update(id: id, draft) else { return .failure(.decoding) }
        return await client.send(EmptyResponse.self, endpoint)
    }
}

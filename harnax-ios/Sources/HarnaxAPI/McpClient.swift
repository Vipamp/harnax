import Foundation
import HarnaxCore

extension AdminClient: McpCataloging {
    public func mcpPage(
        keyword: String?,
        status: Int?,
        type: String?,
        num: Int,
        size: Int
    ) async -> Result<Page<McpServerRow>, APIError> {
        await client.send(
            Page<McpServerRow>.self,
            McpEndpoint.page(keyword: keyword, status: status, type: type, num: num, size: size)
        )
    }

    public func mcpServer(id: Int64) async -> Result<McpServerRow, APIError> {
        await client.send(McpServerRow.self, McpEndpoint.detail(id: id))
    }

    public func createMCPServer(_ draft: McpServerDraft) async -> Result<EmptyResponse, APIError> {
        await send(EmptyResponse.self) { try McpEndpoint.create(draft) }
    }

    public func updateMCPServer(id: Int64, patch: McpServerPatch) async -> Result<EmptyResponse, APIError> {
        await send(EmptyResponse.self) { try McpEndpoint.update(id: id, patch: patch) }
    }

    public func setMcpStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> {
        await client.send(EmptyResponse.self, McpEndpoint.toggle(id: id, status: enabled.hxInt))
    }

    public func mcpRelatedAgents(id: Int64) async -> Result<[RelatedAgent], APIError> {
        await client.send([RelatedAgent].self, McpEndpoint.relatedAgents(id: id))
    }

    public func deleteMCPServer(id: Int64) async -> Result<EmptyResponse, APIError> {
        await client.send(EmptyResponse.self, McpEndpoint.delete(id: id))
    }

    public func testMcpConnectivity(id: Int64) async -> Result<Bool, APIError> {
        await client.send(Bool.self, McpEndpoint.connectivityTest(id: id))
    }

    public func mcpTools(id: Int64) async -> Result<[McpToolRow], APIError> {
        await client.send([McpToolRow].self, McpEndpoint.tools(id: id))
    }

    public func mcpOAuthStatus(id: Int64) async -> Result<McpOAuthStatus, APIError> {
        await client.send(McpOAuthStatus.self, McpEndpoint.oauthStatus(id: id))
    }

    public func revokeMcpOAuth(id: Int64) async -> Result<McpOAuthRevokeResult, APIError> {
        await client.send(McpOAuthRevokeResult.self, McpEndpoint.oauthRevoke(id: id))
    }

    public func mcpAuthorizeURL(id: Int64, scope: String?) async -> Result<McpOAuthAuthorization, APIError> {
        await client.send(McpOAuthAuthorization.self, McpEndpoint.oauthAuthorizeURL(id: id, scope: scope))
    }

    /// The two writes whose body can fail to serialise. A body this side cannot build never reaches the
    /// socket, so the failure is reported as the decoding case it is rather than as a refused request.
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

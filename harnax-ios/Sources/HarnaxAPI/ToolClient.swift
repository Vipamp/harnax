import Foundation
import HarnaxCore

/// The tool client. Like the agent and team surfaces it is an extension of the one `AdminClient`, so the
/// tool routes ride the same base address, bearer token, tenant header and token refresh as everything else
/// under `/api/admin/**`.
///
/// The method names carry the domain because one type conforms to every cataloging protocol at once.
extension AdminClient: ToolCataloging {
    public func toolPage(
        keyword: String?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<ToolSummary>, APIError> {
        await client.send(
            Page<ToolSummary>.self,
            ToolEndpoint.page(keyword: keyword, status: status, num: num, size: size)
        )
    }

    public func toolDetail(id: Int64) async -> Result<ToolSummary, APIError> {
        await client.send(ToolSummary.self, ToolEndpoint.detail(id: id))
    }

    /// `/available` answers `List<AgentToolResponse>` — the same DTO as the page route, so the same row
    /// type decodes it, `envParams` and all (`AgentToolController.kt:59-61`). It is unpaged, so there is
    /// no `Page` wrapper to unwrap and no total to reconcile.
    public func availableTools() async -> Result<[ToolSummary], APIError> {
        await client.send([ToolSummary].self, ToolEndpoint.available)
    }
}

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
}

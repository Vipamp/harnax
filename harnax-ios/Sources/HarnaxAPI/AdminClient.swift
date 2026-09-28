import Foundation
import HarnaxCore

/// Reads the admin domain M0 lists. The feature layer gets one method per list screen and never sees a
/// path, a header or an envelope.
public struct AdminClient: AgentCataloging {
    private let client: APIClient

    public init(client: APIClient) {
        self.client = client
    }

    public func page(num: Int, size: Int) async -> Result<Page<AgentSummary>, APIError> {
        await client.send(Page<AgentSummary>.self, AdminEndpoint.agentsPage(num: num, size: size))
    }
}

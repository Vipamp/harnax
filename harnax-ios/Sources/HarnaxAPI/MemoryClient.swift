import Foundation
import HarnaxCore

/// The memory half of the admin surface (`MemoryEndpoint.swift`).
///
/// Lives on `AdminClient` so it shares the bearer injection, the 401 replay and the tenant header
/// (`AdminClient.swift:4-6`). The tenant header is the whole scoping mechanism here: the routes take no user
/// and no tenant parameter (`MemoryController.kt:30-31`), so nothing this client builds can name another
/// account's memory.
extension AdminClient: MemoryCataloging {
    public func list() async -> Result<[MemoryAgentRow], APIError> {
        await client.send([MemoryAgentRow].self, MemoryEndpoint.list())
    }

    public func read(agentName: String) async -> Result<MemoryDetail, APIError> {
        await client.send(MemoryDetail.self, MemoryEndpoint.read(agentName: agentName))
    }

    public func delete(agentName: String) async -> Result<MemoryDeleteResult, APIError> {
        await client.send(MemoryDeleteResult.self, MemoryEndpoint.delete(agentName: agentName))
    }
}

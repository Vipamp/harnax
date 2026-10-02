import Foundation
import HarnaxCore

extension AdminClient: ExecutorReading {
    /// The agent's own row, with the four binding lists the conversation's tools and CLI panels need.
    public func agent(id: Int64) async -> Result<AgentSummary, APIError> {
        await client.send(AgentSummary.self, AgentEndpoint.by(id: id))
    }

    /// The team's own row, with the membership and the two availability flags no other route answers.
    public func team(id: Int64) async -> Result<TeamSummary, APIError> {
        await client.send(TeamSummary.self, TeamEndpoint.by(id: id))
    }
}

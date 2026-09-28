import Foundation
import HarnaxCore

extension AdminClient: AgentCataloging {
    /// The list endpoint already returns the four binding lists and the session list per row, so a card
    /// drill-down costs nothing extra.
    public func page(name: String?, status: Int?, num: Int, size: Int) async -> Result<Page<AgentSummary>, APIError> {
        await client.send(Page<AgentSummary>.self, AgentEndpoint.page(name: name, status: status, num: num, size: size))
    }

    public func setStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> {
        await client.send(EmptyResponse.self, AgentEndpoint.toggle(id: id, status: enabled.hxInt))
    }

    /// A refusal is a normal answer here: the backend rejects the delete while teams or sessions still
    /// reference the agent, and the envelope message is the sentence the card has to show.
    public func delete(id: Int64) async -> Result<EmptyResponse, APIError> {
        await client.send(EmptyResponse.self, AgentEndpoint.delete(id: id))
    }

    public func relatedSessions(id: Int64) async -> Result<[RelatedSession], APIError> {
        await client.send([RelatedSession].self, AgentEndpoint.relatedSessions(id: id))
    }
}

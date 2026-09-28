import Foundation
import HarnaxCore

extension AdminClient: TeamCataloging {
    public func teamPage(name: String?, status: Int?, num: Int, size: Int) async -> Result<Page<TeamSummary>, APIError> {
        await client.send(Page<TeamSummary>.self, TeamEndpoint.page(name: name, status: status, num: num, size: size))
    }

    public func setTeamStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> {
        await client.send(EmptyResponse.self, TeamEndpoint.toggle(id: id, status: enabled.hxInt))
    }

    public func deleteTeam(id: Int64) async -> Result<EmptyResponse, APIError> {
        await client.send(EmptyResponse.self, TeamEndpoint.delete(id: id))
    }

    public func teamRelatedSessions(id: Int64) async -> Result<[RelatedSession], APIError> {
        await client.send([RelatedSession].self, TeamEndpoint.relatedSessions(id: id))
    }
}

import Foundation
import HarnaxCore

extension AdminClient: CliCataloging {
    public func cliPage(name: String?, status: Int?, num: Int, size: Int) async -> Result<Page<CliSummary>, APIError> {
        await client.send(Page<CliSummary>.self, CliEndpoint.page(name: name, status: status, num: num, size: size))
    }

    public func cliDetail(id: Int64) async -> Result<CliSummary, APIError> {
        await client.send(CliSummary.self, CliEndpoint.detail(id: id))
    }

    public func cliRelatedAgents(id: Int64) async -> Result<[RelatedAgent], APIError> {
        await client.send([RelatedAgent].self, CliEndpoint.relatedAgents(id: id))
    }

    public func cliRelatedSessions(id: Int64) async -> Result<[RelatedSession], APIError> {
        await client.send([RelatedSession].self, CliEndpoint.relatedSessions(id: id))
    }

    public func setCliStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> {
        await client.send(EmptyResponse.self, CliEndpoint.toggle(id: id, status: enabled.hxInt))
    }
}

import Foundation
import HarnaxCore

extension AdminClient: ModelCataloging {
    public func providerPage(
        name: String?,
        type: String?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<ModelProviderSummary>, APIError> {
        await client.send(
            Page<ModelProviderSummary>.self,
            ModelEndpoint.providerPage(name: name, type: type, status: status, num: num, size: size)
        )
    }

    /// A failed count read is not a failed list: the card keeps its title and says the counts are
    /// unavailable, because a provider this tenant does not own answers this route with an error while
    /// still being perfectly readable.
    public func providerStats(id: Int64) async -> Result<ModelProviderStats, APIError> {
        await client.send(ModelProviderStats.self, ModelEndpoint.providerStats(id: id))
    }

    public func setProviderStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> {
        await client.send(EmptyResponse.self, ModelEndpoint.providerToggle(id: id, status: enabled.hxInt))
    }

    public func deleteProvider(id: Int64) async -> Result<EmptyResponse, APIError> {
        await client.send(EmptyResponse.self, ModelEndpoint.providerDelete(id: id))
    }

    /// `id == nil` creates. Neither route answers with the new id, so the list is the only way back to it.
    public func saveProvider(id: Int64?, request: ModelProviderSaveRequest) async -> Result<EmptyResponse, APIError> {
        guard let body = try? APIClient.encodeBody(request) else { return .failure(.decoding) }
        let endpoint: Endpoint
        if let id {
            endpoint = ModelEndpoint.providerUpdate(id: id, body: body)
        } else {
            endpoint = ModelEndpoint.providerCreate(body: body)
        }
        return await client.send(EmptyResponse.self, endpoint)
    }

    /// The one boolean on this surface. There is no timing or reason attached to it, and the service
    /// returns `true` without opening a socket today, so the caller labels the answer rather than
    /// presenting it as a measurement.
    public func testProvider(id: Int64) async -> Result<Bool, APIError> {
        await client.send(Bool.self, ModelEndpoint.providerTest(id: id))
    }

    public func modelPage(
        providerID: Int64,
        name: String?,
        status: Int?,
        tags: [String],
        num: Int,
        size: Int
    ) async -> Result<Page<ModelSummary>, APIError> {
        await client.send(
            Page<ModelSummary>.self,
            ModelEndpoint.modelPage(
                providerID: providerID,
                name: name,
                status: status,
                tags: tags,
                num: num,
                size: size
            )
        )
    }

    /// Unscoped, so the wizards get one page across every provider — the rows are the same DTO either way.
    public func modelChoices(num: Int, size: Int) async -> Result<Page<ModelSummary>, APIError> {
        await client.send(Page<ModelSummary>.self, ModelEndpoint.modelChoices(num: num, size: size))
    }

    public func setModelStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> {
        await client.send(EmptyResponse.self, ModelEndpoint.modelToggle(id: id, status: enabled.hxInt))
    }

    public func deleteModel(id: Int64) async -> Result<EmptyResponse, APIError> {
        await client.send(EmptyResponse.self, ModelEndpoint.modelDelete(id: id))
    }

    public func saveModel(id: Int64?, request: ModelSaveRequest) async -> Result<EmptyResponse, APIError> {
        guard let body = try? APIClient.encodeBody(request) else { return .failure(.decoding) }
        let endpoint: Endpoint
        if let id {
            endpoint = ModelEndpoint.modelUpdate(id: id, body: body)
        } else {
            endpoint = ModelEndpoint.modelCreate(body: body)
        }
        return await client.send(EmptyResponse.self, endpoint)
    }
}

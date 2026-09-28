import Foundation
import HarnaxCore

extension AdminClient: ApiKeyCataloging {
    public func apiKeyPage(keyword: String?, enabled: Int?, num: Int, size: Int) async -> Result<Page<ApiKeySummary>, APIError> {
        await client.send(
            Page<ApiKeySummary>.self,
            ApiKeyEndpoint.page(keyword: keyword, enabled: enabled, num: num, size: size)
        )
    }

    public func createApiKey(_ draft: ApiKeyDraft) async -> Result<ApiKeyCreatedSummary, APIError> {
        guard let endpoint = try? ApiKeyEndpoint.create(draft) else { return .failure(.decoding) }
        return await client.send(ApiKeyCreatedSummary.self, endpoint)
    }

    public func updateApiKey(id: Int64, _ change: ApiKeyChange) async -> Result<EmptyResponse, APIError> {
        guard let endpoint = try? ApiKeyEndpoint.update(id: id, change) else { return .failure(.decoding) }
        return await client.send(EmptyResponse.self, endpoint)
    }

    public func setApiKeyStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> {
        await client.send(EmptyResponse.self, ApiKeyEndpoint.toggle(id: id, enabled: enabled.hxInt))
    }

    public func deleteApiKey(id: Int64) async -> Result<EmptyResponse, APIError> {
        await client.send(EmptyResponse.self, ApiKeyEndpoint.delete(id: id))
    }

    public func regenerateApiKey(id: Int64) async -> Result<ApiKeyCreatedSummary, APIError> {
        await client.send(ApiKeyCreatedSummary.self, ApiKeyEndpoint.regenerate(id: id))
    }
}

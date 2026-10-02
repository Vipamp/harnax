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

    /// `ResultVo<ApiKeyResponse?>` (`ApiKeyController.kt:128`): the envelope's `data` is null when the account
    /// has no permanent row, and that is the answer rather than a failure, so the payload below reads it.
    public func myPermanentKey() async -> Result<ApiKeySummary?, APIError> {
        await client.send(PermanentKeyPayload.self, ApiKeyEndpoint.myPermanentKey()).map(\.row)
    }

    /// The only leg of this screen that ever carries a usable secret, and it carries it once
    /// (`ApiKeyServiceImpl.kt:240-258`).
    public func regenerateMyPermanentKey() async -> Result<ApiKeyCreatedSummary, APIError> {
        await client.send(ApiKeyCreatedSummary.self, ApiKeyEndpoint.regeneratePermanent())
    }
}

/// The read of the account's own key: a row, or nothing at all.
///
/// `ResponseMapper` answers an absent `data` with `.unpackable` unless the payload declares itself `HarnaxVoid`
/// (`Transport/ResponseMapper.swift:25-28`), which is how every `ResultVo<Void>` route in this stack is read.
/// This borrows the same escape and keeps the row when there is one: the decoder is handed the `data` object
/// itself, so a present key decodes as the usual eleven-column row and a null one decodes never — the
/// synthesised `init()` leaves `row` nil, and the caller sees `.success(nil)`.
///
/// No raw key exists on this type because none exists on the DTO: `ApiKeyResponse.kt:8-41` carries the
/// server-computed `keyPrefix` and the SHA-256 digest stays in the database (`ApiKeyServiceImpl.kt:80-82`).
private struct PermanentKeyPayload: Decodable, HarnaxVoid {
    let row: ApiKeySummary?

    init() { row = nil }

    init(from decoder: any Decoder) throws { row = try ApiKeySummary(from: decoder) }
}

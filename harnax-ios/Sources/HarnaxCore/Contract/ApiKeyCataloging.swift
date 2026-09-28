import Foundation

/// Everything the API Key screens do, read and write.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ApiKeyController.kt:23` on
/// the `/api/admin/api-keys` prefix.
///
/// Two routes on that controller are not here: `GET /my-permanent-key` and `POST /regenerate-permanent`
/// (`:126-148`) belong to the account screen, not to this list, and the paged read never returns those
/// rows anyway (`key_type = 'TEMPORARY'`, `ApiKeyMapper.xml:128-147`).
public protocol ApiKeyCataloging: Sendable {
    /// `keyword` matches the name *or* the key prefix (`ApiKeyMapper.xml:133-136`); `enabled` is the raw
    /// 0/1 column and `all` leaves it off the URL (`ApiKeyController.kt:36-39`).
    ///
    /// An administrator sees every tenant's rows and everyone's creator, because the controller nulls both
    /// scoping arguments for admin (`:41-45`).
    func apiKeyPage(keyword: String?, enabled: Int?, num: Int, size: Int) async -> Result<Page<ApiKeySummary>, APIError>
    /// The only two calls in this protocol that carry a raw key back, and it comes back exactly once
    /// (`ApiKeyServiceImpl.kt:105-110`).
    func createApiKey(_ draft: ApiKeyDraft) async -> Result<ApiKeyCreatedSummary, APIError>
    /// `name` is not editable: the update DTO has no such field (`ApiKeyUpdateRequest.kt:6-21`).
    func updateApiKey(id: Int64, _ change: ApiKeyChange) async -> Result<EmptyResponse, APIError>
    func setApiKeyStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError>
    func deleteApiKey(id: Int64) async -> Result<EmptyResponse, APIError>
    /// Invalidates the key the caller is holding. No protected-type check runs here even though update,
    /// toggle and delete all refuse (`ApiKeyServiceImpl.kt:113-183`) — that asymmetry is a deliberate
    /// escape hatch, and the backend's refusal is the only gate iOS applies.
    func regenerateApiKey(id: Int64) async -> Result<ApiKeyCreatedSummary, APIError>
}

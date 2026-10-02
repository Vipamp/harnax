import Foundation

/// Everything the API Key screens do, read and write.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ApiKeyController.kt:23` on
/// the `/api/admin/api-keys` prefix.
///
/// Two shapes of route share this protocol. The first six are the management list's, which the web console
/// puts behind an administrator-only nav entry (`harnax-webui/config/routes.ts:136-141`) and which never
/// carries a permanent or system row (`key_type = 'TEMPORARY'`, `ApiKeyMapper.xml:128-147`). The last two are
/// the signed-in account's own key and answer per user, not per admin — the controller resolves the row from
/// `SecurityUtils.getCurrentUser()?.id` alone (`:129`, `:141`), which is why `harnax-ios/DESIGN.md:360` (O5)
/// keeps the list gated but shows the account screen the account's own key anyway.
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

    // MARK: - the caller's own permanent key

    /// The account's permanent key, or `nil` when the account has none.
    ///
    /// `GET /my-permanent-key` (`ApiKeyController.kt:126-136`) resolves the row from the caller's own id and
    /// runs no administrator check, and the row is whatever `selectPermanentKeyByUserId` picks: the caller's
    /// `key_type = 'PERMANENT'` row that is still active (`ApiKeyMapper.xml:118-125`). A missing row is
    /// `ResultVo<ApiKeyResponse?>` with `data` null, which is a fact about the account and not a failure —
    /// `nil`, never `.unpackable`.
    ///
    /// Same DTO as a list row (`ApiKeyResponse.kt:8-41`), so the secret itself is not in it: only the
    /// `keyPrefix` the server computed from it (`:82`). iOS cannot reveal a permanent key it has already
    /// hidden — the only route that ever hands the value out again is the rotate below.
    func myPermanentKey() async -> Result<ApiKeySummary?, APIError>

    /// Replaces the account's permanent key and publishes the new raw value exactly once.
    ///
    /// `POST /regenerate-permanent` (`ApiKeyController.kt:138-148`) takes no body and answers
    /// `ApiKeyCreatedResponse`; its own `@Operation` description says every connection using the old key is
    /// invalidated, and `ApiKeyServiceImpl.kt:236-258` really does rewrite the hash and the encrypted copy.
    /// The refusal the service throws when the account has no row (`:237-238`) is the only gate here, so a
    /// screen that has read `nil` above has nothing to rotate.
    func regenerateMyPermanentKey() async -> Result<ApiKeyCreatedSummary, APIError>
}

/// Doubles that predate the account screen's two routes.
///
/// `ApiKeyCataloging` has three conformers outside this module — the real facade, the test doubles and the
/// debug walk-through catalog — and only the first can reach these two endpoints, so the requirement is
/// additive rather than a compile error in code this change does not own. The default is a failure, matching
/// the discipline those doubles already keep for a call nobody scripted (`FakeApiKeys.next(from:)` answers
/// `.decoding` for an unqueued request): a screen that reaches an unwired route says "cannot be read", it does
/// not say "no key". `ApiKeyClient.swift` is the one implementation that replaces both.
public extension ApiKeyCataloging {
    func myPermanentKey() async -> Result<ApiKeySummary?, APIError> { .failure(.decoding) }

    func regenerateMyPermanentKey() async -> Result<ApiKeyCreatedSummary, APIError> { .failure(.decoding) }
}

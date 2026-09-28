import Foundation

/// Everything the model screen does, on both of its levels.
///
/// The routes are `/api/admin/model-providers/**` and `/api/admin/models/**`
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ModelProviderController.kt:33-141`,
/// `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ModelController.kt:32-121`). Every
/// method carries its level in its name, because one `AdminClient` conforms to this protocol and to
/// `AgentCataloging` / `TeamCataloging` at the same time, and `setStatus(id:enabled:)` cannot mean two
/// rows on one type.
///
/// Two shapes that are easy to guess wrong and are settled here rather than at the call site:
/// - the write endpoints answer `ResultVo<Void>`, so a create hands back no id — the list has to be
///   refetched (`ModelController.kt:74-96`, `ModelProviderController.kt:72-94`);
/// - `connectivityTest` answers a bare `Boolean` with no timing or reason attached, and the service today
///   returns `true` for every provider
///   (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ModelProviderServiceImpl.kt:163-169`).
public protocol ModelCataloging: Sendable {
    /// The first level. `type` is the technical provider type, and `name` a `LIKE` keyword; both optional
    /// (`ModelProviderController.kt:35-41`). The `isPublic` the console also filters on is not offered here
    /// — iOS reads a shared provider's own badge instead of filtering to it.
    func providerPage(
        name: String?,
        type: String?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<ModelProviderSummary>, APIError>

    /// The card's three counts. Owner-only server-side, so another tenant's public provider fails here.
    func providerStats(id: Int64) async -> Result<ModelProviderStats, APIError>

    /// Refused while the provider still has an enabled model
    /// (`ModelProviderServiceImpl.kt:126-133`), with the server's sentence in the envelope message.
    func setProviderStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError>

    /// Refused while any live model — enabled or not — hangs off the provider
    /// (`ModelProviderServiceImpl.kt:138-148`).
    func deleteProvider(id: Int64) async -> Result<EmptyResponse, APIError>

    /// `id == nil` creates. The response carries no id either way, so the caller refreshes.
    func saveProvider(id: Int64?, request: ModelProviderSaveRequest) async -> Result<EmptyResponse, APIError>

    /// `ResultVo<Boolean>`; a `code != 200` answer — an id this tenant does not own — is the error case.
    func testProvider(id: Int64) async -> Result<Bool, APIError>

    /// The second level, always scoped to one provider. `tags` is the capability multi-select, joined with
    /// commas for the wire (`ModelServiceImpl.kt:47-52` splits them again).
    func modelPage(
        providerID: Int64,
        name: String?,
        status: Int?,
        tags: [String],
        num: Int,
        size: Int
    ) async -> Result<Page<ModelSummary>, APIError>

    /// Enabling a model is refused while its provider is stopped
    /// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ModelServiceImpl.kt:186-198`).
    func setModelStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError>

    /// Refused while agents, teams or sessions still point at the model; the message names the counts
    /// (`ModelServiceImpl.kt:200-221`).
    func deleteModel(id: Int64) async -> Result<EmptyResponse, APIError>

    func saveModel(id: Int64?, request: ModelSaveRequest) async -> Result<EmptyResponse, APIError>
}

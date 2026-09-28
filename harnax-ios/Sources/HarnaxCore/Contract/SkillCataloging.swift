import Foundation

/// Everything the skill domain does: the source half of the split screen, the two-step sync behind its
/// modal, the right-hand skill table and its detail read.
///
/// Deliberately kept out of `Facades.swift`, which every domain shares. Backend surface:
/// `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillSourceController.kt` and
/// `SkillController.kt`. The legacy `/api/admin/skill-repositories` pair and the deprecated
/// `POST /api/admin/skills/batch` are not modelled — the first is unused by the console and the second
/// answers the same job as `install` (`SkillController.kt:125-135`).
public protocol SkillCataloging: Sendable {
    /// Sources page. `name` is a `LIKE` keyword, `sourceType` the raw column string, `status` the 0/1
    /// flag; all three are optional server-side (`SkillSourceController.kt:28-38`).
    ///
    /// This is the endpoint the list screen uses rather than `/skill-sources/active`, because only the
    /// paged one fills `enabledSkillCount`, and that count is the delete gate.
    func sourcePage(
        name: String?,
        sourceType: String?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<SkillSourceSummary>, APIError>

    func source(id: Int64) async -> Result<SkillSourceSummary, APIError>

    /// Step one of a sync: read the source without storing anything. A failure must not be rendered as an
    /// empty preview — the two say opposite things (`SkillSourceController.kt:127-135`).
    func preview(sourceID: Int64) async -> Result<[SkillPreviewItem], APIError>

    /// Step two. `names == nil` stores the whole source; an empty array stores nothing
    /// (`SkillSourceController.kt:74-84`).
    func install(sourceID: Int64, names: [String]?) async -> Result<SkillInstallOutcome, APIError>

    /// Create plus immediate install, which is why the answer carries both halves.
    func createSource(_ payload: SkillSourceCreatePayload) async -> Result<SkillSourceInstallResult, APIError>

    /// Config only — a changed source still has to be re-installed before it takes effect.
    func updateSource(id: Int64, _ payload: SkillSourceUpdatePayload) async -> Result<EmptyResponse, APIError>

    func deleteSource(id: Int64) async -> Result<EmptyResponse, APIError>

    func setSourceStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError>

    /// A ZIP source, created and installed in one call. `fileName` goes into the `file` part's
    /// `filename=` parameter and `name` is a sibling form field, never a query item
    /// (`SkillSourceController.kt:137-160`).
    func uploadSource(
        name: String,
        fileName: String,
        payload: Data
    ) async -> Result<SkillSourceInstallResult, APIError>

    /// The right-hand table: skills of one source, paged (`SkillController.kt:35-58`).
    func skillPage(
        name: String?,
        repositoryID: Int64?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<SkillItem>, APIError>

    /// The detail read, and the only one: `skillmd` and the `resources` blob arrive together, so there is
    /// no second call for file contents (`SkillController.kt:64-72`).
    func skill(id: Int64) async -> Result<SkillItem, APIError>

    /// Refused while the row's own `boundAgentCount` / `boundTeamCount` are non-zero; the UI gates on the
    /// counts before sending (`SkillController.kt:101-112`).
    func setSkillStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError>
}

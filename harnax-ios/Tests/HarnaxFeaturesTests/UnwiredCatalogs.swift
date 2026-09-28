import Foundation
import HarnaxCore

/// The five context-tab catalogs for tests that never open the tab.
///
/// `AppModelTests` builds a `HarnaxDependencies` to exercise restore, login and the tab bar; the catalogs
/// it carries exist only because the composition root holds them. Every method fails rather than returning
/// an empty page, so a screen that reaches this object in a test shows up as a failing call instead of a
/// list that quietly renders nothing.
struct UnwiredCatalogs: ModelCataloging, ToolCataloging, SkillCataloging, McpCataloging, CliCataloging {
    private func unwired() -> APIError {
        .business(code: -1, message: "this test never wires the context tab")
    }

    // MARK: - models

    func providerPage(
        name: String?,
        type: String?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<ModelProviderSummary>, APIError> { .failure(unwired()) }

    func providerStats(id: Int64) async -> Result<ModelProviderStats, APIError> { .failure(unwired()) }

    func setProviderStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func deleteProvider(id: Int64) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func saveProvider(
        id: Int64?,
        request: ModelProviderSaveRequest
    ) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func testProvider(id: Int64) async -> Result<Bool, APIError> { .failure(unwired()) }

    func modelPage(
        providerID: Int64,
        name: String?,
        status: Int?,
        tags: [String],
        num: Int,
        size: Int
    ) async -> Result<Page<ModelSummary>, APIError> { .failure(unwired()) }

    func setModelStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func deleteModel(id: Int64) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func saveModel(
        id: Int64?,
        request: ModelSaveRequest
    ) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    // MARK: - tools

    func toolPage(
        keyword: String?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<ToolSummary>, APIError> { .failure(unwired()) }

    func toolDetail(id: Int64) async -> Result<ToolSummary, APIError> { .failure(unwired()) }

    // MARK: - skills

    func sourcePage(
        name: String?,
        sourceType: String?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<SkillSourceSummary>, APIError> { .failure(unwired()) }

    func source(id: Int64) async -> Result<SkillSourceSummary, APIError> { .failure(unwired()) }

    func preview(sourceID: Int64) async -> Result<[SkillPreviewItem], APIError> { .failure(unwired()) }

    func install(
        sourceID: Int64,
        names: [String]?
    ) async -> Result<SkillInstallOutcome, APIError> { .failure(unwired()) }

    func createSource(
        _ payload: SkillSourceCreatePayload
    ) async -> Result<SkillSourceInstallResult, APIError> { .failure(unwired()) }

    func updateSource(
        id: Int64,
        _ payload: SkillSourceUpdatePayload
    ) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func deleteSource(id: Int64) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func setSourceStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func uploadSource(
        name: String,
        fileName: String,
        payload: Data
    ) async -> Result<SkillSourceInstallResult, APIError> { .failure(unwired()) }

    func skillPage(
        name: String?,
        repositoryID: Int64?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<SkillItem>, APIError> { .failure(unwired()) }

    func skill(id: Int64) async -> Result<SkillItem, APIError> { .failure(unwired()) }

    func setSkillStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    // MARK: - MCP

    func mcpPage(
        keyword: String?,
        status: Int?,
        type: String?,
        num: Int,
        size: Int
    ) async -> Result<Page<McpServerRow>, APIError> { .failure(unwired()) }

    func mcpServer(id: Int64) async -> Result<McpServerRow, APIError> { .failure(unwired()) }

    func createMCPServer(_ draft: McpServerDraft) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func updateMCPServer(
        id: Int64,
        patch: McpServerPatch
    ) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func setMcpStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func mcpRelatedAgents(id: Int64) async -> Result<[RelatedAgent], APIError> { .failure(unwired()) }

    func deleteMCPServer(id: Int64) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }

    func testMcpConnectivity(id: Int64) async -> Result<Bool, APIError> { .failure(unwired()) }

    func mcpTools(id: Int64) async -> Result<[McpToolRow], APIError> { .failure(unwired()) }

    func mcpOAuthStatus(id: Int64) async -> Result<McpOAuthStatus, APIError> { .failure(unwired()) }

    func revokeMcpOAuth(id: Int64) async -> Result<McpOAuthRevokeResult, APIError> { .failure(unwired()) }

    func mcpAuthorizeURL(
        id: Int64,
        scope: String?
    ) async -> Result<McpOAuthAuthorization, APIError> { .failure(unwired()) }

    // MARK: - CLI packages

    func cliPage(
        name: String?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<CliSummary>, APIError> { .failure(unwired()) }

    func cliDetail(id: Int64) async -> Result<CliSummary, APIError> { .failure(unwired()) }

    func cliRelatedAgents(id: Int64) async -> Result<[RelatedAgent], APIError> { .failure(unwired()) }

    func cliRelatedSessions(id: Int64) async -> Result<[RelatedSession], APIError> { .failure(unwired()) }

    func setCliStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> { .failure(unwired()) }
}

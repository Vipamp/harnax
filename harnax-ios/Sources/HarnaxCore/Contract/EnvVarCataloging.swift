import Foundation

/// Everything the environment-variable screens do, read and write.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/EnvVariableController.kt:16`
/// on the `/api/admin/env-variables` prefix. Two absences are deliberate and are both facts about the
/// stack, not oversights: the paged read takes no status filter (`:25-37` answers `keyword` only, unlike
/// the agent and API Key pages), and there is no `GET /list` here because that projection
/// (`EnvVariableServiceImpl.kt:245-271`) feeds the *agent* binding form, which owns it.
///
/// Create, update, toggle and delete all answer `ResultVo<Void>` (`EnvVariableController.kt:61-105`), so
/// a saved row is re-read from the list rather than taken from the response.
public protocol EnvVarCataloging: Sendable {
    /// Rows are already scoped to the caller and their tenant server-side
    /// (`EnvVariableServiceImpl.kt:35-41`), so an empty list means "you typed none", not "you may not see
    /// any".
    func envVarPage(keyword: String?, num: Int, size: Int) async -> Result<Page<EnvVarSummary>, APIError>
    func createEnvVar(_ draft: EnvVarDraft) async -> Result<EmptyResponse, APIError>
    func updateEnvVar(id: Int64, _ change: EnvVarChange) async -> Result<EmptyResponse, APIError>
    /// Refused while any agent still binds the variable, on disable as well as on delete
    /// (`EnvVariableServiceImpl.kt:180-219`); the server's own sentence names up to five of them.
    func setEnvVarStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError>
    func deleteEnvVar(id: Int64) async -> Result<EmptyResponse, APIError>
}

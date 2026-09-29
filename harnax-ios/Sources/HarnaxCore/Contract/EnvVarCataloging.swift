import Foundation

/// Everything the environment-variable screens do, read and write.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/EnvVariableController.kt:16`
/// on the `/api/admin/env-variables` prefix. One absence is deliberate and is a fact about the stack, not an
/// oversight: the paged read takes no status filter (`:25-37` answers `keyword` only, unlike the agent and
/// API Key pages).
///
/// Create, update, toggle and delete all answer `ResultVo<Void>` (`EnvVariableController.kt:61-105`), so
/// a saved row is re-read from the list rather than taken from the response.
public protocol EnvVarCataloging: Sendable {
    /// Rows are already scoped to the caller and their tenant server-side
    /// (`EnvVariableServiceImpl.kt:35-41`), so an empty list means "you typed none", not "you may not see
    /// any".
    func envVarPage(keyword: String?, num: Int, size: Int) async -> Result<Page<EnvVarSummary>, APIError>

    /// `GET /api/admin/env-variables/list` (`EnvVariableController.kt:39-46`) — the unpaged dropdown
    /// projection, and the binding wizard's rather than a list screen's: the agent and team forms own it,
    /// because they are the only callers that resolve an id into a variable to bind.
    ///
    /// The server has already narrowed it to what the *adding* user may add: rows built for
    /// `creator == currentUsername` inside the caller's tenant, then `enabled == 1`
    /// (`EnvVariableServiceImpl.kt:245-252`). A reference an existing agent already holds may point at
    /// another user's variable, since that one resolves by tenant instead — so this read is the set of
    /// candidates for a *new* choice, never a validator for an old one.
    ///
    /// `displayValue` on a sensitive row is a mask (`EnvVarCandidate`), so the result is for labels only;
    /// the wizard submits the id.
    func envVarCandidates() async -> Result<[EnvVarCandidate], APIError>

    func createEnvVar(_ draft: EnvVarDraft) async -> Result<EmptyResponse, APIError>
    func updateEnvVar(id: Int64, _ change: EnvVarChange) async -> Result<EmptyResponse, APIError>
    /// Refused while any agent still binds the variable, on disable as well as on delete
    /// (`EnvVariableServiceImpl.kt:180-219`); the server's own sentence names up to five of them.
    func setEnvVarStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError>
    func deleteEnvVar(id: Int64) async -> Result<EmptyResponse, APIError>
}
